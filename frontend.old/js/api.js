/**
 * API 客户端 — 封装 fetch + JWT 管理 + 错误处理
 *
 * 使用方法:
 *   const data = await api.get('/api/trending');
 *   const data = await api.post('/api/user/login', { username, password });
 *   const stream = api.sse('/api/ai/continue', { context: '...' });
 */

const api = (() => {

  const BASE = '';  // 同源，无需前缀

  // ==================== JWT 管理 ====================

  function getToken() {
    return localStorage.getItem('token');
  }

  function setToken(token) {
    localStorage.setItem('token', token);
  }

  function clearToken() {
    localStorage.removeItem('token');
  }

  function getAuthHeaders() {
    const token = getToken();
    return token ? { 'Authorization': 'Bearer ' + token } : {};
  }

  // ==================== 底层请求 ====================

  async function request(method, path, body, opts = {}) {
    const headers = {
      'Content-Type': 'application/json',
      ...getAuthHeaders(),
      ...(opts.headers || {})
    };

    const config = { method, headers };
    if (body && method !== 'GET') {
      config.body = JSON.stringify(body);
    }

    const res = await fetch(BASE + path, config);
    const json = await res.json().catch(() => null);

    // HTTP 4xx/5xx 但 JSON body 中的 code 才是业务码
    if (json && json.code >= 400 && opts.throwOnError !== false) {
      const err = new Error(json.message || '请求失败');
      err.code = json.code;
      err.data = json.data;
      throw err;
    }

    return json;
  }

  // ==================== 公开 API ====================

  return {
    getToken,
    setToken,
    clearToken,
    getAuthHeaders,

    get(path) {
      return request('GET', path);
    },

    post(path, body) {
      return request('POST', path, body);
    },

    put(path, body) {
      return request('PUT', path, body);
    },

    del(path) {
      return request('DELETE', path);
    },

    // ==================== SSE 流式请求 ====================

    /**
     * SSE 流式请求（用于 AI 续写）.
     *
     * @param {string} path  - API 路径
     * @param {object} body  - 请求体
     * @param {object} cbs   - 回调 { onChunk(text), onDone(), onError(err) }
     * @returns {AbortController} 可用于取消请求
     */
    sse(path, body, cbs) {
      const controller = new AbortController();
      const headers = {
        'Content-Type': 'application/json',
        ...getAuthHeaders()
      };

      fetch(BASE + path, {
        method: 'POST',
        headers,
        body: JSON.stringify(body),
        signal: controller.signal
      }).then(async response => {
        if (!response.ok) {
          const err = await response.text();
          cbs.onError && cbs.onError(new Error(err));
          return;
        }
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop() || '';

          for (const line of lines) {
            if (line.startsWith('data:')) {
              const text = line.substring(5).trim();
              if (text === '[DONE]') {
                cbs.onDone && cbs.onDone();
                return;
              }
              cbs.onChunk && cbs.onChunk(text);
            }
            // SSE 注释 (:heartbeat) 忽略
          }
        }
        // 流结束但没收到 [DONE]
        cbs.onDone && cbs.onDone();
      }).catch(err => {
        if (err.name !== 'AbortError') {
          cbs.onError && cbs.onError(err);
        }
      });

      return controller;
    }
  };
})();
