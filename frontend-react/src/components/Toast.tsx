import { create } from 'zustand';

type ToastType = 'success' | 'error' | 'info';

interface ToastItem { id: number; message: string; type: ToastType; }
interface ToastState { toasts: ToastItem[]; show: (m: string, t: ToastType) => void; }

let nextId = 0;
export const useToast = create<ToastState>((set) => ({
  toasts: [],
  show: (message, type) => {
    const id = nextId++;
    set((s) => ({ toasts: [...s.toasts, { id, message, type }] }));
    setTimeout(() => set((s) => ({ toasts: s.toasts.filter(t => t.id !== id) })), 3000);
  },
}));

export default function Toast() {
  const toasts = useToast((s) => s.toasts);
  return (
    <div className="toast-container">
      {toasts.map((t) => (
        <div key={t.id} className={`toast ${t.type}`}>{t.message}</div>
      ))}
    </div>
  );
}
