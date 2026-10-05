package ua.hmara.lgremote

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), LgTvClient.Listener {

    private val prefs by lazy { getSharedPreferences("lgremote", Context.MODE_PRIVATE) }
    private var tv: LgTvClient? = null

    private lateinit var ipEdit: EditText
    private lateinit var macEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var volumeText: TextView
    private var muted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipEdit = findViewById(R.id.ipEdit)
        macEdit = findViewById(R.id.macEdit)
        statusText = findViewById(R.id.statusText)
        volumeText = findViewById(R.id.volumeText)

        ipEdit.setText(prefs.getString("ip", ""))
        macEdit.setText(prefs.getString("mac", ""))

        click(R.id.btnConnect) { connect() }
        click(R.id.btnScan) { scan() }

        click(R.id.btnPowerOn) { powerOn() }
        click(R.id.btnPowerOff) { tv?.powerOff() }
        click(R.id.btnInput) { showInputs() }
        click(R.id.btnApps) { showApps() }

        // Кнопки через pointer-сокет
        mapOf(
            R.id.btnUp to "UP", R.id.btnDown to "DOWN",
            R.id.btnLeft to "LEFT", R.id.btnRight to "RIGHT",
            R.id.btnOk to "ENTER", R.id.btnBack to "BACK",
            R.id.btnHome to "HOME", R.id.btnExit to "EXIT",
            R.id.btnMenu to "MENU", R.id.btnInfo to "INFO",
            R.id.btnRed to "RED", R.id.btnGreen to "GREEN",
            R.id.btnYellow to "YELLOW", R.id.btnBlue to "BLUE"
        ).forEach { (id, key) -> repeatable(id) { tv?.button(key) } }

        repeatable(R.id.btnVolUp) { tv?.volumeUp() }
        repeatable(R.id.btnVolDown) { tv?.volumeDown() }
        click(R.id.btnMute) { tv?.setMute(!muted) }
        click(R.id.btnChUp) { tv?.channelUp() }
        click(R.id.btnChDown) { tv?.channelDown() }

        click(R.id.btnPlay) { tv?.play() }
        click(R.id.btnPause) { tv?.pause() }
        click(R.id.btnStop) { tv?.stop() }
        click(R.id.btnRew) { tv?.rewind() }
        click(R.id.btnFf) { tv?.fastForward() }

        val textEdit = findViewById<EditText>(R.id.textEdit)
        click(R.id.btnSendText) {
            val t = textEdit.text.toString()
            if (t.isNotEmpty()) tv?.sendText(t)
            tv?.sendEnterKey()
            textEdit.setText("")
        }
        click(R.id.btnBksp) { tv?.deleteChars(1) }

        buildNumpad()
        setupTouchpad()

        if (ipEdit.text.isNotBlank()) connect()
    }

    override fun onDestroy() {
        tv?.disconnect()
        super.onDestroy()
    }

    // Апаратні кнопки гучності телефону керують гучністю ТВ
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val c = tv
        if (c != null && c.isConnected) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> { c.volumeUp(); return true }
                KeyEvent.KEYCODE_VOLUME_DOWN -> { c.volumeDown(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------- actions

    private fun connect() {
        val ip = ipEdit.text.toString().trim()
        if (ip.isEmpty()) { toast("Введіть IP або натисніть 🔍"); return }
        if (ip != prefs.getString("ip", "")) {
            prefs.edit().putString("ip", ip).remove("key_$ip").apply()
        }
        prefs.edit().putString("mac", macEdit.text.toString().trim()).apply()
        tv?.disconnect()
        tv = LgTvClient(ip, prefs.getString("key_$ip", null), this).also { it.connect() }
    }

    private fun scan() {
        statusText.text = "Пошук телевізора в мережі…"
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching { NetUtils.discover(this@MainActivity) }.getOrDefault(emptyList())
            }
            when (list.size) {
                0 -> statusText.text = "ТВ не знайдено. Перевірте, що він увімкнений і в тій самій мережі."
                1 -> { ipEdit.setText(list[0]); connect() }
                else -> AlertDialog.Builder(this@MainActivity)
                    .setTitle("Знайдено")
                    .setItems(list.toTypedArray()) { _, i -> ipEdit.setText(list[i]); connect() }
                    .show()
            }
        }
    }

    private fun powerOn() {
        val mac = macEdit.text.toString().trim()
        if (mac.isEmpty()) {
            toast("Вкажіть MAC ТВ унизу (Налаштування → Загальні → Про цей ТВ)")
            return
        }
        prefs.edit().putString("mac", mac).apply()
        lifecycleScope.launch(Dispatchers.IO) {
            val r = runCatching { NetUtils.sendWol(mac) }
            withContext(Dispatchers.Main) {
                if (r.isSuccess) {
                    toast("WOL відправлено")
                    // ТВ піднімає webOS ~5-10 с — пробуємо перепідключитись
                    statusText.postDelayed({ connect() }, 8000)
                } else toast("Помилка WOL: ${r.exceptionOrNull()?.message}")
            }
        }
    }

    private fun showInputs() {
        val c = tv ?: return
        c.listInputs { list ->
            runOnUiThread {
                if (list.isEmpty()) { toast("Немає входів"); return@runOnUiThread }
                AlertDialog.Builder(this).setTitle("Вхід")
                    .setItems(list.map { it.second }.toTypedArray()) { _, i -> c.setInput(list[i].first) }
                    .show()
            }
        }
    }

    private fun showApps() {
        val c = tv ?: return
        c.listApps { list ->
            runOnUiThread {
                AlertDialog.Builder(this).setTitle("Застосунки")
                    .setItems(list.map { it.second }.toTypedArray()) { _, i -> c.launchApp(list[i].first) }
                    .show()
            }
        }
    }

    // ------------------------------------------------------------- UI helpers

    private fun click(id: Int, action: () -> Unit) {
        findViewById<View>(id).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            action()
        }
    }

    /** Кнопка з автоповтором при утриманні (стрілки, гучність). */
    @SuppressLint("ClickableViewAccessibility")
    private fun repeatable(id: Int, action: () -> Unit) {
        val v = findViewById<View>(id)
        val repeater = object : Runnable {
            override fun run() { action(); v.postDelayed(this, 120) }
        }
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    action()
                    view.postDelayed(repeater, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    view.removeCallbacks(repeater)
                }
            }
            true
        }
    }

    private fun buildNumpad() {
        val grid = findViewById<GridLayout>(R.id.numGrid)
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "-", "0", "LIST")
        keys.forEach { k ->
            val b = Button(this, null, 0, R.style.Btn).apply {
                text = if (k == "-") "—" else k
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply {
                    width = 0
                    height = (52 * resources.displayMetrics.density).roundToInt()
                    setMargins(6, 6, 6, 6)
                }
                setBackgroundColor(0xFF2C2C2C.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    tv?.button(if (k == "-") "DASH" else k)
                }
            }
            grid.addView(b)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchpad() {
        val pad = findViewById<View>(R.id.touchpad)
        var lastX = 0f; var lastY = 0f
        var downTime = 0L; var moved = 0f
        var accScroll = 0f
        val sens = 1.6f

        pad.setOnTouchListener { _, e ->
            val c = tv ?: return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; downTime = e.eventTime; moved = 0f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lastX; val dy = e.y - lastY
                    moved += abs(dx) + abs(dy)
                    if (e.pointerCount >= 2) {
                        accScroll += dy
                        if (abs(accScroll) > 20) {
                            c.scroll(if (accScroll > 0) -1 else 1)
                            accScroll = 0f
                        }
                    } else {
                        c.pointerMove((dx * sens).roundToInt(), (dy * sens).roundToInt())
                    }
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_UP -> {
                    if (moved < 15 && e.eventTime - downTime < 250) {
                        pad.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        c.pointerClick()
                    }
                }
            }
            true
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------- Listener

    override fun onStatus(text: String) = runOnUiThread { statusText.text = text }

    override fun onPaired(clientKey: String) {
        val ip = ipEdit.text.toString().trim()
        prefs.edit().apply {
            if (clientKey.isEmpty()) remove("key_$ip") else putString("key_$ip", clientKey)
        }.apply()
    }

    override fun onConnected() = runOnUiThread {
        statusText.setTextColor(0xFF66BB6A.toInt())
    }

    override fun onDisconnected() = runOnUiThread {
        statusText.setTextColor(0xFFAAAAAA.toInt())
    }

    override fun onVolume(volume: Int, muted: Boolean) = runOnUiThread {
        this.muted = muted
        volumeText.text = if (muted) "Гучність: $volume (без звуку)" else "Гучність: $volume"
    }
}
