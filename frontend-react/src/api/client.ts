import axios from 'axios';

const client = axios.create({
  baseURL: '',
  timeout: 30000,
});

client.interceptors.request.use((config) => {
  const token = localStorage.getItem('token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

client.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('token');
      window.location.hash = '#/login';
    } else if (err.response?.status === 429) {
      // handled per-call
    }
    return Promise.reject(err);
  }
);

export default client;
