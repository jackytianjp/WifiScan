package com.takano.wifiscan

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import java.net.Inet4Address

object IpInfo {
    /**
     * 走 ConnectivityManager 拿当前网络的 IPv4。
     * 不用 NetworkInterface 遍历：Android 10+ 上普通 App 读不到 wlan0 的地址（实测返回空），
     * getLinkProperties 需要 ACCESS_NETWORK_STATE（已声明）。
     */
    fun localIpv4(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val lp = cm.getLinkProperties(cm.activeNetwork)
            lp?.linkAddresses?.firstOrNull {
                it.address is Inet4Address && !it.address.isLoopbackAddress
            }?.let { addr: LinkAddress -> addr.address.hostAddress ?: "-" } ?: "-"
        } catch (t: Throwable) {
            "-"
        }
    }
}
