import { defineConfig, loadEnv, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import { handleDemoRequest } from './dev/demoTrip';

/**
 * 只在开发服务器上挂一个中间件：`/trip/demo` 用内置演示数据渲染，
 * 这样界面改版不依赖后端就能看到真实排版（生产构建不注册它）。
 * 其余 /api 请求照旧代理到真实后端。
 */
function demoTripPlugin(): Plugin {
  return {
    name: 'tripshare-demo-trip',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        // req.url 在 vite 自带的类型里没有声明（@types/node 不是本项目的依赖），
        // 运行时它就是入站请求的 URL，这里显式取出来用。
        const url = (req as { url?: string }).url;
        if (handleDemoRequest(url, res)) return;
        next();
      });
    },
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  const backend = env.VITE_DEV_PROXY_TARGET || 'http://127.0.0.1:3000';

  return {
    plugins: [react(), demoTripPlugin()],
    server: {
      proxy: {
        '/healthz': { target: backend, changeOrigin: true },
        '/api': { target: backend, changeOrigin: true },
        '/_AMapService': { target: backend, changeOrigin: true },
      },
    },
  };
});
