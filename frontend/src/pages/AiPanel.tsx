import { useState, useRef } from 'react';
import { aiContinue } from '../api/endpoints';
import { useAuth } from '../store/auth';
import { useToast } from '../components/Toast';

export default function AiPanel() {
  const [context, setContext] = useState('');
  const [output, setOutput] = useState('');
  const [running, setRunning] = useState(false);
  const ctrlRef = useRef<AbortController | null>(null);
  const { user } = useAuth();
  const toast = useToast();

  const start = () => {
    if (!context.trim()) { toast.show('请输入上文内容', 'error'); return; }
    if (context.length > 4000) { toast.show('上文不能超过4000字', 'error'); return; }
    if (!user) { toast.show('请先登录', 'error'); return; }
    setOutput('');
    setRunning(true);
    ctrlRef.current = aiContinue(context, {
      onChunk: (t) => setOutput(o => o + t),
      onDone: () => setRunning(false),
      onError: () => { toast.show('生成失败', 'error'); setRunning(false); },
    });
  };

  const stop = () => { ctrlRef.current?.abort(); setRunning(false); };

  return (
    <div className="ai-panel">
      <h2>🤖 AI 写作助手</h2>
      <p style={{color:'var(--text-secondary)',fontSize:14,margin:'8px 0 16px'}}>输入上文，AI 将流式续写后续内容（Demo 模式无需 API Key）</p>
      <textarea value={context} onChange={e => setContext(e.target.value)} placeholder="输入上文内容..." />
      <div className="ai-actions">
        <button className="btn btn-primary" onClick={start} disabled={running}>开始续写</button>
        <button className="btn btn-outline" onClick={stop} disabled={!running}>停止</button>
        <span style={{fontSize:13,color:'var(--text-muted)'}}>{running ? '生成中...' : ''}</span>
      </div>
      <div className="ai-output" style={{marginTop:12}}>
        {output || <span style={{color:'var(--text-muted)'}}>AI 生成的内容将在这里逐字显示...</span>}
        {running && <span className="ai-cursor">▌</span>}
      </div>
    </div>
  );
}
