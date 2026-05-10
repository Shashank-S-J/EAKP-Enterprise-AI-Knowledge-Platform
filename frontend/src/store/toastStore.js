import { create } from 'zustand';

let toastId = 0;

export const useToastStore = create((set) => ({
  toasts: [],
  add: (message, type = 'info', duration = 4000) => {
    const id = ++toastId;
    set((s) => ({ toasts: [...s.toasts, { id, message, type }] }));
    if (duration > 0) setTimeout(() => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })), duration);
    return id;
  },
  remove: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),
  success: (msg) => useToastStore.getState().add(msg, 'success'),
  error: (msg) => useToastStore.getState().add(msg, 'error', 6000),
  info: (msg) => useToastStore.getState().add(msg, 'info'),
  warning: (msg) => useToastStore.getState().add(msg, 'warning', 5000),
}));

