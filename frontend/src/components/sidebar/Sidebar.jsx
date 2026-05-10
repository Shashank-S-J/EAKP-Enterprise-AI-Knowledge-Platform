import { useState, useRef, useEffect } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { useChatStore, useSidebarStore, useAuthStore } from '../../store';
import { conversations as convApi } from '../../api/client';

function useTheme() {
  const [theme, setThemeState] = useState(() => localStorage.getItem('eakp-theme') || 'dark');
  const toggleTheme = () => {
    const next = theme === 'dark' ? 'light' : 'dark';
    setThemeState(next);
    document.documentElement.setAttribute('data-theme', next);
    localStorage.setItem('eakp-theme', next);
  };
  return { theme, toggleTheme };
}

function groupConversations(convs) {
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const yesterday = new Date(today); yesterday.setDate(today.getDate() - 1);
  const weekAgo = new Date(today); weekAgo.setDate(today.getDate() - 7);
  const groups = { today: [], yesterday: [], week: [], older: [] };
  convs.forEach((c) => {
    const d = new Date(c.updatedAt || c.createdAt);
    if (d >= today) groups.today.push(c);
    else if (d >= yesterday) groups.yesterday.push(c);
    else if (d >= weekAgo) groups.week.push(c);
    else groups.older.push(c);
  });
  const result = [];
  if (groups.today.length)     result.push({ label: 'Today', items: groups.today });
  if (groups.yesterday.length) result.push({ label: 'Yesterday', items: groups.yesterday });
  if (groups.week.length)      result.push({ label: 'Previous 7 days', items: groups.week });
  if (groups.older.length)     result.push({ label: 'Older', items: groups.older });
  return result;
}

