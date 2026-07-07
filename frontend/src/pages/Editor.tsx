import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { createArticle, updateArticle, publishArticle, getArticle } from '../api/endpoints';
import { useAuth } from '../store/auth';
import { useToast } from '../components/Toast';

export default function Editor() {
  const { id } = useParams<{ id: string }>();
  const isEdit = !!id;
  const { user } = useAuth();
  const toast = useToast();
  const nav = useNavigate();
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');

  useEffect(() => {
    if (isEdit && id) { getArticle(+id).then(a => { setTitle(a.title); setContent(a.content); }).catch(() => {}); }
  }, [id]);

  useEffect(() => { if (!user) { toast.show('请先登录', 'error'); nav('/login'); } }, []);

  const handlePublish = async () => {
    if (!title.trim() || !content.trim()) { toast.show('请填写标题和内容', 'error'); return; }
    try {
      const a = await createArticle(title, content, 'PUBLISHED');
      toast.show('文章已发布！', 'success');
      nav(`/article/${a.id}`);
    } catch { toast.show('发布失败', 'error'); }
  };

  const handleDraft = async () => {
    if (!title.trim() || !content.trim()) { toast.show('请填写标题和内容', 'error'); return; }
    try {
      if (isEdit) { await updateArticle(+id!, title, content); toast.show('已更新', 'success'); }
      else { const a = await createArticle(title, content, 'DRAFT'); toast.show('草稿已保存', 'success'); nav(`/editor/${a.id}`); }
    } catch { toast.show('保存失败', 'error'); }
  };

  return (
    <div className="layout-single">
      <div className="detail">
        <h2>{isEdit ? '编辑文章' : '写文章'}</h2>
        <div className="form-group" style={{marginTop:16}}>
          <input className="editor-title" value={title} onChange={e => setTitle(e.target.value)} placeholder="文章标题" maxLength={200} />
        </div>
        <div className="form-group">
          <textarea className="editor-content" value={content} onChange={e => setContent(e.target.value)} placeholder="分享你的知识..." />
        </div>
        <div style={{display:'flex',gap:12,justifyContent:'flex-end'}}>
          <button className="btn btn-outline" onClick={handleDraft}>{isEdit ? '保存修改' : '保存草稿'}</button>
          {!isEdit && <button className="btn btn-primary" onClick={handlePublish}>发布文章</button>}
          {isEdit && <button className="btn btn-primary" onClick={async () => { try { await publishArticle(+id!); toast.show('已发布', 'success'); nav(`/article/${id}`); } catch { toast.show('发布失败', 'error'); } }}>发布</button>}
        </div>
      </div>
    </div>
  );
}
