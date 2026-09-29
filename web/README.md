# 行迹 Web

React + TypeScript + Vite 的公开只读分享页。`/trip/:token` 进入即加载地图和行程摘要；网页只使用公开只读 API，不持有客户端管理凭证。

Android WebView 使用 `/trip/:token?embed=map` 渲染同一张地图的纯地图视图，定位采集不依赖 WebView。普通分享链接不附带此参数。

## 本地开发

1. 复制 `.env.example` 为 `.env.local`。默认使用无需 Key 的 Leaflet + OpenStreetMap；只有已经配置好高德 Web JS Key 和后端安全代理时才需要设置 AMap 变量。
2. 设置 `VITE_DEV_PROXY_TARGET` 指向后端服务地址（默认 `http://127.0.0.1:3000`）。
3. 运行 `npm ci`、`npm run dev`，打开 `http://localhost:5173/trip/<有效分享令牌>`。运行 `npm test` 可执行坐标和轨迹辅助逻辑测试，`npm run build` 会先执行 TypeScript 检查再构建。

### 地图配置

默认使用 Leaflet 和 OpenStreetMap 底图，不需要地图 Key。页面保留起终点、实际轨迹、异常点过滤、长时间中断虚线、位置点详情和自动缩放功能。Leaflet 直接使用 API 中的 WGS-84 坐标。

只有同时配置了高德 Web JS Key 和服务端安全代理后，才启用 AMap：

```dotenv
VITE_AMAP_JS_KEY=你的高德Web端JS_Key
VITE_AMAP_SECURITY_PROXY_ENABLED=true
```

高德 JS Key 应限制允许使用的 Web 域名。`VITE_AMAP_SECURITY_PROXY_ENABLED=true` 仅在后端配置了 `AMAP_JS_SECURITY_CODE`，并通过同源 `/_AMapService` 提供安全代理后启用。安全密钥 `securityJsCode` 不进入浏览器；页面在加载 SDK 前将 `window._AMapSecurityConfig.serviceHost` 指向该同源路径。如果 AMap SDK 加载失败，页面会自动回退到 OpenStreetMap。OpenStreetMap 的版权署名保留在地图上；其公共瓦片服务须遵守使用政策，高访问量部署应替换为自建或合规的瓦片服务。

坐标接口均使用 WGS-84。Leaflet 地图直接绘制 WGS-84；选择 AMap 时才在浏览器内转换为 GCJ-02。不调用纠偏、道路吸附或路线规划服务。

## Docker

在仓库根目录的 Compose 配置中构建 `web/`。默认无需地图密钥；需要使用 AMap 时再传入两个构建参数：

```yaml
build:
  context: ./web
  args:
    VITE_AMAP_JS_KEY: ${AMAP_WEB_JS_KEY}
    VITE_AMAP_SECURITY_PROXY_ENABLED: ${AMAP_SECURITY_PROXY_ENABLED:-false}
```

不要将 Key 写入 Git。Web JS Key 属于前端地图 SDK 的公开标识，应限制域名；高德安全密钥只配置在后端。容器监听 80 端口，`nginx.conf` 将 `/api/` 和 `/_AMapService` 转发到 Compose 服务 `server:3000`，其余路径回退到 `index.html`，因此刷新 `/trip/:token` 可正常打开。

Nginx 访问日志会对分享令牌路径脱敏。TLS 应由 NAS/个人服务器入口反向代理终止，并配置可信 HTTPS 域名。

## 数据展示约定

- 目的地为 `null` 时不创建终点标记或目的地标题。
- 初次读取按 `nextCursor` 拉取完整轨迹；之后轮询行程摘要与位置增量，并以位置点 ID 去重，按照 `capturedAt` 排序。
- 超过 `max(2 × sampleInterval + 120 秒, 5 分钟)` 的间隔采用虚线连接。服务端标记的异常点保留在数据中，但不画入常规轨迹。
- 位置过期提示阈值为 `2 × uploadInterval + 120 秒`；行程结束时不再显示为持续更新。
- 页面优先展示公开行程字段 `latestPositionLabel`。后端使用配置为 `AMAP_WEB_SERVICE_KEY` 的逆地理编码服务生成地点名，调用不会从浏览器携带分享链接令牌发往第三方。
- 地点解析缺失或失败时显示“位置暂未解析”，地图仍标出实际记录位置；页面不会把坐标伪装成地名，也不会用出发地代替当前位置。
- 普通分享页只展示行程状态、地点名、最近更新时间和可用的有效速度，不展示坐标、坐标系、精度或采样点数量。
