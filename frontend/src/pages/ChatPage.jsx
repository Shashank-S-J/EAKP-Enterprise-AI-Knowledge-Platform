import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useChatStore, useSidebarStore, useAuthStore } from '../store';
import { documents as docsApi, conversations as convApi } from '../api/client';
import Sidebar from '../components/sidebar/Sidebar';
import ChatArea from '../components/chat/ChatArea';
import DocumentsPanel from '../components/documents/DocumentsPanel';
import BottomNav from '../components/shared/BottomNav';
 import KeyboardShortcuts from '../components/shared/KeyboardShortcuts';
import { exportConversation } from '../utils/exportConversation';

export default function ChatPage() {
  const { conversationId } = useParams();
  const navigate = useNavigate();
  const { loadConversations, loadMessages, setActiveConversation, activeConversationId, createConversation, conversations, setConversations } = useChatStore();
  const { open: sidebarOpen, toggle: toggleSidebar } = useSidebarStore();
  const { user } = useAuthStore();
  const [docsOpen, setDocsOpen] = useState(false);
  const [docsCount, setDocsCount] = useState(0);
  const [editingTitle, setEditingTitle] = useState(false);
  const [titleDraft, setTitleDraft] = useState('');
  const [notifPulse, setNotifPulse] = useState(true);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);

  useEffect(() => { loadConversations(); }, [loadConversations]);

  useEffect(() => {
    docsApi.list()
      .then((list) => setDocsCount(Array.isArray(list) ? list.length : 0))
      .catch(() => {});
  }, [docsOpen]);

  useEffect(() => {
    if (conversationId && conversationId !== activeConversationId) {
      loadMessages(conversationId);
    } else if (!conversationId) {
      setActiveConversation(null);
    }
  }, [conversationId, activeConversationId, loadMessages, setActiveConversation]);

  useEffect(() => {
    const handler = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.shiftKey && e.key === 'N') {
        e.preventDefault();
        createConversation('New chat').then((c) => navigate(`/chat/${c.id}`)).catch(() => {});
      }
      if ((e.metaKey || e.ctrlKey) && e.key === '/') {
        e.preventDefault();
        setShortcutsOpen((v) => !v);
      }
      if ((e.metaKey || e.ctrlKey) && e.shiftKey && e.key === 'E' && activeConversationId) {
        e.preventDefault();
        const state = useChatStore.getState();
        const conv = state.conversations.find((c) => c.id === activeConversationId);
        exportConversation(state.messages, conv?.title, 'markdown');
      }
      if (e.key === 'Escape' && docsOpen) {
        setDocsOpen(false);
      }
    };
    globalThis.addEventListener('keydown', handler);
    return () => globalThis.removeEventListener('keydown', handler);
  }, [docsOpen, activeConversationId]);

  const activeConv = conversations.find((c) => c.id === activeConversationId);
  const headerTitle = activeConv?.title || 'EAKP Chat';

  const startEditTitle = () => {
    if (!activeConversationId) return;
    setTitleDraft(headerTitle);
    setEditingTitle(true);
  };

  const submitTitle = async () => {
    setEditingTitle(false);
    const trimmed = titleDraft.trim();
    if (!trimmed || !activeConversationId || trimmed === headerTitle) return;
    setConversations(conversations.map((c) => c.id === activeConversationId ? { ...c, title: trimmed } : c));
    try { await convApi.rename(activeConversationId, trimmed); } catch {}
  };

  const initials = user?.fullName
    ? user.fullName.split(' ').map(n => n[0]).join('').toUpperCase().slice(0, 2)
    : user?.email?.slice(0, 2).toUpperCase() || '??';

  return (
    <div className="chat-page-root">
      {/* ── Top Navigation Bar ── */}
      <header className="top-nav-bar">
        <div className="top-nav-left">
          <button className="top-nav-btn" onClick={toggleSidebar} aria-label="Toggle sidebar">
            <span className="material-symbols-outlined">menu</span>
          </button>
          <div className="top-nav-brand">EAKP</div>
          <div className="top-nav-divider" />
          {editingTitle ? (
            <input
              className="top-nav-title-input"
              value={titleDraft}
              onChange={(e) => setTitleDraft(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') submitTitle(); if (e.key === 'Escape') setEditingTitle(false); }}
              onBlur={submitTitle}
              autoFocus
            />
          ) : (
            <div
              className="top-nav-title"
              onClick={startEditTitle}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); startEditTitle(); } }}
              role="button"
              tabIndex={activeConversationId ? 0 : -1}
              title={activeConversationId ? 'Click to rename' : ''}
            >
              <span className="top-nav-title-text">{headerTitle}</span>
              {activeConversationId && <span className="material-symbols-outlined top-nav-edit-icon">edit</span>}
            </div>
          )}
        </div>
        <div className="top-nav-right">
          <button
            className="top-nav-new-chat-btn"
            onClick={() => createConversation('New chat').then((c) => navigate(`/chat/${c.id}`)).catch(() => {})}
          >
            <span className="material-symbols-outlined" style={{ fontSize: 18 }}>add</span>
            New Chat
          </button>
          <div className="top-nav-actions-group">
            {activeConversationId && (
              <button
                className="top-nav-btn"
                onClick={() => {
                  const state = useChatStore.getState();
                  const conv = state.conversations.find((c) => c.id === activeConversationId);
                  exportConversation(state.messages, conv?.title, 'markdown');
                }}
                title="Export conversation (Ctrl+Shift+E)"
              >
                <span className="material-symbols-outlined">download</span>
              </button>
            )}
            <button
              className={`top-nav-btn ${docsOpen ? 'active' : ''}`}
              onClick={() => setDocsOpen(!docsOpen)}
              title="Toggle Knowledge Base"
            >
              <span className="material-symbols-outlined">library_books</span>
            </button>
          </div>
          <div className="top-nav-user-section">
            <button className="top-nav-btn notif-btn" onClick={() => setNotifPulse(false)} title="Notifications">
              <span className="material-symbols-outlined">notifications</span>
              {notifPulse && <span className="notif-dot" />}
            </button>
            <button className="top-nav-btn" onClick={() => navigate('/settings')} title="Settings">
              <span className="material-symbols-outlined">settings</span>
            </button>
            <div
              className="top-nav-avatar"
              onClick={() => navigate('/settings')}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') navigate('/settings'); }}
              role="button"
              tabIndex={0}
              title={user?.fullName || user?.email}
            >
              <span>{initials}</span>
            </div>
          </div>
        </div>
      </header>

      {/* ── 3-Column Layout ── */}
      <div className={`chat-layout ${sidebarOpen ? '' : 'sidebar-hidden'} ${docsOpen ? 'docs-visible' : ''}`}>
        <Sidebar
          onSelectConv={(id) => navigate(`/chat/${id}`)}
          onOpenDocs={() => setDocsOpen(!docsOpen)}
          docsCount={docsCount}
        />
        <main className="main-content">
          <ChatArea onNewConv={(id) => navigate(`/chat/${id}`)} />
        </main>
        <DocumentsPanel open={docsOpen} onClose={() => setDocsOpen(false)} />
      </div>
      <BottomNav onOpenDocs={() => setDocsOpen(!docsOpen)} />
      <KeyboardShortcuts open={shortcutsOpen} onClose={() => setShortcutsOpen(false)} />
    </div>
  );
}
