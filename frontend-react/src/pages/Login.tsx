import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { login, register } from '../api/endpoints';
import { useAuth } from '../store/auth';
import { useToast } from '../components/Toast';

export default function Login() {
  const [tab, setTab] = useState<'login' | 'register'>('login');
  const [username, setUser] = useState('');
  const [password, setPass] = useState('');
  const [msg, setMsg] = useState('');
  const { setAuth } = useAuth();
  const toast = useToast();
  const nav = useNavigate();

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setMsg('');
    try {
      if (tab === 'login') {
        const data = await login(username, password);
        setAuth({ userId: data.userId, username: data.username, role: data.role }, data.token);
        toast.show(`欢迎回来，${data.username}！`, 'success');
        nav('/');
      } else {
        await register(username, password);
        toast.show('注册成功，请登录', 'success');
        setTab('login');
      }
    } catch (err: any) {
      const m = err.response?.data?.message || err.message || '请求失败';
      setMsg(m);
    }
  };

  return (
    <div style={{display:'flex',justifyContent:'center',paddingTop:60}}>
      <div className="modal-card" style={{position:'static'}}>
        <div className="modal-tabs">
          <button className={`modal-tab ${tab === 'login' ? 'active' : ''}`} onClick={() => setTab('login')}>登录</button>
          <button className={`modal-tab ${tab === 'register' ? 'active' : ''}`} onClick={() => setTab('register')}>注册</button>
        </div>
        <form onSubmit={submit}>
          <div className="form-group">
            <input value={username} onChange={e => setUser(e.target.value)} placeholder="用户名" required minLength={3} />
          </div>
          <div className="form-group">
            <input type="password" value={password} onChange={e => setPass(e.target.value)} placeholder="密码" required minLength={6} />
          </div>
          <button type="submit" className="btn btn-primary btn-block">{tab === 'login' ? '登录' : '注册'}</button>
          {msg && <p className="form-msg error">{msg}</p>}
        </form>
      </div>
    </div>
  );
}
