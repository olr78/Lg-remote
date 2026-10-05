import Foundation

/// Клієнт LG webOS SSAP. UF950V = webOS 3.0 → ws://<ip>:3000 без TLS.
/// Логіка та сама, що в Android-версії (LgTvClient.kt).
final class LgTvClient: NSObject, ObservableObject {

    struct Item: Identifiable, Hashable {
        let id: String
        let title: String
    }

    @Published var status = "Не підключено"
    @Published var connected = false
    @Published var volume: Int?
    @Published var muted = false

    private var session: URLSession!
    private var ws: URLSessionWebSocketTask?
    private var inputWs: URLSessionWebSocketTask?
    private var pingTimer: Timer?
    private var nextId = 1
    private var callbacks: [String: ([String: Any]) -> Void] = [:]
    private(set) var host = ""
    private let defaults = UserDefaults.standard

    override init() {
        super.init()
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 60
        session = URLSession(configuration: cfg, delegate: self, delegateQueue: .main)
    }

    private var clientKey: String? {
        get { defaults.string(forKey: "key_\(host)") }
        set { defaults.set(newValue, forKey: "key_\(host)") }
    }

    // MARK: - Connection

    func connect(host newHost: String) {
        disconnect()
        host = newHost.trimmingCharacters(in: .whitespaces)
        guard !host.isEmpty, let url = URL(string: "ws://\(host):3000/") else {
            status = "Введіть IP телевізора"
            return
        }
        status = "Підключення до \(host)…"
        let task = session.webSocketTask(with: url)
        ws = task
        task.resume()
        receive(task)
    }

    func disconnect() {
        pingTimer?.invalidate(); pingTimer = nil
        inputWs?.cancel(with: .normalClosure, reason: nil); inputWs = nil
        ws?.cancel(with: .normalClosure, reason: nil); ws = nil
        callbacks.removeAll()
        connected = false
    }

    private func markDisconnected(_ msg: String) {
        pingTimer?.invalidate(); pingTimer = nil
        ws = nil
        inputWs = nil
        connected = false
        status = msg
    }

