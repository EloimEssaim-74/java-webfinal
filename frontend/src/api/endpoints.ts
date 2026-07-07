import client from './client';
import { fetchEventSource } from '@microsoft/fetch-event-source';

const get = <T>(url: string, params?: Record<string, unknown>) =>
  client.get<{ code: number; message: string; data: T }>(url, { params }).then(r => r.data.data);

const post = <T>(url: string, body?: unknown) =>
  client.post<{ code: number; message: string; data: T }>(url, body).then(r => r.data.data);

const put = <T>(url: string, body?: unknown) =>
  client.put<{ code: number; message: string; data: T }>(url, body).then(r => r.data.data);

const del = (url: string) =>
  client.delete<{ code: number; message: string }>(url).then(r => r.data);

// ====== Types ======

export interface UserVO { id: number; username: string; role: string; createdAt: string; }
export interface LoginResult { token: string; tokenType: string; userId: number; username: string; role: string; }
export interface ArticleVO { id: number; authorId: number; title: string; content: string; status: string; likeCount: number; tags: string; auditResult: string; createdAt: string; updatedAt: string; }
export interface ArticleListItem { id: number; authorId: number; title: string; likeCount: number; tags: string; createdAt: string; }
export interface PageResult<T> { list: T[]; total: number; page: number; size: number; }
export interface TopArticle { id: number; title: string; authorId: number; likeCount: number; heatScore: number; }
export interface CommentVO { id: number; articleId: number; userId: number; content: string; createdAt: string; }

// ====== User ======
export const register = (username: string, password: string) => post<UserVO>('/api/user/register', { username, password });
export const login = (username: string, password: string) => post<LoginResult>('/api/user/login', { username, password });
export const logout = () => post<void>('/api/user/logout');

// ====== Articles ======
export const getArticles = (page: number, size: number) => get<PageResult<ArticleListItem>>('/api/articles', { page, size });
export const getMyArticles = (status: string | null, page: number, size: number) => {
  const params: Record<string, unknown> = { page, size };
  if (status) params.status = status;
  return get<PageResult<ArticleListItem>>('/api/articles/mine', params);
};
export const getArticle = (id: number) => get<ArticleVO>(`/api/articles/${id}`);
export const createArticle = (title: string, content: string, status: string) => post<ArticleVO>('/api/articles', { title, content, status });
export const updateArticle = (id: number, title: string, content: string) => put<ArticleVO>(`/api/articles/${id}`, { title, content });
export const deleteArticle = (id: number) => del(`/api/articles/${id}`);
export const publishArticle = (id: number) => put<ArticleVO>(`/api/articles/${id}/publish`);

// ====== Interact ======
export const likeArticle = (id: number) => post<void>(`/api/articles/${id}/like`);
export const createComment = (articleId: number, content: string) => post<void>('/api/comments', { articleId, content });
export const getComments = (articleId: number) => get<CommentVO[]>('/api/comments', { articleId });

// ====== Trending ======
export const getTrending = () => get<TopArticle[]>('/api/trending');

// ====== AI SSE ======
export function aiContinue(context: string, cbs: { onChunk: (t: string) => void; onDone: () => void; onError: (e: Error) => void }) {
  const token = localStorage.getItem('token');
  const ctrl = new AbortController();
  fetchEventSource('/api/ai/continue', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify({ context }),
    signal: ctrl.signal,
    onmessage(ev) {
      if (ev.data === '[DONE]') cbs.onDone();
      else cbs.onChunk(ev.data);
    },
    onerror(err) { cbs.onError(err as Error); throw err; },
  }).catch(() => {});
  return ctrl;
}
