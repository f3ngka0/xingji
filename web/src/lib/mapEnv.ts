/** Web deploy-time AMap capability (build-time env; never a per-trip decision). */
export function webAmapConfigured(): boolean {
  const key = import.meta.env.VITE_AMAP_JS_KEY?.trim();
  return Boolean(key) && import.meta.env.VITE_AMAP_SECURITY_PROXY_ENABLED === 'true';
}
