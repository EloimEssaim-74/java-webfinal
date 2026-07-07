/**
 * UI 组件库 — Header、卡片、Toast、Modal
 */

const UI = (() => {

  // ==================== TOAST ====================

  function showToast(message, type = 'info', duration = 3000) {
    const container = document.getElementById('toast-container');
    const el = document.createElement('div');
    el.className = 'toast ' + type;
    el.textContent = message;
    container.appendChild(el);
    setTimeout(() => { el.remove(); }, duration);
  }

  // ==================== MODAL ====================

  function openModal(id) {
    document.getElementById(id).classList.remove('hidden');
  }

  function closeModal(id) {
    document.getElementById(id).classList.add('hidden');
  }

  function closeAllModals() {
    document.querySelectorAll('.modal').forEach(m => m.classList.add('hidden'));
  }

  // ==================== HEADER / USER ====================

  function renderHeaderUser() {
    const area = document.getElementById('user-area');
    const token = api.getToken();

    if (token) {
      const user = App.getUser();
      const initial = user && user.username ? user.username[0].toUpperCase() : 'U';
      area.innerHTML = `
        <div class="user-dropdown" id="user-dropdown">
          <span class="user-avatar">${initial}</span>
          <span style="font-size:14px;color:var(--text-secondary)">${user ? user.username : ''}</span>
          <div class="user-dropdown-menu" id="user-dropdown-menu">
            <a id="btn-logout">退出登录</a>
          </div>
        </div>
      `;
      document.getElementById('user-dropdown').addEventListener('click', function(e) {
        e.stopPropagation();
        document.getElementById('user-dropdown-menu').classList.toggle('show');
      });
      document.getElementById('btn-logout').addEventListener('click', async function() {
        try { await api.post('/api/user/logout'); } catch(e) {}
        api.clearToken();
        App.setUser(null);
        showToast('已退出登录', 'info');
        renderHeaderUser();
        App.navigate('#/');
      });
    } else {
      area.innerHTML = `
        <button class="btn btn-outline btn-sm" id="btn-login">登录</button>
      `;
      document.getElementById('btn-login').addEventListener('click', function() {
        openModal('modal-auth');
      });
    }
  }

  // ==================== 文章卡片 ====================

  function articleCard(article) {
    const tags = article.tags
      ? article.tags.split(',').filter(t => t.trim()).map(t => `<span class="tag">${escapeHtml(t.trim())}</span>`).join('')
      : '';
    const time = fmtTime(article.createdAt);

    return `
      <div class="article-card" data-id="${article.id}" onclick="App.navigate('#/article/${article.id}')">
        <div class="article-card-title">${escapeHtml(article.title)}</div>
        <div class="article-card-meta">
          <span>作者 #${article.authorId}</span>
          <span>${article.likeCount || 0} 赞</span>
          <span>${time}</span>
        </div>
        ${tags ? '<div class="article-card-tags">' + tags + '</div>' : ''}
      </div>
    `;
  }

  // ==================== 分页器 ====================

  function pagination(current, totalPages, onPage) {
    if (totalPages <= 1) return '';
    let html = '<div class="pagination">';
    html += `<button ${current <= 1 ? 'disabled' : ''} onclick="App.setPage(${current - 1})">上一页</button>`;
    for (let i = 1; i <= totalPages; i++) {
      if (i === 1 || i === totalPages || (i >= current - 2 && i <= current + 2)) {
        html += `<button class="${i === current ? 'active' : ''}" onclick="App.setPage(${i})">${i}</button>`;
      } else if (i === current - 3 || i === current + 3) {
        html += '<button disabled>…</button>';
      }
    }
    html += `<button ${current >= totalPages ? 'disabled' : ''} onclick="App.setPage(${current + 1})">下一页</button>`;
    html += '</div>';
    return html;
  }

  // ==================== 热搜列表(侧边栏) ====================

  function trendingList(items) {
    if (!items || items.length === 0) {
      return '<div class="empty-state"><p>暂无热搜数据</p></div>';
    }
    return '<ul class="trending-list">' + items.map((item, i) => {
      const rankClass = i === 0 ? 'top1' : i === 1 ? 'top2' : i === 2 ? 'top3' : 'normal';
      return `
        <li class="trending-item" onclick="App.navigate('#/article/${item.id}')">
          <span class="trending-rank ${rankClass}">${i + 1}</span>
          <span class="trending-title" title="${escapeHtml(item.title)}">${escapeHtml(item.title)}</span>
          <span class="trending-heat">${Math.round(item.heatScore)} 热</span>
        </li>
      `;
    }).join('') + '</ul>';
  }

  // ==================== 评论列表 ====================

  function commentList(comments) {
    if (!comments || comments.length === 0) {
      return '<p style="color:var(--text-muted);font-size:14px;">暂无评论，来说两句吧</p>';
    }
    return comments.map(c => `
      <div class="comment-item">
        <div class="comment-meta">用户 #${c.userId} · ${fmtTime(c.createdAt)}</div>
        <div class="comment-content">${escapeHtml(c.content)}</div>
      </div>
    `).join('');
  }

  // ==================== 工具函数 ====================

  function fmtTime(isoStr) {
    if (!isoStr) return '';
    const d = new Date(isoStr);
    const now = new Date();
    const diff = now - d;
    if (diff < 60000) return '刚刚';
    if (diff < 3600000) return Math.floor(diff / 60000) + ' 分钟前';
    if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前';
    if (diff < 604800000) return Math.floor(diff / 86400000) + ' 天前';
    return d.toLocaleDateString('zh-CN');
  }

  function escapeHtml(str) {
    if (!str) return '';
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
  }

  return {
    showToast,
    openModal, closeModal, closeAllModals,
    renderHeaderUser,
    articleCard, pagination, trendingList, commentList,
    fmtTime, escapeHtml
  };
})();
