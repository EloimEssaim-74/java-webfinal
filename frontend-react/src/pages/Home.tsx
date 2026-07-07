import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getArticles, getTrending } from '../api/endpoints';
import type { ArticleListItem, TopArticle } from '../api/endpoints';

export default function Home() {
  const [articles, setArticles] = useState<ArticleListItem[]>([]);
  const [trending, setTrending] = useState<TopArticle[]>([]);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const nav = useNavigate();

  const load = (p: number) => {
    setLoading(true);
    Promise.all([getArticles(p, 10), getTrending()])
      .then(([a, t]) => { setArticles(a.list); setTotal(a.total); setTrending(t); })
      .catch(() => {})
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(page); }, [page]);

  const pages = Math.max(1, Math.ceil(total / 10));
  const rankColor = (i: number) => ['#F1403C','#FF6600','#FFAA00'][i] || '#CCC';
  const fmt = (d: string) => d ? new Date(d).toLocaleDateString('zh-CN') : '';

  return (
    <div className="layout-feed">
      <div className="layout-main">
        {loading ? <span className="spinner" /> : articles.length === 0 ? (
          <div className="empty"><h3>还没有文章</h3><p>成为第一个创作者吧！</p></div>
        ) : (
          articles.map(a => (
            <div key={a.id} className="card" onClick={() => nav(`/article/${a.id}`)}>
              <div className="card-title">{a.title}</div>
              <div className="card-meta">
                <span>作者 #{a.authorId}</span><span>{a.likeCount} 赞</span><span>{fmt(a.createdAt)}</span>
              </div>
              {a.tags && <div className="card-tags">{a.tags.split(',').filter(Boolean).map(t => <span key={t} className="tag">{t.trim()}</span>)}</div>}
            </div>
          ))
        )}
        {pages > 1 && (
          <div className="pagination">
            <button disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</button>
            <button className="active">{page}</button>
            <button disabled={page >= pages} onClick={() => setPage(page + 1)}>下一页</button>
          </div>
        )}
      </div>
      <div className="layout-side">
        <div className="sidebar-card">
          <h3>🔥 热搜榜单</h3>
          {trending.length === 0 ? <p style={{fontSize:13,color:'var(--text-muted)'}}>暂无热搜</p> :
            trending.map((t, i) => (
              <div key={t.id} className="trending-item" onClick={() => nav(`/article/${t.id}`)}>
                <span className="trending-rank" style={{background:rankColor(i)}}>{i + 1}</span>
                <span className="trending-title">{t.title}</span>
                <span className="trending-heat">{Math.round(t.heatScore)} 热</span>
              </div>
            ))}
        </div>
        <div className="sidebar-card">
          <button className="btn btn-primary btn-block" onClick={() => nav('/editor')}>✏️ 写文章</button>
          <button className="btn btn-outline btn-block" style={{marginTop:8}} onClick={() => nav('/ai')}>🤖 AI 写作助手</button>
        </div>
      </div>
    </div>
  );
}
