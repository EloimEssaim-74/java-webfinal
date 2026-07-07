/**
 * App — SPA 路由器 + 全局状态
 */

const App = (() => {

  // ==================== STATE ====================

  let user = null;
  let currentPage = 1;
  const PAGE_SIZE = 10;

  const state = {
    articles: [],
    totalPages: 1,
    trending: [],
  };

  // ==================== ROUTER ====================

  function navigate(hash) {
    window.location.hash = hash;
  }

  function setPage(page) {
    currentPage = page;
    window.scrollTo(0, 0);
    route();
  }

  function getUser() { return user; }
  function setUser(u) { user = u; }

  async function route() {
    const hash = window.location.hash || '#/';
    const main = document.getElementById('main');
    const token = api.getToken();

    // 如果已登录，尝试恢复用户信息
    if (token && !user) {
      try {
        // JWT payload 中包含 userId 和 role，直接解析
        const payload = JSON.parse(atob(token.split('.')[1]));
        user = { userId: parseInt(payload.sub), role: payload.role };
      } catch (e) { /* ignore */ }
    }

    UI.renderHeaderUser();

    // 路由匹配
    const aidMatch = hash.match(/^#\/article\/(\d+)$/);
    const editorMatch = hash.match(/^#\/editor\/(\d+)$/);

    if (hash === '#/' || hash === '') {
      await renderHome(main);
    } else if (aidMatch) {
      await renderArticle(main, aidMatch[1]);
    } else if (hash === '#/trending') {
      await renderTrending(main);
    } else if (hash === '#/editor') {
      renderEditor(main, null);
    } else if (editorMatch) {
      renderEditor(main, editorMatch[1]);
    } else if (hash === '#/ai') {
      renderAiPanel(main);
    } else {
      main.innerHTML = '<div class="empty-state"><h3>404</h3><p>页面不存在</p></div>';
    }
  }

  // ==================== HOME — 文章列表 + 热搜侧栏 ====================

  async function renderHome(main) {
    main.innerHTML = '<div class="layout-feed"><div class="layout-left"><div class="spinner" style="margin:40px auto;display:block"></div></div></div>';

    try {
      const [articlesRes, trendingRes] = await Promise.all([
        api.get(`/api/articles?page=${currentPage}&size=${PAGE_SIZE}`).catch(() => null),
        api.get('/api/trending').catch(() => null)
      ]);

      const articles = articlesRes && articlesRes.code === 200 ? articlesRes.data : [];
      const trending = trendingRes && trendingRes.code === 200 ? trendingRes.data : [];

      // 粗略估算总页数
      const totalPages = articles.length < PAGE_SIZE ? currentPage : currentPage + 5;

      const articlesHtml = articles.length > 0
        ? articles.map(a => UI.articleCard(a)).join('')
        : '<div class="empty-state"><h3>还没有文章</h3><p>成为第一个创作者吧！</p></div>';

      main.innerHTML = `
        <div class="layout-feed">
          <div class="layout-left">
            ${articlesHtml}
            ${UI.pagination(currentPage, Math.max(totalPages, currentPage), null)}
          </div>
          <div class="layout-right">
            <div class="sidebar-card">
              <h3>🔥 热搜榜单</h3>
              ${UI.trendingList(trending)}
            </div>
            <div class="sidebar-card">
              <div class="sidebar-actions">
                <button class="btn btn-primary btn-block" onclick="App.navigate('#/editor')">✏️ 写文章</button>
                <button class="btn btn-outline btn-block" onclick="App.navigate('#/ai')">🤖 AI 写作助手</button>
              </div>
            </div>
          </div>
        </div>
      `;
    } catch (e) {
      main.innerHTML = `<div class="empty-state"><h3>加载失败</h3><p>${UI.escapeHtml(e.message)}</p></div>`;
    }
  }

  // ==================== ARTICLE DETAIL ====================

  async function renderArticle(main, articleId) {
    main.innerHTML = '<div class="layout-single"><div class="spinner" style="margin:40px auto;display:block"></div></div>';

    try {
      const res = await api.get(`/api/articles/${articleId}`);
      if (!res || res.code !== 200) {
        main.innerHTML = '<div class="empty-state"><h3>文章不存在</h3></div>';
        return;
      }
      const a = res.data;
      const isAuthor = user && (user.userId === a.authorId || user.role === 'admin');
      const tags = a.tags ? a.tags.split(',').filter(t => t.trim()).map(t => `<span class="tag">${UI.escapeHtml(t.trim())}</span>`).join('') : '';

      main.innerHTML = `
        <div class="layout-single">
          <div class="article-detail">
            <h1>${UI.escapeHtml(a.title)}</h1>
            <div class="article-detail-meta">
              <span>作者 #${a.authorId}</span>
              <span>${UI.fmtTime(a.createdAt)}</span>
              ${a.updatedAt !== a.createdAt ? `<span>更新于 ${UI.fmtTime(a.updatedAt)}</span>` : ''}
              <span>${a.likeCount || 0} 赞</span>
              ${a.auditResult ? `<span style="color:${a.auditResult==='PASS'?'var(--success)':'var(--danger)'}">审核:${a.auditResult}</span>` : ''}
            </div>
            <div class="article-detail-content">${UI.escapeHtml(a.content)}</div>
            ${tags ? '<div class="article-detail-tags">' + tags + '</div>' : ''}
            <div class="article-actions">
              <button class="btn btn-outline like-btn" id="btn-like" ${!user ? 'disabled title="请先登录"' : ''}>
                ❤️ <span id="like-count">${a.likeCount || 0}</span> 赞
              </button>
              ${isAuthor ? `
                <button class="btn btn-outline btn-sm" id="btn-edit-article">编辑</button>
                <button class="btn btn-danger btn-sm" id="btn-delete-article">删除</button>
              ` : ''}
            </div>
            <div class="comments-section">
              <h3>评论</h3>
              <div id="comments-list">${UI.commentList([])}</div>
              ${user ? `
                <div class="comment-form">
                  <textarea id="comment-input" placeholder="写下你的评论..." rows="2"></textarea>
                  <button class="btn btn-primary btn-sm" id="btn-submit-comment">发表</button>
                </div>
              ` : '<p style="font-size:13px;color:var(--text-muted);margin-top:12px">请先登录后再评论</p>'}
            </div>
          </div>
        </div>
      `;

      // 绑定事件
      document.getElementById('btn-like') && document.getElementById('btn-like').addEventListener('click', () => likeArticle(articleId));
      document.getElementById('btn-edit-article') && document.getElementById('btn-edit-article').addEventListener('click', () => navigate('#/editor/' + articleId));
      document.getElementById('btn-delete-article') && document.getElementById('btn-delete-article').addEventListener('click', () => deleteArticle(articleId));
      document.getElementById('btn-submit-comment') && document.getElementById('btn-submit-comment').addEventListener('click', () => submitComment(articleId));

    } catch (e) {
      main.innerHTML = `<div class="empty-state"><h3>加载失败</h3><p>${UI.escapeHtml(e.message)}</p></div>`;
    }
  }

  // ==================== LIKE ====================

  async function likeArticle(articleId) {
    if (!user) { UI.showToast('请先登录', 'error'); return; }
    try {
      await api.post(`/api/articles/${articleId}/like`);
      UI.showToast('点赞成功！', 'success');
      // 刷新详情页以更新计数
      route();
    } catch (e) {
      UI.showToast(e.message, 'error');
    }
  }

  // ==================== COMMENT ====================

  async function submitComment(articleId) {
    const input = document.getElementById('comment-input');
    const content = input.value.trim();
    if (!content) { UI.showToast('评论不能为空', 'error'); return; }
    try {
      await api.post('/api/comments', { articleId: parseInt(articleId), content });
      UI.showToast('评论已提交', 'success');
      input.value = '';
      // 等待异步持久化后刷新
      setTimeout(() => route(), 1000);
    } catch (e) {
      UI.showToast(e.message, 'error');
    }
  }

  // ==================== DELETE ARTICLE ====================

  async function deleteArticle(articleId) {
    if (!confirm('确定要删除这篇文章吗？')) return;
    try {
      await api.del(`/api/articles/${articleId}`);
      UI.showToast('文章已删除', 'success');
      navigate('#/');
    } catch (e) {
      UI.showToast(e.message, 'error');
    }
  }

  // ==================== EDITOR ====================

  function renderEditor(main, articleId) {
    const isEdit = !!articleId;
    const title = isEdit ? '编辑文章' : '写文章';
    main.innerHTML = `
      <div class="layout-single">
        <div class="article-detail">
          <h2>${title}</h2>
          <form id="form-editor-page" style="margin-top:16px">
            <input type="text" id="editor-title" placeholder="文章标题" required maxlength="200"
              class="editor-title-input" style="width:100%;padding:10px;border:1px solid var(--border);border-radius:4px;font-size:18px;margin-bottom:12px;font-family:inherit">
            <textarea id="editor-content" placeholder="分享你的知识..." required
              class="editor-content-input" style="width:100%;min-height:300px;padding:12px;border:1px solid var(--border);border-radius:4px;font-size:15px;font-family:inherit;resize:vertical;line-height:1.8"></textarea>
            <div class="editor-actions">
              <button type="button" class="btn btn-outline" id="btn-editor-draft">保存草稿</button>
              <button type="submit" class="btn btn-primary">发布文章</button>
            </div>
            <p class="form-msg" id="editor-msg"></p>
          </form>
        </div>
      </div>
    `;

    // 如果是编辑模式，加载现有文章
    if (isEdit) {
      api.get(`/api/articles/${articleId}`).then(res => {
        if (res && res.code === 200) {
          document.getElementById('editor-title').value = res.data.title || '';
          document.getElementById('editor-content').value = res.data.content || '';
        }
      });
    }

    // 绑定提交
    document.getElementById('form-editor-page').addEventListener('submit', async function(e) {
      e.preventDefault();
      const titleVal = document.getElementById('editor-title').value.trim();
      const contentVal = document.getElementById('editor-content').value.trim();
      const msgEl = document.getElementById('editor-msg');

      if (!titleVal || !contentVal) { msgEl.className = 'form-msg error'; msgEl.textContent = '请填写标题和内容'; return; }

      try {
        if (isEdit) {
          await api.put(`/api/articles/${articleId}`, { title: titleVal, content: contentVal });
          UI.showToast('文章已更新', 'success');
        } else {
          const res = await api.post('/api/articles', { title: titleVal, content: contentVal, status: 'PUBLISHED' });
          UI.showToast('文章已发布！', 'success');
          navigate('#/article/' + res.data.id);
        }
      } catch (e) {
        msgEl.className = 'form-msg error'; msgEl.textContent = e.message;
      }
    });

    document.getElementById('btn-editor-draft').addEventListener('click', async function() {
      const titleVal = document.getElementById('editor-title').value.trim();
      const contentVal = document.getElementById('editor-content').value.trim();
      if (!titleVal || !contentVal) { UI.showToast('请填写标题和内容', 'error'); return; }
      try {
        const res = await api.post('/api/articles', { title: titleVal, content: contentVal, status: 'DRAFT' });
        UI.showToast('草稿已保存', 'success');
        navigate('#/article/' + res.data.id);
      } catch (e) {
        UI.showToast(e.message, 'error');
      }
    });
  }

  // ==================== TRENDING PAGE ====================

  async function renderTrending(main) {
    main.innerHTML = '<div class="layout-single"><div class="spinner" style="margin:40px auto;display:block"></div></div>';
    try {
      const res = await api.get('/api/trending');
      const items = (res && res.code === 200) ? res.data : [];
      const colors = ['#F1403C', '#FF6600', '#FFAA00'];
      main.innerHTML = `
        <div class="trending-page">
          <h2>🔥 热搜榜单 Top 10</h2>
          ${items.length === 0 ? '<div class="empty-state"><p>暂无热搜数据</p></div>' : items.map((item, i) => `
            <div class="trending-card" onclick="App.navigate('#/article/${item.id}')">
              <span class="rank" style="background:${colors[i] || '#CCC'}">${i + 1}</span>
              <div class="info">
                <div class="t">${UI.escapeHtml(item.title)}</div>
                <div class="m">${item.likeCount || 0} 赞</div>
              </div>
              <span class="hs">${Math.round(item.heatScore)} 热度</span>
            </div>
          `).join('')}
        </div>
      `;
    } catch (e) {
      main.innerHTML = `<div class="empty-state"><h3>加载失败</h3><p>${UI.escapeHtml(e.message)}</p></div>`;
    }
  }

  // ==================== AI PANEL ====================

  function renderAiPanel(main) {
    main.innerHTML = `
      <div class="layout-single">
        <div class="ai-panel">
          <h2>🤖 AI 写作助手</h2>
          <p class="subtitle">输入上文，AI 将为你流式续写后续内容（Demo 模式无需 API Key）</p>
          <div class="ai-input-area">
            <textarea id="ai-context" placeholder="输入上文内容...例如：微服务架构是一种将应用程序构建为松耦合服务集合的软件设计方法。"></textarea>
          </div>
          <div class="ai-actions">
            <button class="btn btn-primary" id="btn-ai-start">开始续写</button>
            <button class="btn btn-outline" id="btn-ai-stop" disabled>停止</button>
            <span class="ai-status" id="ai-status"></span>
          </div>
          <div class="ai-output" id="ai-output">
            <span style="color:var(--text-muted)">AI 生成的内容将在这里逐字显示...</span>
          </div>
        </div>
      </div>
    `;

    let controller = null;
    const output = document.getElementById('ai-output');
    const status = document.getElementById('ai-status');
    const startBtn = document.getElementById('btn-ai-start');
    const stopBtn = document.getElementById('btn-ai-stop');
    const contextInput = document.getElementById('ai-context');

    startBtn.addEventListener('click', function() {
      const context = contextInput.value.trim();
      if (!context) { UI.showToast('请输入上文内容', 'error'); return; }
      if (context.length > 4000) { UI.showToast('上文内容不能超过4000字', 'error'); return; }

      output.innerHTML = '<span class="streaming-cursor">▌</span>';
      status.textContent = '生成中...';
      startBtn.disabled = true;
      stopBtn.disabled = false;

      controller = api.sse('/api/ai/continue', { context }, {
        onChunk(text) {
          const cursor = output.querySelector('.streaming-cursor');
          if (cursor) cursor.remove();
          output.appendChild(document.createTextNode(text));
          const newCursor = document.createElement('span');
          newCursor.className = 'streaming-cursor';
          newCursor.textContent = '▌';
          output.appendChild(newCursor);
          output.scrollTop = output.scrollHeight;
        },
        onDone() {
          const cursor = output.querySelector('.streaming-cursor');
          if (cursor) cursor.remove();
          status.textContent = '生成完成 ✅';
          startBtn.disabled = false;
          stopBtn.disabled = true;
          controller = null;
        },
        onError(err) {
          status.textContent = '生成失败: ' + err.message;
          startBtn.disabled = false;
          stopBtn.disabled = true;
          controller = null;
        }
      });
    });

    stopBtn.addEventListener('click', function() {
      if (controller) { controller.abort(); controller = null; }
      const cursor = output.querySelector('.streaming-cursor');
      if (cursor) cursor.remove();
      status.textContent = '已停止';
      startBtn.disabled = false;
      stopBtn.disabled = true;
    });
  }

  // ==================== INIT ====================

  function init() {
    // 监听 hash 变化
    window.addEventListener('hashchange', route);
    window.addEventListener('load', route);

    // 全局点击关闭下拉菜单
    document.addEventListener('click', function() {
      const menu = document.getElementById('user-dropdown-menu');
      if (menu) menu.classList.remove('show');
    });

    // 全局点击关闭 modal（点击背景）
    document.querySelectorAll('.modal-backdrop').forEach(bd => {
      bd.addEventListener('click', function() {
        UI.closeAllModals();
      });
    });

    // 关闭按钮
    document.querySelectorAll('.modal-close').forEach(btn => {
      btn.addEventListener('click', function() {
        UI.closeAllModals();
      });
    });

    // 写文章按钮
    document.getElementById('btn-write').addEventListener('click', function() {
      if (!api.getToken()) { UI.showToast('请先登录', 'error'); UI.openModal('modal-auth'); return; }
      navigate('#/editor');
    });

    // ==================== AUTH MODAL ====================

    // 标签切换
    document.querySelectorAll('#modal-auth .tab').forEach(tab => {
      tab.addEventListener('click', function() {
        document.querySelectorAll('#modal-auth .tab').forEach(t => t.classList.remove('active'));
        this.classList.add('active');
        const target = this.dataset.tab;
        document.getElementById('form-login').classList.toggle('hidden', target !== 'login');
        document.getElementById('form-register').classList.toggle('hidden', target !== 'register');
      });
    });

    // 登录表单
    document.getElementById('form-login').addEventListener('submit', async function(e) {
      e.preventDefault();
      const msgEl = this.querySelector('.form-msg');
      msgEl.textContent = ''; msgEl.className = 'form-msg';
      const formData = new FormData(this);
      const body = { username: formData.get('username'), password: formData.get('password') };
      try {
        const res = await api.post('/api/user/login', body);
        if (res && res.code === 200 && res.data.token) {
          api.setToken(res.data.token);
          user = { userId: res.data.userId, username: res.data.username, role: res.data.role };
          UI.showToast('登录成功！欢迎 ' + res.data.username, 'success');
          UI.closeAllModals();
          UI.renderHeaderUser();
          navigate('#/');
        }
      } catch (e) {
        msgEl.className = 'form-msg error'; msgEl.textContent = e.message;
      }
    });

    // 注册表单
    document.getElementById('form-register').addEventListener('submit', async function(e) {
      e.preventDefault();
      const msgEl = this.querySelector('.form-msg');
      msgEl.textContent = ''; msgEl.className = 'form-msg';
      const formData = new FormData(this);
      const body = { username: formData.get('username'), password: formData.get('password') };
      try {
        const res = await api.post('/api/user/register', body);
        if (res && res.code === 200) {
          UI.showToast('注册成功！请登录', 'success');
          document.querySelector('#modal-auth .tab[data-tab="login"]').click();
          this.reset();
        }
      } catch (e) {
        msgEl.className = 'form-msg error'; msgEl.textContent = e.message;
      }
    });
  }

  return { init, navigate, route, setPage, getUser, setUser };
})();

// 启动
App.init();
