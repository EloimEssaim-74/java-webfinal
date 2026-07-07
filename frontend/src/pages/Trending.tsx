import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getTrending } from '../api/endpoints';
import type { TopArticle } from '../api/endpoints';

export default function Trending() {
  const [items, setItems] = useState<TopArticle[]>([]);
  const nav = useNavigate();

  useEffect(() => {
    getTrending().then(setItems).catch(() => {});
    const t = setInterval(() => getTrending().then(setItems).catch(() => {}), 10000);
    return () => clearInterval(t);
  }, []);

  const colors = ['#F1403C', '#FF6600', '#FFAA00'];
  return (
    <div className="layout-single">
      <h2>🔥 热搜榜单 Top 10</h2>
      {items.length === 0 ? <div className="empty"><p>暂无热搜数据</p></div> : items.map((t, i) => (
        <div key={t.id} className="card" onClick={() => nav(`/article/${t.id}`)} style={{display:'flex',alignItems:'center',gap:16}}>
          <span style={{width:36,height:36,borderRadius:8,display:'flex',alignItems:'center',justifyContent:'center',fontWeight:700,fontSize:16,color:'#fff',background:colors[i]||'#CCC',flexShrink:0}}>{i + 1}</span>
          <div style={{flex:1}}>
            <div style={{fontSize:16,fontWeight:500}}>{t.title}</div>
            <div style={{fontSize:13,color:'var(--text-muted)'}}>{t.likeCount} 赞</div>
          </div>
          <span style={{fontSize:14,fontWeight:600,color:'var(--danger)'}}>{Math.round(t.heatScore)} 热度</span>
        </div>
      ))}
    </div>
  );
}
