# 行迹 Android Release 与 GitHub 发布

Release APK 包名为 `com.tripshare.app`，最低 Android 8.0（API 26）。Debug 包名为 `com.tripshare.app.debug`，可与 Release 同时安装。Release 启用 R8 优化，使用独立发布证书签名。

## 安装与服务器地址

下载 Release 的 APK，允许安装该来源的应用。打开「行迹」，从首页右上角 **⋮ → 服务器地址** 填入服务端根地址，验证连接后保存。

- 公网使用 `https://trip.example.com` 或具有有效证书的 HTTPS IP 地址。
- 内网可填写 `192.168.1.20:3000` 或 `http://192.168.1.20:3000`。HTTP 会明文传输位置和设备凭证，应仅用于受信任内网。
- 模拟器访问宿主机使用 `http://10.0.2.2:3100`；手机访问电脑应填写电脑的局域网 IP，不能填写 `127.0.0.1`。
- 推荐填写本项目 API 与地图页面的统一入口。Docker 默认部署使用同一 HTTPS 域名；仅启动 Node API 无法提供 `/trip/...` 地图页，此时需要另行部署 Web 并配置其公开地址。
- `PUBLIC_BASE_URL` 必须填写家人可访问的网页地址；客户端填入的管理地址不会自动替换服务器生成的分享链接。

服务器地址保存在本机，重启 App 后继续生效。为避免将原行程及设备凭证误发到另一台服务器，存在本地行程时禁止切换到不同地址。先结束、同步并删除行程后再切换；切换会更新设备注册信息。

发布 APK 不包含实际高德 Key。系统原生定位和默认 OpenStreetMap 地图无需高德 Key；地点搜索需要构建者配置自己的 `AMAP_WEB_SERVICE_KEY`。服务端地名解析可以独立通过 `.env` 配置该 Key。

## 发布签名

将签名文件及密码保存在仓库外，长期备份。同一包名的后续版本必须使用相同签名才能覆盖安装。

首次创建证书（JDK 17 的 `keytool` 会交互式询问密码）：

```powershell
keytool -genkeypair -keystore C:\private\tripshare.jks -alias tripshare -keyalg RSA -keysize 4096 -validity 10000
```

创建仓库外的 `signing.properties`：

```properties
RELEASE_STORE_FILE=C:/private/tripshare.jks
RELEASE_STORE_PASSWORD=填入实际密码
RELEASE_KEY_ALIAS=tripshare
RELEASE_KEY_PASSWORD=填入实际密码
```

设置 `JAVA_HOME`、`ANDROID_HOME`，在仓库根目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-android-release.ps1 -SigningProperties C:\private\signing.properties
```

也可直接通过环境变量或 Android 的本地 `local.properties` 提供同名四项签名配置，再执行 `android/gradlew.bat :app:assembleRelease`。APK 输出为 `android/app/build/outputs/apk/release/app-release.apk`。签名配置、Key、数据库、构建产物均不提交 Git。

使用 `apksigner verify --verbose --print-certs app-release.apk` 验证签名，并用 `Get-FileHash -Algorithm SHA256` 生成下载校验值。使用高德定位 SDK 时，在高德控制台绑定 Release 包名与发布证书 SHA-1。

首次 v1.0.0 发布 APK 的包名是 `com.tripshare.app`，证书 SHA-1 为 `63:51:6D:E3:A4:C8:D7:4E:C3:04:4C:BB:1D:BB:33:7E:9F:9A:FA:1E`。自行构建时以自己的签名为准。

## GitHub Release

发布前运行 `server/` 的 `npm test`、`web/` 的 `npm test` 和 `npm run build`、Android 单元测试与设备验证。更新版本号，在已提交的 Git 修订上建立 `v1.0.0` 等版本标签。

GitHub Release 应提供签名 APK、部署源码 ZIP、`SHA256SUMS.txt` 和验证结果。签名私钥、签名密码、实际高德 Key、设备凭证和本机数据库绝不作为附件上传。

模拟器验证不能代替真机 GPS、锁屏定位、厂商省电策略及正式 HTTPS 服务器的实测。
