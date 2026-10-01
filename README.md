# 同行

同行是一款轻量级行程位置共享应用。乘坐公共交通时，用户在首页点击一次「开始共享」，App 用首个真实定位点创建行程、启动前台定位记录并生成分享链接；家人打开独立分享链接，即可在浏览器中查看最后一次有效位置和按采集时间排列的轨迹。没有出发地表单：一次行程的起点就是开始后的第一个有效实测位置。目的地始终是选填的附加信息，行程开始后可随时添加、修改或清除，且不影响位置记录。应用不计算路线，也不做轨迹纠偏。

Android 安装包与部署源码 ZIP 见 [GitHub Releases](https://github.com/f3ngka0/xingji/releases)。首次启动没有服务器时 App 直接进入「连接服务器」页；之后可在 **设置 → 服务器** 修改。所有技术参数（服务器、地图服务、记录模式、更新间隔、最长共享时间、权限状态）都集中在设置页。

## 目录

| 目录 | 用途 |
| --- | --- |
| `android/` | Kotlin、Compose、前台定位服务、Room 本地队列和内嵌地图 |
| `server/` | TypeScript REST API、SQLite、迁移、分享权限和高德安全代理 |
| `web/` | React 响应式公开地图页 |
| `API_CONTRACT.md` | 跨客户端的 v1 JSON 接口与坐标约定 |
| `docker-compose.yml`, `Caddyfile` | 服务器、网页与 HTTPS 入口 |

## 数据与权限

- 行程出发地和可选目的地是地图标记；位置点只来自设备定位。API 与数据库统一使用 WGS-84、UTC ISO-8601 时间。高德返回的 GCJ-02 在 Android 接入层只转换一次；默认 OpenStreetMap 地图直接使用 WGS-84，启用高德地图时网页再转换为 GCJ-02。
- Android 首次安装生成随机安装 ID，向服务器换取随机设备凭证。凭证保存在 Android 安全存储，服务端仅保存凭证哈希。管理接口要求 Bearer 凭证。
- 每段行程有独立随机分享令牌。服务器保存令牌哈希用于公开查询，并用 AES-256-GCM 加密保存原令牌，供设备管理接口在 App 重开后恢复分享链接。分享链接仅访问公开的只读接口，不能上传位置、改设置、结束或删除行程。撤销、过期或删除后，链接失效。
- 行程最长记录时间默认 24 小时，到期后服务端结束行程。分享链接有效期和数据保留期分别配置；结束后可在链接有效期内查看历史。
- 每个实测点先写入 Room，再批量上传；服务器以客户端位置点 ID 去重，并保留采集时间与接收时间。异常点保留原始记录，地图可跳过其正常轨迹连接。

## 地图与高德开放平台配置

每个行程在创建时记录 `mapProvider`（`OSM` 或 `AMAP`），Android 内嵌地图与家人打开的 Web 页面都按行程自身的 provider 渲染；之后修改地图服务设置不影响历史行程。坐标体系：存储与传输始终是 WGS-84，展示层由统一的坐标适配器（Web 端 `web/src/lib/mapCoordinate.ts`、Android 接入层 `CoordinateTransform`）完成唯一一次转换——OSM 直接使用 WGS-84，高德展示前转换为 GCJ-02，严禁二次转换。

- **基础模式（默认，OpenStreetMap）**：无需任何 Key。Web 使用 Leaflet 渲染 OSM 数据瓦片（默认镜像源为社区 `tile.openstreetmap.de`，中国大陆网络通常比 `tile.openstreetmap.org` 更可达；也可在 `web/src/components/TripMap.tsx` 换成自建瓦片）。基础模式不提供 POI 搜索与目的地设置。
- **增强模式（高德地图）**：在 App **设置 → 地图服务** 中选择增强模式并填写两个 Key（保存进设备加密存储，不进 Git、不进日志）：
  1. **高德 Android Key**：定位 SDK 使用。填入 release 包名 `com.tripshare.app` 与签名 SHA-1；debug 包名为 `com.tripshare.app.debug`。运行时输入的 Key 会覆盖构建期 `AMAP_ANDROID_KEY`（Gradle 属性或环境变量注入的旧方式仍作为兜底）。定位 SDK 的 GCJ-02 结果在接入层转换为 WGS-84 才上传；Key 不可用时退回系统 `LocationManager` 的 WGS-84 单次定位。
  2. **高德 Web 服务 Key**：行程中的 POI 搜索（inputtips）。服务端同名环境变量 `AMAP_WEB_SERVICE_KEY` 用于限频逆地理编码，为最近位置点解析「地名 + 方位 + 距离」标签；留空时 UI 显示中性的「当前位置」。
- 两个 Key 均完整时增强模式才生效（显示「高德服务已配置」），否则显示「配置尚未完成」并自动保持基础模式。
- **Web 高德底图（可选，独立于 App 配置）**：要让家人端 AMAP 行程渲染高德地图，部署侧需设置 Web JS 的 `AMAP_JS_KEY`、服务端 `AMAP_JS_SECURITY_CODE` 并开启 `AMAP_SECURITY_PROXY_ENABLED=true`（安全密钥只存在服务端）。若部署未配置，AMAP 行程在 Web 自动回退到开源地图并给出轻提示——WGS-84 在 OSM 上仍然正确，只是没有高德底图语义。

## 个人服务器或 NAS 部署

要求：Docker Compose、一个指向服务器的域名，以及开放的 80/443 端口。HTTPS 由 Caddy 申请和续期证书；SQLite 文件存放在 Docker 命名卷 `trip_data`，重建容器不会丢失数据。

1. 复制 `.env.example` 为 `.env`，设置 `PUBLIC_HOST`、`PUBLIC_BASE_URL=https://你的域名`。运行 `node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"`，把输出保存为 `SHARE_TOKEN_ENCRYPTION_KEY`；此密钥须长期保留，否则已创建行程的分享链接无法恢复。`.env` 已被 Git 忽略。
2. 默认地图无需高德 JS Key。如选择高德地图，在控制台为该域名配置 Web JS Key 的域名白名单和安全代理，并填写上述三个高德 JS 环境变量。
3. 运行 `docker compose up -d --build`。检查 `https://你的域名/healthz`，以及服务容器日志。服务启动时执行数据库迁移。
4. 安装 Release APK，首次启动直接进入「连接服务器」页，填入同一域名，例如 `https://trip.example.com`，验证成功后进入首页；之后可在 **设置 → 服务器** 修改。无需为了修改地址重新编译 APK。只有服务端成功创建行程后，App 才能给出可用的分享链接。

SQLite 默认保留 90 天数据，可通过 `DATA_RETENTION_DAYS` 调整。`DEFAULT_SHARE_TTL_SECONDS` 是分享链接有效期，`DEFAULT_MAX_SHARE_SECONDS` 是行程最长自动记录时间；两者不等同。定期备份 `trip_data`。换域名时同时更新 `.env` 与高德控制台的域名限制。

### 内网 IP 部署

仅在受信任的局域网或 VPN 中，可使用 HTTP IP 入口。复制 `.env.example` 为 `.env`，配置 `PUBLIC_BASE_URL=http://服务器内网IP:8080` 和实际的 `SHARE_TOKEN_ENCRYPTION_KEY`，然后运行：

```sh
docker compose -f docker-compose.yml -f docker-compose.lan.yml up -d --build server web
```

App 的服务器地址填写相同的 `http://服务器内网IP:8080`。此入口同时提供 API、健康检查与 Web 地图；家人也需要能访问该内网或 VPN。端口可通过 `LAN_PORT` 配置。公网部署使用上方的 HTTPS 方案。

## 本地开发

服务端：在 `server/` 安装依赖，设置本地 `.env` 的 `DB_PATH` 与 `PUBLIC_BASE_URL`，运行开发脚本。网页端默认用 OpenStreetMap，直接运行 Vite；本地开发代理应转发 `/api/` 到服务端。选择高德地图时还应转发 `/_AMapService`。具体命令见各目录的 `package.json`。

Android：安装 JDK 17、Android SDK Platform 36 和 Build Tools 37，设置 `ANDROID_HOME`/`JAVA_HOME` 和 `android/local.properties` 的 `sdk.dir`。App 的目标系统版本仍是 Android 35。在 `android/` 用 Gradle 构建 `assembleDebug`。`TRIP_API_BASE_URL` 可配置默认地址，用户可在 App 中配置地址；公开服务器使用 HTTPS，内网 IP 可使用 HTTP。模拟器可填写 `http://10.0.2.2:3000/` 访问宿主机服务。按需配置 `AMAP_WEB_SERVICE_KEY` 和 `AMAP_ANDROID_KEY`（Gradle 属性或环境变量）。Release 构建与签名方法见 [发布说明](docs/RELEASING.md)。第一次使用需授予前台精确位置权限；Android 13+ 通知权限、Android 14+ 位置前台服务要求也应按系统提示处理。

## 使用和已知边界

首页显示「定位已就绪 / 正在获取当前位置 / 暂时无法获取位置」和真实逆地理地名（系统 Geocoder 或高德定位附带的地点名；拿不到地名时只显示「当前位置已获取」，绝不伪造）。点击「开始共享」一步创建行程：捕获首个有效定位 → 服务端建行程（记录 mapProvider）→ 写入首点 → 启动前台服务 → 进入共享中页。行程标题自动为「从某地出发」，基础模式没有目的地入口；增强模式可在共享中页右上角 ⋮ 添加、修改或清除目的地。标准模式按选定间隔采样；详细模式提高采样频率并批量上传。设置页的默认值只影响以后新行程；进行中的行程可在共享中页 ⋮ 的「行程设置」单独调整。锁屏后依靠 Android 位置前台服务继续执行；系统 Doze、厂商省电策略、权限被撤销或强行终止应用均可能延迟或停止定位，不能保证严格的秒级定时。重新打开 App 后可识别仍在进行的行程并继续记录。分享页显示的是最后一次**实测**位置和时间，长时间未更新时不推测当前位置。

本项目没有账号恢复机制。如果卸载 App 或丢失本地设备凭证，不能凭分享链接取得管理权限。分享链接是持有即访问，请只发给信任的人；需要停止访问时在 App 内撤销分享或删除行程。

## 测试

服务端、网页端和 Android 的自动化测试分别位于各自项目。启动一次性测试服务器后，可运行 `node scripts/integration-smoke.mjs` 做跨进程 REST 联调；它会在目标服务器写入测试设备、行程和位置点，请只对可丢弃的测试数据库运行。验证应包含空目的地建行程、幂等上传、离线补传顺序、令牌撤销、轨迹显示与后台服务生命周期。模拟器可验证安装、权限与基本交互；真实锁屏和厂商后台策略仍需要真机测试。具体本次已运行的命令与结果在交付说明中列出。
