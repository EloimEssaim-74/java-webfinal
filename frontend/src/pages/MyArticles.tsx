import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getMyArticles, publishArticle, deleteArticle } from '../api/endpoints';
import type { ArticleListItem } from '../api/endpoints';
import { useAuth } from '../store/auth';
import { useToast } from '../components/Toast';

export default function MyArticles() {
  const [tab, setTab] = useState<string>('PUBLISHED');
  const [items, setItems] = useState<ArticleListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const { user } = useAuth();
  const toast = useToast();
  const nav = useNavigate();

  useEffect(() => { if (!user) { nav('/login'); return; } load(tab); }, [tab, user]);

  const load = (s: string) => {
    setLoading(true);
    getMyArticles(s, 1, 100).then(r => setItems(r.list)).catch(() => {}).finally(() => setLoading(false));
  };

  const handlePublish = async (id: number) => {
    try { await publishArticle(id); toast.show('已发布', 'success'); load(tab); } catch { toast.show('发布失败', 'error'); }
  };
  const handleDelete = async (id: number) => {
    if (!confirm('确定删除？')) return;
    try { await deleteArticle(id); toast.show('已删除', 'success'); load(tab); } catch { toast.show('删除失败', 'error'); }
  };

  const fmt = (d: string) => new Date(d).toLocaleDateString('zh-CN');

  return (
    <div className="layout-single">
      <h2>我的文章</h2>
      <div className="tabs-nav" style={{margin:'16px 0'}}>
        {['PUBLISHED','DRAFT'].map(s => (
          <button key={s} className={`tab-btn ${tab === s ? 'active' : ''}`} onClick={() => setTab(s)}>
            {s === 'PUBLISHED' ? '已发布' : '草稿箱'}
          </button>
        ))}
      </div>
      {loading ? <span className="spinner" /> : items.length === 0 ? (
        <div className="empty"><p>暂无文章</p></div>
      ) : items.map(a => (
        <div key={a.id} className="card" style={{cursor:'default'}}>
          <div className="card-title" style={{cursor:'pointer'}} onClick={() => nav(`/article/${a.id}`)}>{a.title}</div>
          <div className="card-meta">
            <span>{a.likeCount} 赞</span><span>{fmt(a.createdAt)}</span>
          </div>
          <div style={{display:'flex',gap:8,marginTop:8}}>
            <button className="btn btn-outline btn-sm" onClick={() => nav(`/editor/${a.id}`)}>编辑</button>
            {tab === 'DRAFT' && <button className="btn btn-primary btn-sm" onClick={() => handlePublish(a.id)}>发布</button>}
            <button className="btn btn-danger" onClick={() => handleDelete(a.id)}>删除</button>
          </div>
        </div>
      ))}
    </div>
  );
}
