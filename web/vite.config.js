import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig(function (_a) {
    var mode = _a.mode;
    var env = loadEnv(mode, '.', '');
    var backend = env.VITE_DEV_PROXY_TARGET || 'http://127.0.0.1:3000';
    return {
        plugins: [react()],
        server: {
            proxy: {
                '/api': { target: backend, changeOrigin: true },
                '/_AMapService': { target: backend, changeOrigin: true },
            },
        },
    };
});
