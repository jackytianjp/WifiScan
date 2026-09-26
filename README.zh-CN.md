# WifiScan — 用智能眼镜扫码连 Wi-Fi

[English](README.md) · **中文**

一个给 Rokid 眼镜（以及任何有摄像头的 Android 12+ 设备）用的独立小 App：
把眼镜对着手机上「分享 Wi-Fi」的二维码，凭据就交给 Android 系统，由系统去连并**长期记住
（重启后依然在）**。不用在一行 HUD 上敲密码、不用配套 App、不需要 root、不依赖谷歌服务。

<img src="docs/icons/wifiscan-512.png" width="120" alt="WifiScan 图标">

## 为什么做这个

1. **在眼镜上敲 Wi-Fi 密码太难受**。二维码才是最自然的输入方式——手机里本来就有
   （设置 → WLAN → 分享）。
2. **Rokid 的系统会自己把 Wi-Fi 关掉**。摘下眼镜时固件里的 `SpriteWifiService` 会关闭 Wi-Fi，
   直播 / 中继连接每次都会因此断掉。这个 App 就是最快回到网络里的办法。
3. **希望它被记住**。把凭据交给系统一次，之后就会自动重连，不用再打开任何东西。

## 它做什么

- 实时相机画面 + 取景框；顶部状态带（Wi-Fi 状态 / 当前网络 / 地址），底部消息带。
- 解析标准 `WIFI:T:WPA;S:<ssid>;P:<password>;;` 格式的二维码（zxing，纯 Java，无 GMS 依赖）。
- 用 `WifiNetworkSuggestion`（API 29+）把凭据交给系统：这是免 root 的唯一正路，网络由**系统**
  持有、自动加入、并持久保存。
- 读到码给一声提示（成功一声、失败两声低音）。
- 扫到的网络不是当前连接的那个时，自动打开系统 Wi-Fi 列表，点一下就能切（原因见下）。

## 平台限制（都是在设备上实测的，不是猜的）

| 现象 | 真实情况 |
|---|---|
| App 开不了 Wi-Fi | Android 10+ 起禁止普通 App 调 `setWifiEnabled`，调用直接返回 `false`。App 改为打开 `Settings.ACTION_WIFI_SETTINGS`——这台 ROM 上它能打开。 |
| 扫到了却不切过去 | Android 的网络选择给「当前已连 / 已保存」的网络加了很高的分值。只要当前网络可用，系统不会为一个「建议网络」断掉它。建议网络真正生效的时机是你**没有可用网络**时（刚开机、Wi-Fi 刚被重新打开、换地方）。 |
| 强制切换 | 第三方 App 已无接口可用（`addNetwork`/`enableNetwork` 早已关闭，`allowAutoJoin` 只有网络属主能调）。在系统 Wi-Fi 列表里点一下可行，之后该网络会被记为「用户已认可」。 |
| 系统面板 | 这台 ROM 上 `android.settings.panel.action.INTERNET_CONNECTIVITY` 不存在，`WIFI_SETTINGS` 存在。 |

## 实测环境

- Rokid 眼镜，Android 12 / API 32（YodaOS-Sprite），无 GMS。
- 用 SDK 36 构建，JDK 21（Android Studio 自带的 JBR）。
- 真机跑通：扫码解析 → 建议提交成功（`STATUS_NETWORK_SUGGESTIONS_SUCCESS`）→ 连上；
  相机预览、触控板手势、HUD 都用 adb 在设备上核对过。

## 构建

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # JDK 21
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

运行时权限：`CAMERA`（扫码）、`ACCESS_FINE_LOCATION`（旧版本上 Wi-Fi 建议接口需要）。

## 用法（眼镜触控板）

| 手势 | 动作 |
|---|---|
| 单击 | 重新扫描 / 再提交一次 |
| 双击 | 退出 |
| 长按 | 退出 |

没有相机也能走通整条链路的调试入口：

```bash
adb shell am start -n com.takano.wifiscan/.MainActivity \
  --es fakeqr 'WIFI:T:WPA;S:MyWiFi;P:12345678;;'
adb shell logcat -s WifiScan:I
```

## 目录结构

```
app/src/main/java/com/takano/wifiscan/
├── MainActivity.kt   # HUD + 手势 + 主流程（扫码 → 提交建议 → 兜底跳系统 Wi-Fi 列表）
├── QrScanner.kt      # CameraX ImageAnalysis + zxing（四个方向都试）
├── WifiJoiner.kt     # WifiNetworkSuggestion 提交、状态回报、旧建议清理
├── HudView.kt        # 自绘 HUD，压在实时预览上
└── IpInfo.kt         # 用 ConnectivityManager 取 IPv4（API 30+ 读不到 NetworkInterface）
```

## 致谢

- [zxing](https://github.com/zxing/zxing) —— 二维码解码（只用 core，不含 Android 客户端）。
- AndroidX [CameraX](https://developer.android.com/media/camera/camerax) —— 相机管线。

## 许可

MIT，见 [LICENSE](LICENSE)。
