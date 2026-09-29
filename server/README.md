# 行迹 API

Node.js 22 + TypeScript + SQLite REST API。位置点在客户端断网时可以延迟补传；服务器保留客户端采集时间，并以幂等位置点 ID 去重。坐标在 API 与数据库中统一采用 WGS-84。

## 本地运行

```powershell
Copy-Item .env.example .env
# 编辑 .env：设置 PUBLIC_BASE_URL；生产环境还必须设置 SHARE_TOKEN_ENCRYPTION_KEY。
npm install
npm run dev
```

生产构建与测试：

```powershell
npm ci
npm test
npm start
```

`npm test` 会先运行 TypeScript 构建，再执行本地 HTTP API 测试。健康检查为 `GET /healthz`。

## Docker 部署

复制 `.env.example` 为 `.env`，填写外部 HTTPS 域名与密钥后执行：

```powershell
docker compose up -d --build
```

SQLite 文件存放于持久化卷 `/data/trips.sqlite`。数据库备份恢复时必须一并保留 `SHARE_TOKEN_ENCRYPTION_KEY`，否则无法通过设备管理 API 恢复现有分享链接。将服务放在 TLS 反向代理后；代理应把 HTTPS 的 `X-Forwarded-Proto` 传给后端。Caddy 直接代理到 API 时设置 `TRUST_PROXY_HOPS=1`、`REQUIRE_HTTPS=true`。若代理链路中间还有 Web Nginx，按实际可信代理跳数配置，避免信任用户可伪造的转发头。

反向代理将以下路径发往本服务：

- `/api/*`
- `/_AMapService/*`
- `/healthz`

分享页面 `/trip/:token` 由 Web 容器处理；后端创建行程返回的 `shareUrl` 使用 `PUBLIC_BASE_URL` 拼接。

## 配置

| 变量 | 默认值 | 用途 |
|---|---|---|
| `HOST` | `0.0.0.0` | 监听地址 |
| `PORT` | `3000` | 监听端口 |
| `DB_PATH` | `./data/trips.sqlite` | SQLite 文件路径；Docker 使用 `/data/trips.sqlite` |
| `PUBLIC_BASE_URL` | `http://localhost:3000` | 分享链接的 HTTPS 公网根域名 |
| `CORS_ORIGINS` | 空 | 允许跨域的精确 origin，逗号分隔；同源部署保持为空 |
| `TRUST_PROXY_HOPS` | `0` | 信任的反向代理层数 |
| `REQUIRE_HTTPS` | 生产环境 `true`，其他环境 `false` | 生产 API 经 TLS 代理访问时启用 |
| `SHARE_TOKEN_ENCRYPTION_KEY` | 无 | 64 位十六进制字符（32 字节）；用于 AES-256-GCM 加密数据库中的分享令牌，生产环境必填 |
| `AMAP_JS_SECURITY_CODE` | 空 | 高德 JS API 安全密钥；只配置在后端，不返回给浏览器 |
| `AMAP_WEB_SERVICE_KEY` | 空 | 可选的高德 Web 服务 Key；服务端限频逆地理编码，以显示附近地点和方位距离 |
| `DEFAULT_SAMPLE_INTERVAL_SEC` | `300` | 默认定位采样间隔 |
| `DEFAULT_MAX_SHARE_SECONDS` | `86400` | 默认最长采集时间；到期后行程自动结束 |
| `DEFAULT_SHARE_TTL_SECONDS` | `2592000` | 分享链接有效期；独立于行程结束时间 |
| `DATA_RETENTION_DAYS` | `90` | 已结束行程及位置数据保留天数 |

生成分享令牌加密密钥时，在 PowerShell 执行：

```powershell
([Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))).ToLower()
```

将输出写入 `.env`，不要提交 `.env`。数据库中设备凭证与分享令牌均不以明文存储；设备凭证用 SHA-256 摘要保存，分享令牌用 SHA-256 摘要查找，并使用上述 AES 密钥加密副本以便已认证客户端恢复分享链接。

## 高德地图安全代理

浏览器加载地图时使用 Web 端 `VITE_AMAP_JS_KEY`（Web JS API 类型，需要设置站点域名白名单）。客户端 Android 定位应使用独立的 Android Key（Android SDK 类型，并绑定包名及签名 SHA-1/SHA-256）。不要把高德 JS API 安全密钥放进 Vite 环境变量。

Web 在加载高德 JS API 之前，将 `window._AMapSecurityConfig.serviceHost` 指向 `location.origin + "/_AMapService"`。后端只允许高德固定 REST 主机的地理编码、逆地理编码、地点搜索、详情和输入提示 GET 接口，并覆盖浏览器传来的 `jscode`，注入服务器配置的 `AMAP_JS_SECURITY_CODE`。Web Key 是需要公开的域名受限 JS Key；安全密钥留在服务端。路线规划接口不在代理允许列表中。

服务端设置 `AMAP_WEB_SERVICE_KEY` 后，会对最近的真实定位点执行可选逆地理编码。每段行程两次查询至少间隔 2 分钟，查询在位置上传或公开读取之后异步执行，不阻塞轨迹保存。高德返回附近 POI 时，页面可显示如“南宁东站西北 430 米”；缺少合适 POI 时回退到区域名称。此请求只传经纬度，不传分享令牌或设备凭证。Key 缺失、配额耗尽或高德不可用时，位置采集和分享仍可用，页面不会把手动出发地当作当前地点。

## API 主要路由

- `POST /api/v1/devices`：以安装 UUID 注册设备，首次返回设备管理凭证；重复注册不会再次返回凭证。
- `POST /api/v1/trips`：创建行程。`origin`、`destination` 可为空；返回 `trip` 和顶层 `shareUrl`。
- `GET /api/v1/trips`、`GET /api/v1/trips/:id`：列出或读取自己的行程；管理端响应会带可恢复的 `trip.shareUrl`（若链接已撤销/过期则不带）。
- `PATCH /api/v1/trips/:id/settings`：修改进行中行程的采样、上传间隔、模式与最长共享时长。
- `POST /api/v1/trips/:id/positions`：批量接收 1–100 个位置点；逐点返回已接受、重复及拒绝结果。行程结束后仍接受采集时间不晚于结束时间的位置，以支持断网补传。
- `POST /api/v1/trips/:id/end`、`POST /api/v1/trips/:id/revoke`、`DELETE /api/v1/trips/:id`：结束、撤销分享或删除行程。
- `GET /api/v1/public/trips/:token`、`GET /api/v1/public/trips/:token/positions`：无需认证的只读分享接口。

管理接口必须使用 `Authorization: Bearer <credential>`。公开接口只接受分享令牌，绝不会把凭证、设备 ID 或管理用行程 ID 返回给浏览器。所有错误使用 `{ "error": { "code": "...", "message": "..." } }`。请求频率受限，API 不写入访问日志或令牌日志。

采样与上传间隔支持每分钟至每 60 分钟的任意整分钟；详细模式固定每分钟采样，上传间隔不得短于采样间隔。服务启动时及每分钟检查最长共享时间，并清理已超过保留期限的已结束数据。

## 验证范围与限制

自动化测试覆盖无目的地和显式空值、目的地行程、设备凭证隔离、断网晚到位置补传、重复上传、公开链接撤销/过期/删除、分页位置输出、高德安全代理密钥注入、超时结束时间、异常点标记与数据保留。它们不替代真实公网 TLS、真实高德账号 Key、反向代理部署及 Android 真机联调。
