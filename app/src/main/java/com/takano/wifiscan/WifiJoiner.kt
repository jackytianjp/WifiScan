package com.takano.wifiscan

import android.content.Context
import android.net.wifi.WifiNetworkSuggestion
import android.net.wifi.WifiManager
import android.os.Build

/**
 * 把「扫到的 Wi-Fi 凭据」交给系统。
 *
 * 为什么是 WifiNetworkSuggestion：Android 10(API29) 起，第三方 App 已经不能
 * 直接 addNetwork/enableNetwork 了（WifiManager.setWifiEnabled 对普通 App 也是空操作）。
 * 官方留给普通 App 的唯一通道就是「网络建议」：系统自己按建议去连，而且会一直记住
 * （重启后依然有效）。
 */
class WifiJoiner(private val ctx: Context) {

    private val wm: WifiManager =
        ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    data class Result(val ok: Boolean, val message: String)

    /** 提交一条网络建议。secure=false 表示开放网络。 */
    fun suggest(ssid: String, password: String, secure: Boolean): Result {
        if (ssid.isBlank()) return Result(false, "二维码里没有 SSID")
        if (!secure || password.isEmpty()) {
            // 开放网络：不给任何凭据（API 36 里没有 setIsOpenNetwork）
            val status = submit { WifiNetworkSuggestion.Builder().setSsid(ssid) }
            return explain(status, ssid, false)
        }
        // 一次只能给一种加密凭据（系统会直接拒绝混填），所以先 WPA2，不行再 WPA3
        val s2 = submit {
            WifiNetworkSuggestion.Builder().setSsid(ssid).setWpa2Passphrase(password)
        }
        if (s2 == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            return explain(s2, ssid, true)
        }
        val s3 = submit {
            WifiNetworkSuggestion.Builder().setSsid(ssid).setWpa3Passphrase(password)
        }
        return if (s3 == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Result(true, "已交给系统（WPA3），正在连 $ssid")
        } else {
            explain(s2, ssid, true)
        }
    }

    private fun submit(build: () -> WifiNetworkSuggestion.Builder): Int = try {
        val b = build()
        runCatching { b.setIsUserInteractionRequired(false) }
        wm.addNetworkSuggestions(listOf(b.build()))
    } catch (t: Throwable) {
        android.util.Log.i("WifiScan", "addNetworkSuggestions 抛异常: ${t.message}")
        -1
    }

    private fun explain(status: Int, ssid: String, secure: Boolean): Result = when (status) {
        WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ->
            Result(true, if (secure) "已交给系统，正在连 $ssid" else "已交给系统，正在连开放网络 $ssid")
        WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE ->
            Result(true, "这个网络之前已建议过，系统会自己连 $ssid")
        WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP ->
            Result(false, "建议数量超上限，先去系统设置里删几条")
        WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED ->
            Result(false, "系统不允许本 App 添加建议（被 ROM 限制）")
        else -> Result(false, "提交失败，code=$status")
    }

    fun isWifiEnabled(): Boolean = runCatching { wm.isWifiEnabled }.getOrDefault(false)

    /**
     * 尝试打开 Wi-Fi。Android 10+ 普通 App 这里是空操作（返回 false），
     * 所以调用方要按返回值决定是否退到「系统面板」那条路。
     */
    @Suppress("DEPRECATION")
    fun tryEnableWifi(): Boolean {
        val before = isWifiEnabled()
        if (before) return true
        runCatching { wm.isWifiEnabled = true }
        // 给它一点时间；这里只做一次快速复核
        var waited = 0
        while (waited < 2500) {
            if (isWifiEnabled()) return true
            Thread.sleep(250)
            waited += 250
        }
        return isWifiEnabled()
    }

    /** 当前已连上的 SSID（没有定位权限时可能是 <unknown ssid>） */
    @Suppress("DEPRECATION")
    fun currentSsid(): String {
        val s = runCatching { wm.connectionInfo?.ssid }.getOrNull() ?: return "-"
        return if (s == WifiManager.UNKNOWN_SSID || s.isBlank()) "-" else s.trim('"')
    }

    fun ipAddress(): String = IpInfo.localIpv4(ctx)

    /**
     * 清掉本 App 以前留下的旧建议（测试码之类），只保留「当前已连上」的那条，
     * 免得建议列表越攒越多，也免得删掉正在用的网络导致断线。
     * 返回清掉的条数（-1 = 调用失败）。
     */
    fun pruneOldSuggestions(): Int = try {
        val own = wm.networkSuggestions ?: emptyList()
        val keep = currentSsid()
        val drop = own.filter { s -> s.ssid.let { it != keep } }
        if (drop.isEmpty()) 0 else wm.removeNetworkSuggestions(drop)
    } catch (t: Throwable) {
        android.util.Log.i("WifiScan", "清理旧建议失败: ${t.message}")
        -1
    }

    fun androidVersionNote(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "建议模式" else "旧接口"
}
