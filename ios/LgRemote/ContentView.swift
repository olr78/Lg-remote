import SwiftUI
import UIKit

enum Haptics {
    static func tap() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
}

/// Кнопка пульта: дія при натисканні, опційно автоповтор при утриманні.
struct RBtn: View {
    let title: String
    var color = Color(white: 0.17)
    var height: CGFloat = 52
    var font: Font = .system(size: 17, weight: .medium)
    var repeats = false
    let action: () -> Void

    @State private var pressed = false
    @State private var timer: Timer?

    var body: some View {
        Text(title)
            .font(font)
            .foregroundColor(.white)
            .frame(maxWidth: .infinity, minHeight: height, maxHeight: height)
            .background(RoundedRectangle(cornerRadius: 10)
                .fill(pressed ? color.opacity(0.55) : color))
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    guard !pressed else { return }
                    pressed = true
                    Haptics.tap()
                    action()
                    if repeats {
                        timer = Timer.scheduledTimer(withTimeInterval: 0.45, repeats: false) { _ in
                            timer = Timer.scheduledTimer(withTimeInterval: 0.12, repeats: true) { _ in
                                action()
                            }
                        }
                    }
                }
                .onEnded { _ in
                    pressed = false
                    timer?.invalidate()
                    timer = nil
                })
    }
}

struct Touchpad: View {
    let onMove: (Int, Int) -> Void
    let onTap: () -> Void
    let onScroll: (Int) -> Void

    @State private var last = CGSize.zero
    @State private var travel: CGFloat = 0
    @State private var scrollLast: CGFloat = 0
    @State private var scrollAcc: CGFloat = 0

    var body: some View {
        HStack(spacing: 8) {
            RoundedRectangle(cornerRadius: 12)
                .fill(Color(white: 0.12))
                .overlay(Text("Тачпад: веди — курсор, тап — клік")
                    .font(.footnote).foregroundColor(.gray))
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { v in
                        let dx = v.translation.width - last.width
                        let dy = v.translation.height - last.height
                        last = v.translation
                        travel += abs(dx) + abs(dy)
                        let ix = Int((dx * 1.6).rounded()), iy = Int((dy * 1.6).rounded())
                        if ix != 0 || iy != 0 { onMove(ix, iy) }
                    }
                    .onEnded { _ in
                        if travel < 10 { Haptics.tap(); onTap() }
                        last = .zero
                        travel = 0
                    })

            RoundedRectangle(cornerRadius: 12)
                .fill(Color(white: 0.16))
                .frame(width: 44)
                .overlay(Text("⇅").foregroundColor(.gray))
                .gesture(DragGesture(minimumDistance: 0)
                    .onChanged { v in
                        let dy = v.translation.height - scrollLast
                        scrollLast = v.translation.height
                        scrollAcc += dy
                        if abs(scrollAcc) > 20 {
                            onScroll(scrollAcc > 0 ? -1 : 1)
                            scrollAcc = 0
                        }
                    }
                    .onEnded { _ in scrollLast = 0; scrollAcc = 0 })
        }
        .frame(height: 180)
    }
}

struct ContentView: View {
    @StateObject private var tv = LgTvClient()
    @AppStorage("ip") private var ip = ""
    @AppStorage("mac") private var mac = ""
    @Environment(\.scenePhase) private var scenePhase

    @State private var text = ""
    @State private var listTitle = ""
    @State private var listItems: [LgTvClient.Item] = []
    @State private var listAction: ((String) -> Void)?
    @State private var showList = false
    @State private var alertText: String?

