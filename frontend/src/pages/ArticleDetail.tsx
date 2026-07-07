import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { getArticle, likeArticle, getComments, createComment, deleteArticle } from '../api/endpoints';
import type { ArticleVO, CommentVO } from '../api/endpoints';
import { useAuth } from '../store/auth';
import { useToast } from '../components/Toast';

export default function ArticleDetail() {
  const { id } = useParams<{ id: string }>();
  const nav = useNavigate();
  const { user } = useAuth();
  const toast = useToast();
  const [a, setA] = useState<ArticleVO | null>(null);
  const [comments, setComments] = useState<CommentVO[]>([]);
  const [cmt, setCmt] = useState('');
  const [liking, setLiking] = useState(false);

  const load = () => {
    if (!id) return;
    Promise.all([getArticle(+id), getComments(+id)])
      .then(([article, c]) => { setA(article); setComments(c); })
      .catch(() => toast.show('加载失败', 'error'));
  };

  useEffect(() => { load(); }, [id]);

  const handleLike = async () => {
    if (!user) { toast.show('请先登录', 'error'); return; }
    setLiking(true);
    try { await likeArticle(+id!); toast.show('点赞成功！', 'success'); load(); }
    catch { toast.show('点赞失败', 'error'); }
    finally { setLiking(false); }
  };

  const handleComment = async () => {
    if (!cmt.trim()) return;
    try { await createComment(+id!, cmt); setCmt(''); toast.show('评论已提交', 'success'); setTimeout(load, 500); }
    catch { toast.show('评论失败', 'error'); }
  };

  const handleDelete = async () => {
    if (!confirm('确定删除？')) return;
    try { await deleteArticle(+id!); toast.show('已删除', 'success'); nav('/'); }
    catch { toast.show('删除失败', 'error'); }
  };

  if (!a) return <span className="spinner" />;
  const isAuthor = user && (user.userId === a.authorId || user.role === 'admin');
  const fmt = (d: string) => new Date(d).toLocaleDateString('zh-CN');

  return (
    <div className="layout-single">
      <div className="detail">
        <h1>{a.title}</h1>
        <div className="detail-meta">
          <span>作者 #{a.authorId}</span><span>{fmt(a.createdAt)}</span>
          {a.updatedAt !== a.createdAt && <span>更新于 {fmt(a.updatedAt)}</span>}
          <span>{a.likeCount} 赞</span>
          {a.auditResult && <span style={{color:a.auditResult==='PASS'?'var(--success)':'var(--danger)'}}>审核:{a.auditResult}</span>}
        </div>
        <div className="detail-content">{a.content}</div>
        {a.tags && <div className="card-tags" style={{marginBottom:24}}>{a.tags.split(',').filter(Boolean).map(t => <span key={t} className="tag">{t.trim()}</span>)}</div>}
        <div className="detail-actions">
          <button className="btn btn-outline" onClick={handleLike} disabled={liking}>❤️ {a.likeCount} 赞</button>
          {isAuthor && <>
            <button className="btn btn-outline btn-sm" onClick={() => nav(`/editor/${a.id}`)}>编辑</button>
            <button className="btn btn-danger" onClick={handleDelete}>删除</button>
          </>}
        </div>
        <h3 style={{marginBottom:16}}>评论 ({comments.length})</h3>
        {comments.map(c => (
          <div key={c.id} className="comment-item">
            <div className="comment-meta">用户 #{c.userId} · {fmt(c.createdAt)}</div>
            <div>{c.content}</div>
          </div>
        ))}
        {user ? (
          <div className="comment-form">
            <textarea value={cmt} onChange={e => setCmt(e.target.value)} placeholder="写下你的评论..." rows={2} />
            <button className="btn btn-primary btn-sm" onClick={handleComment}>发表</button>
          </div>
        ) : <p style={{fontSize:13,color:'var(--text-muted)',marginTop:12}}>请先登录后再评论</p>}
      </div>
    </div>
  );
}
