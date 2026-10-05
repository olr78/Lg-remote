package ua.hmara.lgremote

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Клієнт протоколу LG webOS SSAP (Second Screen App Protocol).
 * UF950V = webOS 3.0 → ws://<ip>:3000, без TLS.
 *
 * Схема:
 *  1. Відкриваємо WebSocket, шлемо "register" з маніфестом прав.
 *  2. Перший раз ТВ показує запит "Дозволити підключення?" → після згоди
 *     повертає client-key, який зберігаємо і далі шлемо без підтвердження.
 *  3. Команди — "request" на ssap://... URI.
 *  4. Кнопки пульта (стрілки, OK, Back, Home, цифри) йдуть окремим
 *     сокетом, адресу якого видає networkinput/getPointerInputSocket.
 */
class LgTvClient(
    private val host: String,
    private var clientKey: String?,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(text: String)
        fun onPaired(clientKey: String)
        fun onConnected()
        fun onDisconnected()
        fun onVolume(volume: Int, muted: Boolean) {}
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var inputWs: WebSocket? = null
    private val nextId = AtomicInteger(1)
    private val callbacks = ConcurrentHashMap<String, (JSONObject) -> Unit>()

    @Volatile var isConnected = false
        private set

    fun connect() {
        disconnect()
        listener.onStatus("Підключення до $host…")
        val req = Request.Builder().url("ws://$host:3000/").build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sendRegister()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS failure", t)
                markDisconnected("Помилка: ${t.message ?: t.javaClass.simpleName}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                markDisconnected("З'єднання закрито")
            }
        })
    }

    fun disconnect() {
        inputWs?.close(1000, null); inputWs = null
        ws?.close(1000, null); ws = null
        callbacks.clear()
        isConnected = false
    }

    private fun markDisconnected(msg: String) {
        val was = isConnected
        isConnected = false
        inputWs = null
        listener.onStatus(msg)
        if (was) listener.onDisconnected()
    }

    // ---------------------------------------------------------------- register

    private fun sendRegister() {
        val payload = JSONObject().apply {
            put("forcePairing", false)
            put("pairingType", "PROMPT")
            clientKey?.let { put("client-key", it) }
            put("manifest", JSONObject().apply {
                put("manifestVersion", 1)
                put("appVersion", "1.0")
                put("permissions", JSONArray(PERMISSIONS))
                put("signed", JSONObject().apply {
                    put("appId", "ua.hmara.lgremote")
                    put("vendorId", "ua.hmara")
                    put("created", "20261005")
                    put("localizedAppNames", JSONObject().put("", "LG Remote"))
                    put("localizedVendorNames", JSONObject().put("", "Hmara"))
                    put("permissions", JSONArray(PERMISSIONS))
                    put("serial", "lgremote-0001")
                })
            })
        }
        val msg = JSONObject()
            .put("type", "register")
            .put("id", "register_0")
            .put("payload", payload)
        if (clientKey == null) listener.onStatus("Підтвердіть запит на екрані телевізора…")
        ws?.send(msg.toString())
    }

    private fun handleMessage(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        val type = msg.optString("type")
        val id = msg.optString("id")
        val payload = msg.optJSONObject("payload") ?: JSONObject()

        when {
            type == "registered" -> {
                val key = payload.optString("client-key")
                if (key.isNotEmpty() && key != clientKey) {
                    clientKey = key
                    listener.onPaired(key)
                }
                isConnected = true
                listener.onStatus("Підключено: $host")
                listener.onConnected()
                openInputSocket()
                subscribeVolume()
            }
            type == "error" && id == "register_0" -> {
                // Ключ застарів (наприклад, після скидання ТВ) — перепаруємось
                if (clientKey != null) {
                    clientKey = null
                    listener.onPaired("")
                    sendRegister()
                } else {
                    listener.onStatus("Відмовлено: ${msg.optString("error")}")
                }
            }
            else -> callbacks[id]?.invoke(payload)
        }
    }

    // ---------------------------------------------------------------- requests

    fun request(uri: String, payload: JSONObject? = null, cb: ((JSONObject) -> Unit)? = null) {
        val id = "req_${nextId.getAndIncrement()}"
        if (cb != null) callbacks[id] = cb
        val msg = JSONObject().put("type", "request").put("id", id).put("uri", uri)
        if (payload != null) msg.put("payload", payload)
        ws?.send(msg.toString())
    }

    private fun subscribe(uri: String, cb: (JSONObject) -> Unit) {
        val id = "sub_${nextId.getAndIncrement()}"
        callbacks[id] = cb
        ws?.send(JSONObject().put("type", "subscribe").put("id", id).put("uri", uri).toString())
    }

    private fun subscribeVolume() {
        subscribe("ssap://audio/getVolume") { p ->
            // webOS 3: {volume, muted}; новіші: {volumeStatus:{volume, muteStatus}}
            val vs = p.optJSONObject("volumeStatus")
            val vol = vs?.optInt("volume", -1) ?: p.optInt("volume", -1)
            val muted = vs?.optBoolean("muteStatus") ?: p.optBoolean("muted")
            if (vol >= 0) listener.onVolume(vol, muted)
        }
    }

    // ------------------------------------------------------- pointer / buttons

    private fun openInputSocket() {
        request("ssap://com.webos.service.networkinput/getPointerInputSocket") { p ->
            val path = p.optString("socketPath")
            if (path.isEmpty()) return@request
            inputWs = http.newWebSocket(Request.Builder().url(path).build(),
                object : WebSocketListener() {
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        inputWs = null
                    }
                })
        }
    }

    /** Кнопка пульта: UP DOWN LEFT RIGHT ENTER BACK HOME EXIT MENU INFO 0-9 RED GREEN … */
    fun button(name: String) {
        val s = inputWs
        if (s != null) {
            s.send("type:button\nname:$name\n\n")
        } else if (isConnected) {
            openInputSocket()
        }
    }

    fun pointerMove(dx: Int, dy: Int) =
        inputWs?.send("type:move\ndx:$dx\ndy:$dy\ndown:0\n\n")

    fun pointerClick() = inputWs?.send("type:click\n\n")

    fun scroll(dy: Int) = inputWs?.send("type:scroll\ndx:0\ndy:$dy\n\n")

    // ------------------------------------------------------------ shortcuts

    fun volumeUp() = request("ssap://audio/volumeUp")
    fun volumeDown() = request("ssap://audio/volumeDown")
    fun setMute(mute: Boolean) = request("ssap://audio/setMute", JSONObject().put("mute", mute))
    fun channelUp() = request("ssap://tv/channelUp")
    fun channelDown() = request("ssap://tv/channelDown")
    fun powerOff() = request("ssap://system/turnOff")
    fun play() = request("ssap://media.controls/play")
    fun pause() = request("ssap://media.controls/pause")
    fun stop() = request("ssap://media.controls/stop")
    fun rewind() = request("ssap://media.controls/rewind")
    fun fastForward() = request("ssap://media.controls/fastForward")

    fun toast(text: String) =
        request("ssap://system.notifications/createToast", JSONObject().put("message", text))

    fun launchApp(id: String) =
        request("ssap://system.launcher/launch", JSONObject().put("id", id))

    fun openUrl(url: String) =
        request("ssap://system.launcher/open", JSONObject().put("target", url))

    fun setInput(inputId: String) =
        request("ssap://tv/switchInput", JSONObject().put("inputId", inputId))

    fun sendText(text: String) {
        request("ssap://com.webos.service.ime/insertText",
            JSONObject().put("text", text).put("replace", 0))
    }

    fun sendEnterKey() = request("ssap://com.webos.service.ime/sendEnterKey")
    fun deleteChars(n: Int) =
        request("ssap://com.webos.service.ime/deleteCharacters", JSONObject().put("count", n))

    fun listInputs(cb: (List<Pair<String, String>>) -> Unit) {
        request("ssap://tv/getExternalInputList") { p ->
            val arr = p.optJSONArray("devices") ?: JSONArray()
            cb((0 until arr.length()).map {
                val d = arr.getJSONObject(it)
                d.optString("id") to d.optString("label", d.optString("id"))
            })
        }
    }

    fun listApps(cb: (List<Pair<String, String>>) -> Unit) {
        request("ssap://com.webos.applicationManager/listLaunchPoints") { p ->
            val arr = p.optJSONArray("launchPoints") ?: JSONArray()
            cb((0 until arr.length()).map {
                val a = arr.getJSONObject(it)
                a.optString("id") to a.optString("title")
            }.sortedBy { it.second.lowercase() })
        }
    }

    companion object {
        private const val TAG = "LgTvClient"

        private val PERMISSIONS = listOf(
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
        )
    }
}
