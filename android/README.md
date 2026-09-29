# 行迹 Android 客户端

Kotlin + Jetpack Compose Android 客户端。位置由 Android 前台服务按用户间隔单次采集；成功采集的数据先写入 Room，再通过 REST API 上传。分享链接在 Keystore-backed 加密偏好中保存。

## 结构

- `app/src/main/java/com/tripshare/app/ui`：行程创建、设置、历史、分享视角和 Compose 页面。
- `app/src/main/java/com/tripshare/app/location`：高德单次定位、Android `LocationManager` 后备定位、GCJ-02 到 WGS-84 转换、前台服务。
- `app/src/main/java/com/tripshare/app/data`：API DTO、Room 数据库、本地缓存、凭证和分享链接加密存储。
- `app/src/main/java/com/tripshare/app/worker`：网络恢复后的待上传位置与离线结束状态补传。

所有发送到服务器的坐标均为 WGS-84。高德定位 SDK 和地点搜索结果使用 GCJ-02 时，只在客户端接入处转换一次；Android 系统 `LocationManager` 的位置直接按 WGS-84 保存。后台样本不请求地名解析。

## 配置

将 `local.properties.example` 复制为 `local.properties`，填写本机 Android SDK 路径和实际配置。此文件已被 Git 忽略。

```properties
sdk.dir=D\\:\\android-tools\\android-sdk
TRIP_API_BASE_URL=https://your-domain.example/
AMAP_ANDROID_KEY=
AMAP_WEB_SERVICE_KEY=
```

也可以使用同名环境变量或 Gradle 属性传入配置。密钥和部署地址不得提交到 Git。

### 高德 Key 类型

- `AMAP_ANDROID_KEY`：Android 定位 SDK Key。高德控制台需将应用包名和签名 SHA-1 配对。Debug 包名为 `com.tripshare.app.debug`，Release 包名为 `com.tripshare.app`。本地 Debug 签名通常来自用户目录中的 Android debug keystore；发布时请使用正式签名并在高德控制台登记对应 SHA-1。没有此 Key 或 Key 校验失败时，客户端改用 Android 原生 `LocationManager` 单次定位，位置仍是真实 WGS-84 数据。
- `AMAP_WEB_SERVICE_KEY`：Android 地点搜索调用的高德 Web 服务 Key。它与 Android SDK Key、Web JS API Key 是不同类型。可在 AMap 开放平台控制台检查 Key 的服务权限和安全限制。
- Web JS API Key 与 JS API 安全密钥由 `web/` 和服务端配置；Android WebView 加载自己服务器的 `/trip/:token` 页面，不在 APK 内配置或暴露 JS 安全密钥。

用户进入创建行程页面后会看到定位与隐私说明并申请位置权限。使用高德定位 SDK 前，会调用 SDK 的隐私展示和同意接口。若 SDK Key 不可用，原生定位后备不会经过高德坐标转换。

### 本地服务器联调

安装后从首页右上角 **⋮ → 服务器地址** 配置服务端根地址，检查连接后保存，无需重新构建。公开服务器使用 HTTPS；Release 也允许受信任内网 IP 的 HTTP 地址。模拟器访问开发机可填写 `http://10.0.2.2:<端口>/`。`TRIP_API_BASE_URL` 仅作为构建时默认值。服务器没有运行或地址仍为占位符时，App 会明确提示，不能创建可分享的本地假行程。已有本地行程时禁止切换服务器，先结束、同步并删除行程。

## 构建与测试

需要 JDK 17、Android SDK Platform 36 和 Build Tools 37。Windows PowerShell 示例（在 `android/` 中运行）：

```powershell
$env:JAVA_HOME = 'C:\\Program Files\\Eclipse Adoptium\\jdk-17'
$env:ANDROID_HOME = 'C:\\Users\\you\\AppData\\Local\\Android\\Sdk'
$env:TRIP_API_BASE_URL = 'https://your-domain.example/'
$env:AMAP_ANDROID_KEY = '从高德开放平台配置的 Android SDK Key'
$env:AMAP_WEB_SERVICE_KEY = '从高德开放平台配置的 Web 服务 Key'
.\\gradlew.bat :app:assembleDebug
.\\gradlew.bat :app:testDebugUnitTest
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。安装到模拟器：

```powershell
adb install -r app\\build\\outputs\\apk\\debug\\app-debug.apk
```

正式签名 APK 构建方法与 GitHub Release 附件说明见 [发布说明](../docs/RELEASING.md)。

Debug 包名为 `com.tripshare.app.debug`。模拟器应授予精确位置和通知权限，并在系统定位设置中开启定位服务。模拟器内没有真实定位硬件时，可通过 Android Emulator 的 Location 面板设置一个真实坐标用于设备定位测试；应用不会生成随机点。

## 后台行为和限制

- 创建行程前先从设备取得有效实际位置；用户选择的出发地只是地图标记，不能作为采样点。
- 标准模式按设置间隔采样并同步。详细模式每分钟采样，按设置的上传间隔批量同步。支持 1–60 分钟的整分钟间隔。
- 每次采样优先接受 60 秒内且精度不差于 1,200 米的位置；无合格缓存时进行一次定位请求。高德 SDK 单次请求最多 15 秒；后备原生 GPS/网络定位最多请求 30 秒。后台样本不做反向地理编码。
- Android 前台服务会显示最近定位时间、间隔和结束操作。系统 Doze、厂商省电设置和定位信号可能延迟采样。系统重启或用户强行停止应用后，App 不会偷偷恢复定位；重新打开后需要用户点“继续记录”。
- 结束行程后停止新采样；未同步位置由 WorkManager 在网络可用时补传。服务器仍负责独立的最长共享时间到期处理。
- Android WebView 展示服务器托管的分享页面。未同步点在本机列表中显示，不会冒充家人视角中的服务器轨迹。
