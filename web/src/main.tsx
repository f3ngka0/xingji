import React from 'react';
import ReactDOM from 'react-dom/client';
import { SharePage } from './pages/SharePage';
import './styles.css';

function NotFoundPage() {
  return (
    <main className="message-page">
      <section className="message-card">
        <span className="eyebrow">同行</span>
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
  return token ? <SharePage token={token} /> : <NotFoundPage />;
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
