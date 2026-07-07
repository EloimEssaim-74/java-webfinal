import { useNavigate, useLocation } from 'react-router-dom';
import { useState } from 'react';
import { useAuth } from '../store/auth';

export default function Header() {
  const { user, logout } = useAuth();
  const nav = useNavigate();
  const loc = useLocation();
  const [showMenu, setShowMenu] = useState(false);
  const active = (p: string) => loc.pathname === p ? 'nav-item active' : 'nav-item';

  return (
    <header className="header">
      <div className="header-inner">
        <a href="#/" className="logo">知识库</a>
        <nav className="nav-links">
          <a href="#/" className={active('/')}>首页</a>
          <a href="#/trending" className={active('/trending')}>热搜</a>
          <a href="#/ai" className={active('/ai')}>AI写作</a>
          {user && <a href="#/my" className={active('/my')}>我的文章</a>}
        </nav>
        <div className="header-right">
          {user ? (
            <>
              <button className="btn btn-primary btn-sm" onClick={() => nav('/editor')}>写文章</button>
              <div className="user-drop" onClick={() => setShowMenu(!showMenu)}>
                <span className="user-avatar">{user.username[0].toUpperCase()}</span>
                <span style={{fontSize:14,color:'var(--text-secondary)'}}>{user.username}</span>
                <div className={`user-menu ${showMenu ? 'show' : ''}`}>
                  <button onClick={() => { setShowMenu(false); logout(); nav('/'); }}>退出登录</button>
                </div>
              </div>
            </>
          ) : (
            <button className="btn btn-outline btn-sm" onClick={() => nav('/login')}>登录</button>
          )}
        </div>
      </div>
    </header>
  );
}
