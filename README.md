# 行程位置共享

乘坐公共交通时，用户主动开始一段行程，Android 定时保存设备真实位置并上传到自己的服务器。家人打开独立分享链接，即可在浏览器中查看最后一次有效位置和按采集时间排列的轨迹。目的地始终是选填项。应用不计算路线，也不会把用户选的出发地当作实测位置。

## 目录

| 目录 | 用途 |
| --- | --- |
| `android/` | Kotlin、Compose、前台定位服务、Room 本地队列和内嵌地图 |
| `server/` | TypeScript REST API、SQLite、迁移、分享权限和高德安全代理 |
| `web/` | React 响应式公开地图页 |
| `API_CONTRACT.md` | 跨客户端的 v1 JSON 接口与坐标约定 |
| `docker-compose.yml`, `Caddyfile` | 服务器、网页与 HTTPS 入口 |

## 数据与权限

- 行程出发地和可选目的地是地图标记；位置点只来自设备定位。API 与数据库统一使用 WGS-84、UTC ISO-8601 时间。高德返回的 GCJ-02 在 Android 接入层只转换一次，网页展示时转换回高德地图坐标。
- Android 首次安装生成随机安装 ID，向服务器换取随机设备凭证。凭证保存在 Android 安全存储，服务端仅保存凭证哈希。管理接口要求 Bearer 凭证。
- 每段行程有独立随机分享令牌。服务器保存令牌哈希用于公开查询，并用 AES-256-GCM 加密保存原令牌，供设备管理接口在 App 重开后恢复分享链接。分享链接仅访问公开的只读接口，不能上传位置、改设置、结束或删除行程。撤销、过期或删除后，链接失效。
- 行程最长记录时间默认 24 小时，到期后服务端结束行程。分享链接有效期和数据保留期分别配置；结束后可在链接有效期内查看历史。
- 每个实测点先写入 Room，再批量上传；服务器以客户端位置点 ID 去重，并保留采集时间与接收时间。异常点保留原始记录，地图可跳过其正常轨迹连接。

## 高德开放平台配置

**请分别创建平台对应的 Key，切勿把本仓库或聊天中提供的 Key 当作通用 Key 提交到 Git。**

1. **Android 定位 Key**：在高德控制台创建 Android 应用，填入 release 包名 `com.tripshare.app` 与签名 SHA-1；debug 包名为 `com.tripshare.app.debug`，需要单独配置相应签名或 Key。通过 Android Gradle 属性或环境变量 `AMAP_ANDROID_KEY` 注入。定位 SDK 的结果在接入层转换为 WGS-84 才上传。
2. **Web JS API Key**：创建 Web 端 Key，限制为实际分享域名。构建网页时设置 `AMAP_JS_KEY`，Compose 会将其作为 `VITE_AMAP_JS_KEY` 传给 Vite。浏览器加载 JS API 时会看见此平台 Key，这是高德 JS API 的正常工作方式，须在控制台绑定域名。
3. **JS API 安全密钥**：设置服务端 `AMAP_JS_SECURITY_CODE`。网页在加载地图之前设置同源 `/_AMapService` 代理；安全密钥只存在服务端，绝不构建进 JS 资源。请在高德控制台核对 JS API 的安全代理/安全密钥要求。
4. **Web 服务 API Key**：本版地图和位置分享不依赖 Web 服务 API。只有今后需要服务端地点搜索或逆地理编码时，才创建单独的服务端 Key，并限制用途和配额。

Android、Web JS 的 Key 类型及签名/域名限制不同。用户提供的 Key 可用于匹配的平台测试，但它不能自动代替所有平台所需的 Key。

## 个人服务器或 NAS 部署

要求：Docker Compose、一个指向服务器的域名，以及开放的 80/443 端口。HTTPS 由 Caddy 申请和续期证书；SQLite 文件存放在 Docker 命名卷 `trip_data`，重建容器不会丢失数据。

1. 复制 `.env.example` 为 `.env`，设置 `PUBLIC_HOST`、`PUBLIC_BASE_URL=https://你的域名`、Web JS Key 和 JS 安全密钥。运行 `node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"`，把输出保存为 `SHARE_TOKEN_ENCRYPTION_KEY`；此密钥须长期保留，否则已创建行程的分享链接无法恢复。`.env` 已被 Git 忽略。
2. 在高德控制台为该域名配置 Web JS Key 的域名白名单、安全代理设置。
3. 运行 `docker compose up -d --build`。检查 `https://你的域名/healthz`，以及服务容器日志。服务启动时执行数据库迁移。
4. 在 Android 构建配置中填入同一域名的 API 根地址。只有服务端成功创建行程后，App 才能给出可用的分享链接。

SQLite 默认保留 90 天数据，可通过 `DATA_RETENTION_DAYS` 调整。`DEFAULT_SHARE_TTL_SECONDS` 是分享链接有效期，`DEFAULT_MAX_SHARE_SECONDS` 是行程最长自动记录时间；两者不等同。定期备份 `trip_data`。换域名时同时更新 `.env` 与高德控制台的域名限制。

## 本地开发

服务端：在 `server/` 安装依赖，设置本地 `.env` 的 `DB_PATH` 与 `PUBLIC_BASE_URL`，运行开发脚本。网页端：在 `web/` 设置 `VITE_AMAP_JS_KEY` 后运行 Vite；本地开发代理应转发 `/api/` 和 `/_AMapService` 到服务端。具体命令见各目录的 `package.json`。

Android：安装 JDK 17、Android SDK 35，设置 `ANDROID_HOME`/`JAVA_HOME` 和 `android/local.properties` 的 `sdk.dir`。在 `android/` 用 Gradle 构建 `assembleDebug`。配置 `TRIP_API_BASE_URL`、`AMAP_ANDROID_KEY`、`AMAP_WEB_KEY`（Gradle 属性或环境变量）；release 地址必须是 HTTPS。debug 可使用 `http://10.0.2.2:3000/` 访问宿主机服务。release 签名由部署者自备，不在仓库存储。第一次使用需授予前台精确位置权限；Android 13+ 通知权限、Android 14+ 位置前台服务要求也应按系统提示处理。

## 使用和已知边界

打开 App 创建行程时，会尝试用当前位置填入出发地；用户手动选定后不会被后续自动结果覆盖。目的地留空也能开始、分享、结束、查看历史和删除。标准模式按选定间隔采样；详细模式提高采样频率并批量上传。锁屏后依靠 Android 位置前台服务继续执行；系统 Doze、厂商省电策略、权限被撤销或强行终止应用均可能延迟或停止定位，不能保证严格的秒级定时。重新打开 App 后可识别仍在进行的行程并继续记录。分享页显示的是最后一次**实测**位置和时间，长时间未更新时不推测当前位置。

本项目没有账号恢复机制。如果卸载 App 或丢失本地设备凭证，不能凭分享链接取得管理权限。分享链接是持有即访问，请只发给信任的人；需要停止访问时在 App 内撤销分享或删除行程。

## 测试

服务端、网页端和 Android 的自动化测试分别位于各自项目。验证应包含空目的地建行程、幂等上传、离线补传顺序、令牌撤销、轨迹显示与后台服务生命周期。模拟器可验证安装、权限与基本交互；真实锁屏和厂商后台策略仍需要真机测试。具体本次已运行的命令与结果在交付说明中列出。