    private func receive(_ task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            DispatchQueue.main.async {
                guard let self = self, task === self.ws else { return }
                switch result {
                case .success(let message):
                    if case .string(let text) = message { self.handle(text) }
                    self.receive(task)
                case .failure(let error):
                    self.markDisconnected("Помилка: \(error.localizedDescription)")
                }
            }
        }
    }

    // MARK: - Register

    private func sendRegister() {
        let perms = Self.permissions
        var payload: [String: Any] = [
            "forcePairing": false,
            "pairingType": "PROMPT",
            "manifest": [
                "manifestVersion": 1,
                "appVersion": "1.0",
                "permissions": perms,
                "signed": [
                    "appId": "ua.hmara.lgremote",
                    "vendorId": "ua.hmara",
                    "created": "20261005",
                    "localizedAppNames": ["": "LG Remote"],
                    "localizedVendorNames": ["": "Hmara"],
                    "permissions": perms,
                    "serial": "lgremote-ios-0001"
                ] as [String: Any]
            ] as [String: Any]
        ]
        if let key = clientKey { payload["client-key"] = key }
        if clientKey == nil { status = "Підтвердіть запит на екрані телевізора…" }
        send(["type": "register", "id": "register_0", "payload": payload])
    }

    private func handle(_ text: String) {
        guard let data = text.data(using: .utf8),
              let msg = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return }
        let type = msg["type"] as? String ?? ""
        let id = msg["id"] as? String ?? ""
        let payload = msg["payload"] as? [String: Any] ?? [:]

        if type == "registered" {
            if let key = payload["client-key"] as? String, !key.isEmpty { clientKey = key }
            connected = true
            status = "Підключено: \(host)"
            openInputSocket()
            subscribeVolume()
            startPing()
        } else if type == "error" && id == "register_0" {
            if clientKey != nil {
                // Ключ застарів — перепаруємось
                clientKey = nil
                sendRegister()
            } else {
                status = "Відмовлено: \(msg["error"] as? String ?? "")"
            }
        } else if let cb = callbacks[id] {
            if id.hasPrefix("req_") { callbacks[id] = nil }
            cb(payload)
        }
    }

    private func startPing() {
        pingTimer?.invalidate()
        pingTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
            self?.ws?.sendPing { _ in }
        }
    }

    // MARK: - Requests

    private func send(_ obj: [String: Any]) {
        guard let ws = ws,
              let data = try? JSONSerialization.data(withJSONObject: obj),
              let s = String(data: data, encoding: .utf8) else { return }
        ws.send(.string(s)) { _ in }
    }

    func request(_ uri: String, _ payload: [String: Any]? = nil,
                 _ cb: (([String: Any]) -> Void)? = nil) {
        let id = "req_\(nextId)"; nextId += 1
        if let cb = cb { callbacks[id] = cb }
        var msg: [String: Any] = ["type": "request", "id": id, "uri": uri]
        if let payload = payload { msg["payload"] = payload }
        send(msg)
    }

    private func subscribe(_ uri: String, _ cb: @escaping ([String: Any]) -> Void) {
        let id = "sub_\(nextId)"; nextId += 1
        callbacks[id] = cb
        send(["type": "subscribe", "id": id, "uri": uri])
    }

    private func subscribeVolume() {
        subscribe("ssap://audio/getVolume") { [weak self] p in
            guard let self = self else { return }
            if let vs = p["volumeStatus"] as? [String: Any] {
                if let v = vs["volume"] as? Int { self.volume = v }
                self.muted = vs["muteStatus"] as? Bool ?? false
            } else {
                if let v = p["volume"] as? Int { self.volume = v }
                self.muted = p["muted"] as? Bool ?? false
            }
        }
    }

    // MARK: - Pointer / buttons

    private func openInputSocket() {
        request("ssap://com.webos.service.networkinput/getPointerInputSocket") { [weak self] p in
            guard let self = self,
                  let path = p["socketPath"] as? String,
                  let url = URL(string: path) else { return }
            let task = self.session.webSocketTask(with: url)
            self.inputWs = task
            task.resume()
            self.drainInput(task)
        }
    }

    private func drainInput(_ task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            DispatchQueue.main.async {
                guard let self = self, task === self.inputWs else { return }
                if case .success = result { self.drainInput(task) } else { self.inputWs = nil }
            }
        }
    }

    private func sendInput(_ s: String) {
        if let input = inputWs {
            input.send(.string(s)) { _ in }
        } else if connected {
            openInputSocket()
        }
    }

    /// UP DOWN LEFT RIGHT ENTER BACK HOME EXIT MENU INFO 0-9 RED GREEN YELLOW BLUE …
    func button(_ name: String) { sendInput("type:button\nname:\(name)\n\n") }
    func pointerMove(dx: Int, dy: Int) { sendInput("type:move\ndx:\(dx)\ndy:\(dy)\ndown:0\n\n") }
    func pointerClick() { sendInput("type:click\n\n") }
    func scroll(dy: Int) { sendInput("type:scroll\ndx:0\ndy:\(dy)\n\n") }

    // MARK: - Shortcuts

    func volumeUp() { request("ssap://audio/volumeUp") }
    func volumeDown() { request("ssap://audio/volumeDown") }
    func toggleMute() { request("ssap://audio/setMute", ["mute": !muted]) }
    func channelUp() { request("ssap://tv/channelUp") }
    func channelDown() { request("ssap://tv/channelDown") }
    func powerOff() { request("ssap://system/turnOff") }
    func play() { request("ssap://media.controls/play") }
    func pause() { request("ssap://media.controls/pause") }
    func stop() { request("ssap://media.controls/stop") }
    func rewind() { request("ssap://media.controls/rewind") }
    func fastForward() { request("ssap://media.controls/fastForward") }
    func launchApp(_ id: String) { request("ssap://system.launcher/launch", ["id": id]) }
    func setInput(_ id: String) { request("ssap://tv/switchInput", ["inputId": id]) }

    func sendText(_ text: String) {
        if !text.isEmpty {
            request("ssap://com.webos.service.ime/insertText", ["text": text, "replace": 0])
        }
        request("ssap://com.webos.service.ime/sendEnterKey")
    }

    func deleteChar() {
        request("ssap://com.webos.service.ime/deleteCharacters", ["count": 1])
    }

    func listInputs(_ cb: @escaping ([Item]) -> Void) {
        request("ssap://tv/getExternalInputList") { p in
            let arr = p["devices"] as? [[String: Any]] ?? []
            cb(arr.compactMap { d in
                guard let id = d["id"] as? String else { return nil }
                return Item(id: id, title: d["label"] as? String ?? id)
            })
        }
    }

    func listApps(_ cb: @escaping ([Item]) -> Void) {
        request("ssap://com.webos.applicationManager/listLaunchPoints") { p in
            let arr = p["launchPoints"] as? [[String: Any]] ?? []
            cb(arr.compactMap { a in
                guard let id = a["id"] as? String else { return nil }
                return Item(id: id, title: a["title"] as? String ?? id)
            }.sorted { $0.title.lowercased() < $1.title.lowercased() })
        }
    }

    static let permissions = [
        "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CLOSE",
        "TEST_OPEN", "TEST_PROTECTED",
        "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
        "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_MEDIA_PLAYBACK",
        "CONTROL_INPUT_TV", "CONTROL_POWER", "CONTROL_INPUT_TEXT",
        "CONTROL_MOUSE_AND_KEYBOARD",
        "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST",
        "READ_NETWORK_STATE", "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST",
        "WRITE_NOTIFICATION_TOAST", "READ_POWER_STATE", "READ_COUNTRY_INFO",
        "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_NOTIFICATIONS",
        "SEARCH", "WRITE_SETTINGS", "WRITE_NOTIFICATION_ALERT",
        "CHECK_BLUETOOTH_DEVICE", "STB_INTERNAL_CONNECTION",
        "ADD_LAUNCHER_CHANNEL", "SET_CHANNEL", "READ_SETTINGS",
        "READ_UPDATE_INFO", "UPDATE_FROM_REMOTE_APP", "READ_LGE_TV_INPUT_EVENTS",
        "READ_TV_CURRENT_TIME"
    ]
}

extension LgTvClient: URLSessionWebSocketDelegate {
    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didOpenWithProtocol protocol: String?) {
        if webSocketTask === ws { sendRegister() }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask,
                    didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        if webSocketTask === ws { markDisconnected("З'єднання закрито") }
    }
}