    private let accent = Color(red: 0.78, green: 0.06, blue: 0.18)

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                connectionSection
                powerRow
                dpad
                volumeSection
                mediaRow
                colorRow
                Touchpad(onMove: { tv.pointerMove(dx: $0, dy: $1) },
                         onTap: { tv.pointerClick() },
                         onScroll: { tv.scroll(dy: $0) })
                textRow
                numpad
                TextField("MAC ТВ для увімкнення (AA:BB:CC:DD:EE:FF)", text: $mac)
                    .textFieldStyle(.roundedBorder)
                    .autocapitalization(.allCharacters)
                    .disableAutocorrection(true)
                    .padding(.top, 8)
            }
            .padding(12)
        }
        .background(Color(white: 0.07).ignoresSafeArea())
        .onAppear { if !ip.isEmpty && !tv.connected { tv.connect(host: ip) } }
        .onChange(of: scenePhase) { phase in
            if phase == .active && !tv.connected && !ip.isEmpty { tv.connect(host: ip) }
        }
        .sheet(isPresented: $showList) {
            NavigationView {
                List(listItems) { item in
                    Button(item.title) {
                        listAction?(item.id)
                        showList = false
                    }
                }
                .navigationTitle(listTitle)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Закрити") { showList = false }
                    }
                }
            }
        }
        .alert(alertText ?? "", isPresented: Binding(
            get: { alertText != nil },
            set: { if !$0 { alertText = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    // MARK: - Sections

    private var connectionSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                TextField("IP телевізора", text: $ip)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.numbersAndPunctuation)
                    .disableAutocorrection(true)
                    .onSubmit { tv.connect(host: ip) }
                Button("Підкл.") { hideKeyboard(); tv.connect(host: ip) }
                    .buttonStyle(.borderedProminent)
                    .tint(accent)
            }
            Text(tv.status)
                .font(.footnote)
                .foregroundColor(tv.connected ? .green : .gray)
        }
    }

    private var powerRow: some View {
        HStack(spacing: 8) {
            RBtn(title: "⏻ Увімк", color: Color(red: 0.12, green: 0.37, blue: 0.18)) { powerOn() }
            RBtn(title: "⏻ Вимк", color: Color(red: 0.55, green: 0.1, blue: 0.1)) { tv.powerOff() }
            RBtn(title: "Вхід") {
                tv.listInputs { items in present("Вхід", items) { tv.setInput($0) } }
            }
            RBtn(title: "Апки") {
                tv.listApps { items in present("Застосунки", items) { tv.launchApp($0) } }
            }
        }
    }

    private var dpad: some View {
        let big = Font.system(size: 24, weight: .semibold)
        let dColor = Color(white: 0.23)
        return VStack(spacing: 8) {
            HStack(spacing: 8) {
                RBtn(title: "↩ Назад", height: 72) { tv.button("BACK") }
                RBtn(title: "▲", color: dColor, height: 72, font: big, repeats: true) { tv.button("UP") }
                RBtn(title: "⌂ Home", height: 72) { tv.button("HOME") }
            }
            HStack(spacing: 8) {
                RBtn(title: "◀", color: dColor, height: 72, font: big, repeats: true) { tv.button("LEFT") }
                RBtn(title: "OK", color: accent, height: 72, font: big) { tv.button("ENTER") }
                RBtn(title: "▶", color: dColor, height: 72, font: big, repeats: true) { tv.button("RIGHT") }
            }
            HStack(spacing: 8) {
                RBtn(title: "⚙ Меню", height: 72) { tv.button("MENU") }
                RBtn(title: "▼", color: dColor, height: 72, font: big, repeats: true) { tv.button("DOWN") }
                RBtn(title: "Exit", height: 72) { tv.button("EXIT") }
            }
        }
        .padding(.top, 4)
    }

    private var volumeSection: some View {
        VStack(spacing: 6) {
            HStack(spacing: 8) {
                RBtn(title: "🔉 −", repeats: true) { tv.volumeDown() }
                RBtn(title: tv.muted ? "🔇 Увімк. звук" : "🔇") { tv.toggleMute() }
                RBtn(title: "🔊 +", repeats: true) { tv.volumeUp() }
            }
            Text(volumeLabel).font(.footnote).foregroundColor(.gray)
            HStack(spacing: 8) {
                RBtn(title: "CH −") { tv.channelDown() }
                RBtn(title: "Info") { tv.button("INFO") }
                RBtn(title: "CH +") { tv.channelUp() }
            }
        }
    }

    private var volumeLabel: String {
        guard let v = tv.volume else { return "Гучність: —" }
        return tv.muted ? "Гучність: \(v) (без звуку)" : "Гучність: \(v)"
    }

    private var mediaRow: some View {
        HStack(spacing: 8) {
            RBtn(title: "⏪") { tv.rewind() }
            RBtn(title: "▶") { tv.play() }
            RBtn(title: "⏸") { tv.pause() }
            RBtn(title: "⏹") { tv.stop() }
            RBtn(title: "⏩") { tv.fastForward() }
        }
    }

    private var colorRow: some View {
        HStack(spacing: 8) {
            RBtn(title: "", color: Color(red: 0.72, green: 0.11, blue: 0.11), height: 40) { tv.button("RED") }
            RBtn(title: "", color: Color(red: 0.11, green: 0.37, blue: 0.13), height: 40) { tv.button("GREEN") }
            RBtn(title: "", color: Color(red: 0.98, green: 0.66, blue: 0.15), height: 40) { tv.button("YELLOW") }
            RBtn(title: "", color: Color(red: 0.05, green: 0.28, blue: 0.63), height: 40) { tv.button("BLUE") }
        }
    }

    private var textRow: some View {
        HStack(spacing: 8) {
            TextField("Текст у поле на ТВ", text: $text)
                .textFieldStyle(.roundedBorder)
                .onSubmit(sendText)
            Button("⏎", action: sendText).buttonStyle(.bordered)
            Button("⌫") { tv.deleteChar() }.buttonStyle(.bordered)
        }
    }

    private var numpad: some View {
        let keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "—", "0", "LIST"]
        return LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3),
                         spacing: 8) {
            ForEach(keys, id: \.self) { k in
                RBtn(title: k) { tv.button(k == "—" ? "DASH" : k) }
            }
        }
    }

    // MARK: - Actions

    private func sendText() {
        tv.sendText(text)
        text = ""
    }

    private func powerOn() {
        let m = mac.trimmingCharacters(in: .whitespaces)
        guard !m.isEmpty else {
            alertText = "Вкажіть MAC ТВ унизу (Налаштування ТВ → Загальні → Про цей ТВ)"
            return
        }
        do {
            try WakeOnLan.send(mac: m, tvIp: ip)
            DispatchQueue.main.asyncAfter(deadline: .now() + 8) { tv.connect(host: ip) }
        } catch {
            alertText = error.localizedDescription
        }
    }

    private func present(_ title: String, _ items: [LgTvClient.Item],
                         _ action: @escaping (String) -> Void) {
        guard !items.isEmpty else { alertText = "Список порожній"; return }
        listTitle = title
        listItems = items
        listAction = action
        showList = true
    }

    private func hideKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder),
                                        to: nil, from: nil, for: nil)
    }
}