export default function Sidebar({ onSelectConv, onOpenDocs, docsCount }) {
  const navigate = useNavigate();
  const location = useLocation();
  const { conversations, activeConversationId, createConversation, deleteConversation, setConversations } = useChatStore();
  const { open, toggle, setOpen } = useSidebarStore();
  const { user, logout } = useAuthStore();
  const { theme, toggleTheme } = useTheme();
  const [search, setSearch] = useState('');
  const [deletingId, setDeletingId] = useState(null);
  const [renamingId, setRenamingId] = useState(null);
  const [renameText, setRenameText] = useState('');
  const renameRef = useRef(null);
  const searchRef = useRef(null);

  useEffect(() => {
    if (renamingId && renameRef.current) { renameRef.current.focus(); renameRef.current.select(); }
  }, [renamingId]);

  useEffect(() => {
    const handler = (e) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        if (!open) toggle();
        setTimeout(() => searchRef.current?.focus(), 100);
      }
    };
    globalThis.addEventListener('keydown', handler);
    return () => globalThis.removeEventListener('keydown', handler);
  }, [open]);

  const handleNew = async () => {
    try {
      const conv = await createConversation('New chat');
      onSelectConv(conv.id);
    } catch {
      // Silently fail — network error or auth expired
    }
  };

  const handleDelete = async (id) => { await deleteConversation(id); setDeletingId(null); };

  const startRename = (c) => { setRenamingId(c.id); setRenameText(c.title || ''); };

  const submitRename = async () => {
    if (!renameText.trim() || !renamingId) return;
    setConversations(conversations.map((c) => c.id === renamingId ? { ...c, title: renameText.trim() } : c));
    setRenamingId(null);
    try { await convApi.rename(renamingId, renameText.trim()); } catch {}
  };

  const handleSelectConv = (id) => {
    onSelectConv(id);
    if (globalThis.innerWidth <= 768) setOpen(false);
  };

  const filtered = search.trim()
    ? conversations.filter((c) => (c.title || '').toLowerCase().includes(search.toLowerCase()))
    : conversations;
  const groups = groupConversations(filtered);

  const initials = user?.fullName
    ? user.fullName.split(' ').map(n => n[0]).join('').toUpperCase().slice(0, 2)
    : user?.email?.slice(0, 2).toUpperCase() || '??';

  return (
    <>
      {!open && (
        <button className="sidebar-toggle" onClick={toggle} aria-label="Open sidebar">
          <span className="material-symbols-outlined">menu</span>
        </button>
      )}
      <aside className={`sidebar ${open ? '' : 'collapsed'}`}>
        {/* Header */}
        <div className="sidebar-header">
          <div className="sidebar-brand">
            <div className="sidebar-brand-icon">
              <span className="material-symbols-outlined filled">dataset</span>
            </div>
            <div className="sidebar-brand-text">
              <h2>EAKP</h2>
              <p>Enterprise AI Platform</p>
            </div>
          </div>
          <button className="sidebar-close" onClick={toggle} aria-label="Close sidebar">
            <span className="material-symbols-outlined">close</span>
          </button>
        </div>

        {/* New Chat Button */}
        <button className="new-chat-btn" onClick={handleNew}>
          <span className="material-symbols-outlined">auto_awesome</span>
          New Chat
          <span className="shortcut-hint">⌘N</span>
        </button>

        {/* Search */}
        <div className="sidebar-search">
          <span className="material-symbols-outlined search-icon" style={{fontSize:18}}>search</span>
          <input ref={searchRef} type="text" placeholder="Search conversations…" value={search}
            onChange={(e) => setSearch(e.target.value)} className="search-input" />
          {search && <button className="search-clear" onClick={() => setSearch('')}>
            <span className="material-symbols-outlined" style={{fontSize:16}}>close</span>
          </button>}
        </div>

        {/* Conversation List */}
        <div className="conv-list">
          {groups.length === 0 && (
            <p className="conv-empty">{search ? 'No matches found' : 'No conversations yet'}</p>
          )}
          {groups.map((g) => (
            <div key={g.label}>
              <div className="conv-group-label">{g.label}</div>
              {g.items.map((c) => (
                <div
                  key={c.id}
                  className={`conv-item ${c.id === activeConversationId ? 'active' : ''}`}
                  onClick={() => handleSelectConv(c.id)}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); handleSelectConv(c.id); } }}
                  role="button"
                  tabIndex={0}
                  aria-label={`Conversation: ${c.title || 'New chat'}`}
                >
                  <span className="material-symbols-outlined conv-icon" style={{fontSize:18}}>chat</span>
                  {renamingId === c.id ? (
                    <input ref={renameRef} className="rename-input" value={renameText}
                      onChange={(e) => setRenameText(e.target.value)}
                      onKeyDown={(e) => { if (e.key === 'Enter') submitRename(); if (e.key === 'Escape') setRenamingId(null); }}
                      onBlur={submitRename}
                      onClick={(e) => e.stopPropagation()} />
                  ) : (
                    <span className="conv-title" onDoubleClick={(e) => { e.stopPropagation(); startRename(c); }}>
                      {c.title || 'New chat'}
                    </span>
                  )}
                  {deletingId === c.id ? (
                    <div className="delete-confirm" onClick={(e) => e.stopPropagation()}>
                      <button className="confirm-yes" onClick={() => handleDelete(c.id)} title="Confirm delete">
                        <span className="material-symbols-outlined" style={{fontSize:16}}>check</span>
                      </button>
                      <button className="confirm-no" onClick={() => setDeletingId(null)} title="Cancel">
                        <span className="material-symbols-outlined" style={{fontSize:16}}>close</span>
                      </button>
                    </div>
                  ) : (
                    <button className="delete-btn" onClick={(e) => { e.stopPropagation(); setDeletingId(c.id); }}>
                      <span className="material-symbols-outlined" style={{fontSize:16}}>delete</span>
                    </button>
                  )}
                </div>
              ))}
            </div>
          ))}
        </div>

        {/* Navigation */}
        <div className="sidebar-nav">
          <button className={`sidebar-nav-item${location.pathname.startsWith('/chat') ? ' active' : ''}`} onClick={() => navigate('/chat')}>
            <span className="material-symbols-outlined">chat</span>
            Chat
            {conversations.length > 0 && (
              <span className="sidebar-nav-badge">{conversations.length}</span>
            )}
          </button>
          <button className={`sidebar-nav-item${location.pathname === '/dashboard' ? ' active' : ''}`} onClick={() => navigate('/dashboard')}>
            <span className="material-symbols-outlined">dashboard</span>
            Dashboard
          </button>
          <button className="sidebar-nav-item" onClick={onOpenDocs}>
            <span className="material-symbols-outlined">folder_open</span>
            Documents
            {docsCount > 0 && (
              <span className="sidebar-nav-badge">{docsCount}</span>
            )}
          </button>
          {user?.role === 'ADMIN' && (
            <button className={`sidebar-nav-item${location.pathname === '/admin' ? ' active' : ''}`} onClick={() => navigate('/admin')}>
              <span className="material-symbols-outlined">admin_panel_settings</span>
              Administration
            </button>
          )}
        </div>

        {/* Footer */}
        <div className="sidebar-footer">
          <div className="sidebar-footer-links">
            <button className="sidebar-footer-link" onClick={() => navigate('/settings')}>
              <span className="material-symbols-outlined" style={{fontSize:18}}>settings</span>
              Settings
            </button>
            <button className="sidebar-footer-link" onClick={toggleTheme}>
              <span className="material-symbols-outlined" style={{fontSize:18}}>{theme === 'dark' ? 'light_mode' : 'dark_mode'}</span>
              {theme === 'dark' ? 'Light Mode' : 'Dark Mode'}
            </button>
            <button className="sidebar-footer-link" onClick={() => navigate('/settings')}>
              <span className="material-symbols-outlined" style={{fontSize:18}}>help</span>
              Help & Support
            </button>
          </div>
          <div className="sidebar-user">
            <div className="user-avatar">{initials}</div>
            <div className="user-info">
              <div className="user-name">{user?.fullName || user?.email}</div>
              <div className="user-email">{user?.email}</div>
            </div>
            <button className="sidebar-logout" onClick={(e) => { e.stopPropagation(); logout(); }} title="Sign out">
              <span className="material-symbols-outlined" style={{fontSize:18}}>logout</span>
            </button>
          </div>
        </div>
      </aside>
    </>
  );
}
