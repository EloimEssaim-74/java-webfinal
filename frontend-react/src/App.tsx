import { Routes, Route, Navigate } from 'react-router-dom';
import Header from './components/Header';
import Home from './pages/Home';
import ArticleDetail from './pages/ArticleDetail';
import Login from './pages/Login';
import Editor from './pages/Editor';
import Trending from './pages/Trending';
import AiPanel from './pages/AiPanel';
import MyArticles from './pages/MyArticles';
import Toast from './components/Toast';

export default function App() {
  return (
    <>
      <Header />
      <main id="main">
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/article/:id" element={<ArticleDetail />} />
          <Route path="/login" element={<Login />} />
          <Route path="/editor" element={<Editor />} />
          <Route path="/editor/:id" element={<Editor />} />
          <Route path="/trending" element={<Trending />} />
          <Route path="/ai" element={<AiPanel />} />
          <Route path="/my" element={<MyArticles />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
      <Toast />
    </>
  );
}
