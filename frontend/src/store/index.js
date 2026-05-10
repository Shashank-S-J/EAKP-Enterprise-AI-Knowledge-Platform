import { create } from 'zustand';
import { auth as authApi, conversations as convApi } from '../api/client';

// Cross-tab logout coordination
const logoutChannel = typeof BroadcastChannel !== 'undefined'
  ? new BroadcastChannel('eakp-auth')
  : null;

export const useAuthStore = create((set) => ({
  user: null,
  loading: true,
  setUser: (user) => set({ user, loading: false }),
  logout: async () => {
    // 1. Tell backend to blacklist tokens (also clears cookies internally)
    await authApi.logout();
    // 2. Clear all client-side state
    set({ user: null, loading: false });
    useChatStore.getState().reset();
    // 3. Notify other tabs
    logoutChannel?.postMessage('logout');
    // 4. Redirect to login
    globalThis.location.href = '/login';
  },
  checkAuth: async () => {
    try {
      const user = await authApi.me();
      set({ user, loading: false });
    } catch {
      set({ user: null, loading: false });
    }
  },
}));

// Listen for logout from other tabs
if (logoutChannel) {
  logoutChannel.onmessage = (e) => {
    if (e.data === 'logout') {
      useAuthStore.setState({ user: null, loading: false });
      useChatStore.getState().reset();
      globalThis.location.href = '/login';
    }
  };
}
export const useChatStore = create((set, get) => ({
  conversations: [],
  activeConversationId: null,
  messages: [],
  streaming: false,
  streamingContent: '',
  streamChunks: [],
  reset: () => set({ conversations: [], activeConversationId: null, messages: [], streaming: false, streamingContent: '', streamChunks: [] }),
  setConversations: (c) => set({ conversations: c }),
  setActiveConversation: (id) => set({ activeConversationId: id, messages: [], streamingContent: '', streamChunks: [] }),
  setMessages: (m) => set({ messages: m }),
  setStreaming: (s) => set({ streaming: s }),
  appendStreamToken: (token) => set((s) => ({
    streamingContent: s.streamingContent + token,
    streamChunks: [...s.streamChunks, { text: token, id: Date.now() + Math.random() }],
  })),
  clearStream: () => set({ streamingContent: '', streamChunks: [] }),
  loadConversations: async () => {
    try { const list = await convApi.list(); set({ conversations: list }); } catch {}
  },
  loadMessages: async (convId) => {
    try { const msgs = await convApi.messages(convId); set({ messages: msgs, activeConversationId: convId }); } catch {}
  },
  addMessage: (msg) => set((s) => ({ messages: [...s.messages, msg] })),
  createConversation: async (title) => {
    const conv = await convApi.create(title || 'New chat');
    set((s) => ({ conversations: [conv, ...s.conversations], activeConversationId: conv.id }));
    return conv;
  },
  deleteConversation: async (id) => {
    await convApi.delete(id);
    set((s) => ({
      conversations: s.conversations.filter((c) => c.id !== id),
      activeConversationId: s.activeConversationId === id ? null : s.activeConversationId,
      messages: s.activeConversationId === id ? [] : s.messages,
    }));
  },
  renameConversation: async (id, title) => {
    const prev = get().conversations;
    set((s) => ({ conversations: s.conversations.map((c) => c.id === id ? { ...c, title } : c) }));
    try { await convApi.rename(id, title); } catch { set({ conversations: prev }); }
  },
}));
export const useSidebarStore = create((set) => ({
  open: true,
  toggle: () => set((s) => ({ open: !s.open })),
  setOpen: (open) => set({ open }),
}));
