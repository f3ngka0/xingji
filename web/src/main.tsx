import React from 'react';
import ReactDOM from 'react-dom/client';
import { MapPin } from 'lucide-react';
import { AtlasTripPage } from './pages/AtlasTripPage';
import { InlayTripPage } from './pages/InlayTripPage';
import { SharePage } from './pages/SharePage';
import { SignalTripPage } from './pages/SignalTripPage';
import './styles.css';

// 无效路径走的是分享页同一套外观（atlas.css 由 AtlasTripPage 引入；这里复用同样的类名，
// 即使样式没加载，结构与文案依然可读）。
function NotFoundPage() {
  return (
    <main className="atlas-shell atlas-static">
      <section className="atlas-message" role="status">
        <MapPin size={22} strokeWidth={1.6} aria-hidden="true" />
        <span className="atlas-eyebrow">行迹 · 只读分享</span>
        <h1>页面不存在</h1>
        <p>请检查分享链接是否完整。</p>
      </section>
    </main>
  );
}

function App() {
  const match = /^\/trip\/([^/]+)\/?$/.exec(window.location.pathname);
  if (!match) return <NotFoundPage />;

  let token: string;
  try {
    token = decodeURIComponent(match[1]);
  } catch {
    return <NotFoundPage />;
  }
  if (!token) return <NotFoundPage />;

  // 分享页以选定的「路牌读数」为默认界面，保留探索方向供本分支比对：
  //   默认        方向 04「路牌读数」
  //   ?ui=atlas   方向 01「折叠图志」
  //   ?ui=inlay   方向 02「中轴 · 内嵌读数」
  //   ?ui=classic 上一版灰度版
  const ui = new URLSearchParams(window.location.search).get('ui');
  if (ui === 'atlas') return <AtlasTripPage token={token} />;
  if (ui === 'inlay') return <InlayTripPage token={token} />;
  if (ui === 'classic') return <SharePage token={token} />;
  return <SignalTripPage token={token} />;
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
