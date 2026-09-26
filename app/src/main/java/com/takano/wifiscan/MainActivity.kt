package com.takano.wifiscan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.zxing.client.result.ResultParser
import com.google.zxing.client.result.WifiParsedResult

/**
 * 扫码连 Wi-Fi（眼镜端）
 *
 * 用法：手机打开「设置 → WLAN → 分享」显示二维码，眼镜对着扫。
 * 触控板：单击 = 重新扫描 / 再提交一次；双击或返回键 = 退出。
 */
class MainActivity : Activity(), LifecycleOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private lateinit var hud: HudView
    private lateinit var preview: androidx.camera.view.PreviewView
    private lateinit var joiner: WifiJoiner
    private var scanner: QrScanner? = null

    private var message = "把手机上的 Wi-Fi 二维码对准镜头"
    private var highlight = false
    private var lastSsid = ""
    private var lastPassword = ""
    private var lastAttemptAt = 0L
    private var lastKeyAt = 0L
    private var tone: android.media.ToneGenerator? = null

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            hud.status = statusText()
            hud.invalidate()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry.currentState = Lifecycle.State.CREATED
        hud = HudView(this)
        hud.previewMode = true
        // 相机预览垫在 HUD 下面：中间那块露出实时画面，方便对准二维码
        preview = androidx.camera.view.PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            scaleType = androidx.camera.view.PreviewView.ScaleType.FILL_CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(preview)
            addView(
                hud,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        setContentView(root)
        hud.title = getString(R.string.app_name)
        hud.hint = getString(R.string.hud_hint)
        hud.message = message
        joiner = WifiJoiner(this)

        askPermissions()
        // 调试用：adb 直接喂一张码，跳过相机
        //   am start -n com.takano.wifiscan/.MainActivity --es fakeqr 'WIFI:T:WPA;S:TestSSID;P:12345678;;'
        intent?.getStringExtra("fakeqr")?.let { payload ->
            handler.postDelayed({
                setMessage("(调试) 用 adb 喂进来的码", false)
                handleScan(com.google.zxing.Result(payload, null, null, com.google.zxing.BarcodeFormat.QR_CODE))
            }, 1200)
        }
        handler.post {
            val n = joiner.pruneOldSuggestions()
            if (n > 0) android.util.Log.i("WifiScan", "清掉 $n 条旧建议")
        }
        startScan()
        handler.post(ticker)
    }

    private fun askPermissions() {
        val want = mutableListOf<String>()
        for (p in listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) want += p
        }
        if (want.isNotEmpty()) {
            runCatching { ActivityCompat.requestPermissions(this, want.toTypedArray(), 1) }
        }
    }

    private fun statusText(): String {
        val on = joiner.isWifiEnabled()
        val ssid = if (on) joiner.currentSsid() else "-"
        val ip = if (on) joiner.ipAddress() else "-"
        return "Wi-Fi ${if (on) "开" else "关"}   网络 $ssid\n地址 $ip"
    }

    private fun startScan() {
        scanner?.stop()
        val s = QrScanner(onResult = { r -> handleScan(r) }, onLog = { setMessage(it, false) })
        scanner = s
        s.start(this, this, preview)
    }

    private fun handleScan(result: com.google.zxing.Result) {
        val parsed = runCatching { ResultParser.parseResult(result) }.getOrNull()
        if (parsed !is WifiParsedResult) {
            beepFail()
            setMessage("不是 Wi-Fi 二维码\n${result.text.take(30)}", false)
            return
        }
        val ssid = parsed.ssid ?: ""
        val pass = parsed.password ?: ""
        // 加密方式在 getNetworkEncryption()（getType() 是 ParsedResultType 枚举）：'WPA' / 'WEP' / 'nopass'
        val enc: String = parsed.networkEncryption ?: ""
        val secure: Boolean = enc != "nopass"
        val now = System.currentTimeMillis()
        if (ssid == lastSsid && now - lastAttemptAt < 15000) return   // 同一张码别连点
        lastSsid = ssid
        lastPassword = pass
        lastAttemptAt = now

        setMessage("扫到 $ssid\n正在交给系统…", true)
        beepOk()   // 读到二维码了，先给一声
        android.util.Log.i("WifiScan", "扫码命中: ssid=$ssid secure=$secure pass.len=${pass.length}")
        // 1) 先把网络建议交出去（系统自己会连，且会记住）
        val res = joiner.suggest(ssid, pass, secure)
        android.util.Log.i("WifiScan", "建议提交: ok=${res.ok} msg=${res.message} wifiEnabled=${joiner.isWifiEnabled()}")
        // 2) Wi-Fi 若是关着的，尽力打开；打不开就弹系统面板
        if (!joiner.isWifiEnabled()) {
            val ok = joiner.tryEnableWifi()
            android.util.Log.i("WifiScan", "尝试开 Wi-Fi: 成功=$ok")
            if (!ok) {
                setMessage("${res.message}\nWi-Fi 是关的，App 打不开——点开系统面板手动开一下", true)
                openWifiPanel()
                return
            }
        }
        setMessage(res.message, res.ok)
        if (!res.ok) {
            beepFail()
            return
        }
        // 系统只会「需要用」建议网络时才切；当前连着的网络还好好的，它不会主动换。
        // 想立刻切过去只能用户点一下，所以这里直接把系统 Wi-Fi 列表弹给他点。
        val cur = joiner.currentSsid()
        if (cur.isNotEmpty() && cur != ssid && joiner.isWifiEnabled()) {
            setMessage("已记住 $ssid\n在列表里点一下它就连过去", true)
            handler.postDelayed({
                if (!isFinishing) openWifiPanel()
            }, 700)
        }
    }

    private fun setMessage(text: String, hl: Boolean) {
        message = text
        highlight = hl
        runOnUiThread { hud.message = text; hud.messageHighlight = hl; hud.invalidate() }
    }

    /**
     * 提示音。眼镜上走 STREAM_MUSIC（提示音流在免打扰/静音时会没声），成功=滴一声，失败=两声低音。
     * ToneGenerator 起来要几十毫秒，所以第一次用时建好后留着复用，退出时释放。
     */
    private fun beep(kind: Int = android.media.ToneGenerator.TONE_PROP_BEEP, ms: Int = 150) {
        val t = tone ?: runCatching {
            android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 90)
        }.getOrNull()?.also { tone = it } ?: return
        runCatching { t.startTone(kind, ms) }
    }

    private fun beepOk() = beep(android.media.ToneGenerator.TONE_PROP_BEEP, 140)

    private fun beepFail() = beep(android.media.ToneGenerator.TONE_PROP_NACK, 220)

    /** 系统 Wi-Fi 入口（API 29+ 普通 App 唯一能用的「开 Wi-Fi」入口）。
     *  实测这台眼镜上 WIFI_SETTINGS 能打开、panel 那条 action 打不开，所以顺序如下。 */
    private fun openWifiPanel() {
        val intents = listOf(
            Intent(Settings.ACTION_WIFI_SETTINGS),
            Intent("android.settings.panel.action.INTERNET_CONNECTIVITY"),
        )
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { startActivity(i); true }.getOrDefault(false)
            if (ok) return
        }
        setMessage("这台机器上没有系统 Wi-Fi 面板", false)
    }

    /**
     * 触控板按键（跟 Rstream 同款，实测这台眼镜上）：
     *   单指轻点 = KEYCODE_ENTER，双击 = KEYCODE_BACK，长按 = KEYCODE_PROG_BLUE。
     * 所以「双击退出」必须接 BACK，而不是自己数两次 ENTER（之前就是不退出的原因）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                    onTap()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_PROG_BLUE -> {
                    exitApp()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 单击：扫到过网络且它不是当前网络 → 打开系统 Wi-Fi 列表去点一下；否则重扫 / 重提交 */
    private fun onTap() {
        val cur = joiner.currentSsid()
        if (lastSsid.isNotEmpty() && lastSsid != cur && joiner.isWifiEnabled()) {
            setMessage("在列表里点一下 $lastSsid", true)
            openWifiPanel()
            return
        }
        if (lastSsid.isNotEmpty()) {
            lastAttemptAt = 0
            setMessage("再提交一次 $lastSsid", true)
            val ok = joiner.isWifiEnabled() || joiner.tryEnableWifi()
            if (ok) joiner.suggest(lastSsid, lastPassword, true)
        } else {
            setMessage("重新扫描…", false)
            startScan()
        }
    }

    private fun exitApp() {
        finish()
    }

    override fun onResume() {
        super.onResume()
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        handler.removeCallbacks(ticker)
        scanner?.stop()
        runCatching { tone?.release() }
        tone = null
        super.onDestroy()
    }
}
