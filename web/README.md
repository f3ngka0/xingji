# 行程共享 Web

React + TypeScript + Vite 的公开只读分享页。`/trip/:token` 进入即加载地图和行程摘要；网页只使用公开只读 API，不持有客户端管理凭证。

## 本地开发

1. 复制 `.env.example` 为 `.env.local`，填入高德 JavaScript API Key。
2. 设置 `VITE_DEV_PROXY_TARGET` 指向后端服务地址（默认 `http://127.0.0.1:3000`）。
3. 运行 `npm ci`、`npm run dev`，打开 `http://localhost:5173/trip/<有效分享令牌>`。运行 `npm test` 可执行坐标和轨迹辅助逻辑测试，`npm run build` 会先执行 TypeScript 检查再构建。

高德 JS Key 应在开放平台限制允许使用的 Web 域名。安全密钥 `securityJsCode` 不进入浏览器；页面在加载 SDK 前将 `window._AMapSecurityConfig.serviceHost` 指向同源 `/_AMapService`。生产反向代理把该路径转交后端，高德安全代理凭据由服务端配置。

坐标接口均使用 WGS-84。渲染时在浏览器内转换为高德地图使用的 GCJ-02，不调用纠偏、道路吸附或路线规划服务。

## Docker

在仓库根目录的 Compose 配置中构建 `web/`，通过构建参数 `VITE_AMAP_JS_KEY` 传入 Web JS Key，例如：

```yaml
build:
  context: ./web
  args:
    VITE_AMAP_JS_KEY: ${AMAP_WEB_JS_KEY}
```

不要将 Key 写入 Git。Key 属于前端地图 SDK 的公开标识，应限制域名；高德安全密钥只配置在后端。容器监听 80 端口，`nginx.conf` 将 `/api/` 和 `/_AMapService` 转发到 Compose 服务 `server:3000`，其余路径回退到 `index.html`，因此刷新 `/trip/:token` 可正常打开。

Nginx 访问日志会对分享令牌路径脱敏。TLS 应由 NAS/个人服务器入口反向代理终止，并配置可信 HTTPS 域名。

## 数据展示约定

- 目的地为 `null` 时不创建终点标记或目的地标题。
- 初次读取按 `nextCursor` 拉取完整轨迹；之后轮询行程摘要与位置增量，并以位置点 ID 去重，按照 `capturedAt` 排序。
- 超过 `max(2 × sampleInterval + 120 秒, 5 分钟)` 的间隔采用虚线连接。服务端标记的异常点保留在数据中，但不画入常规轨迹。
- 位置过期提示阈值为 `2 × uploadInterval + 120 秒`；行程结束时不再显示为持续更新。
- 页面使用坐标作为位置描述，不做反向地理编码，避免重复请求和分享 token 泄露到第三方请求。
