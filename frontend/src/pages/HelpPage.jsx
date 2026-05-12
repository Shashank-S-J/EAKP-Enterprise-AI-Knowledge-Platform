import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Logo from '../components/shared/Logo';
import ThemeToggle from '../components/auth/ThemeToggle';
import Sidebar from '../components/sidebar/Sidebar';
import { useSidebarStore } from '../store';
import BottomNav from '../components/shared/BottomNav';

const FAQS = [
    {
        q: 'How do I upload a document?',
        a: 'Open the Knowledge panel from the chat (paperclip icon or "Knowledge" tab on mobile), then drag-and-drop or click to browse. PDF, DOCX, TXT, CSV, JSON, PPTX, XLSX, HTML and MD are supported (max 50 MB each).',
    },
    {
        q: 'Why is my answer slow or missing context?',
        a: 'Documents are vectorized in the background. Wait for the "Indexed" badge on each file before asking deep questions. You can monitor processing status in the Knowledge panel.',
    },
    {
        q: 'Can I edit a message after sending it?',
        a: 'Yes. Hover over your message and click the pencil icon. Editing creates a new branch from that point — the original is preserved in conversation history.',
    },
    {
        q: 'How do I export a conversation?',
        a: 'Press Ctrl + Shift + E (or ⌘ + Shift + E on macOS) while a chat is open. The conversation downloads as Markdown.',
    },
    {
        q: 'I forgot my password — what now?',
        a: 'On the login screen, click "Forgot password?". You will receive a reset link by email. OAuth (Google / GitHub) users manage credentials through their provider.',
    },
    {
        q: 'Is my data private?',
        a: 'All data is workspace-scoped. Only members of your workspace can access uploaded documents and conversation history. See the Privacy Policy for full details.',
    },
];

const SHORTCUTS = [
    { keys: ['Ctrl', 'K'], action: 'Open conversation search' },
    { keys: ['Ctrl', 'Shift', 'N'], action: 'Start a new chat' },
    { keys: ['Ctrl', '/'], action: 'Show keyboard shortcuts' },
    { keys: ['Ctrl', 'Shift', 'E'], action: 'Export current chat' },
    { keys: ['Esc'], action: 'Close panels / modals' },
];

export default function HelpPage() {
    const navigate = useNavigate();
    const { open: sidebarOpen, toggle: toggleSidebar } = useSidebarStore();
    const [openIdx, setOpenIdx] = useState(0);
    const [query, setQuery] = useState('');

    const filtered = query.trim()
        ? FAQS.filter(
            (f) =>
                f.q.toLowerCase().includes(query.toLowerCase()) ||
                f.a.toLowerCase().includes(query.toLowerCase())
        )
        : FAQS;

    return (
        <div className="app-layout">
            <Sidebar
                onSelectConv={(id) => navigate(`/chat/${id}`)}
                onOpenDocs={() => {}}
                docsCount={0}
            />
            <div className="main-content">
                <div className="chat-header">
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                        {!sidebarOpen && (
                            <button className="chat-header-btn" onClick={toggleSidebar} title="Open sidebar" aria-label="Open sidebar">
                                <span className="material-symbols-outlined">menu</span>
                            </button>
                        )}
                        <Logo size={26} />
                        <h2>Help &amp; Support</h2>
                    </div>
                    <div className="chat-header-actions">
                        <ThemeToggle />
                        <button className="chat-header-btn" onClick={() => navigate(-1)} title="Back" aria-label="Go back">
                            <span className="material-symbols-outlined">arrow_back</span>
                        </button>
                    </div>
                </div>

                <div className="help-page">
                    {/* Hero */}
                    <section className="help-hero">
                        <div className="help-hero-left">
                            <span className="help-eyebrow">Support center</span>
                            <h1>How can we help?</h1>
                            <p>
                                Find answers fast, learn keyboard shortcuts, or reach the team. Most questions are
                                covered below — search to jump directly to your topic.
                            </p>
                            <div className="help-search">
                                <span className="material-symbols-outlined">search</span>
                                <input
                                    type="text"
                                    placeholder="Search help articles…"
                                    value={query}
                                    onChange={(e) => setQuery(e.target.value)}
                                    aria-label="Search help"
                                />
                                {query && (
                                    <button onClick={() => setQuery('')} aria-label="Clear search">
                                        <span className="material-symbols-outlined">close</span>
                                    </button>
                                )}
                            </div>
                        </div>
                        <div className="help-hero-glyph" aria-hidden="true">
                            <Logo size={120} />
                        </div>
                    </section>

                    {/* Quick action tiles */}
                    <section className="help-tiles">
                        <button className="help-tile" onClick={() => navigate('/chat')}>
                            <span className="material-symbols-outlined">chat</span>
                            <div>
                                <strong>Start a new chat</strong>
                                <p>Ask anything about your indexed data.</p>
                            </div>
                        </button>
                        <button className="help-tile" onClick={() => navigate('/dashboard')}>
                            <span className="material-symbols-outlined">dashboard</span>
                            <div>
                                <strong>Open dashboard</strong>
                                <p>System metrics and recent activity.</p>
                            </div>
                        </button>
                        <button className="help-tile" onClick={() => navigate('/settings')}>
                            <span className="material-symbols-outlined">tune</span>
                            <div>
                                <strong>Workspace settings</strong>
                                <p>Models, chunking, and profile.</p>
                            </div>
                        </button>
                        <a className="help-tile" href="mailto:support@eakp.app">
                            <span className="material-symbols-outlined">mail</span>
                            <div>
                                <strong>Email support</strong>
                                <p>support@eakp.app — reply within 24h.</p>
                            </div>
                        </a>
                    </section>

                    {/* FAQ */}
                    <section className="help-section">
                        <header className="help-section-header">
                            <h2>Frequently asked</h2>
                            <span className="help-section-count">{filtered.length} {filtered.length === 1 ? 'article' : 'articles'}</span>
                        </header>

                        {filtered.length === 0 ? (
                            <div className="help-empty">
                                <span className="material-symbols-outlined">search_off</span>
                                <p>No articles match "{query}". Try a different keyword or email us.</p>
                            </div>
                        ) : (
                            <div className="help-faq-list">
                                {filtered.map((f, i) => {
                                    const isOpen = openIdx === i;
                                    return (
                                        <div key={f.q} className={`help-faq-item ${isOpen ? 'open' : ''}`}>
                                            <button
                                                className="help-faq-q"
                                                onClick={() => setOpenIdx(isOpen ? -1 : i)}
                                                aria-expanded={isOpen}
                                            >
                                                <span>{f.q}</span>
                                                <span className="material-symbols-outlined help-faq-chevron">
                          {isOpen ? 'remove' : 'add'}
                        </span>
                                            </button>
                                            <div className="help-faq-a" aria-hidden={!isOpen}>
                                                <p>{f.a}</p>
                                            </div>
                                        </div>
                                    );
                                })}
                            </div>
                        )}
                    </section>

                    {/* Shortcuts */}
                    <section className="help-section">
                        <header className="help-section-header">
                            <h2>Keyboard shortcuts</h2>
                        </header>
                        <div className="help-shortcuts">
                            {SHORTCUTS.map((s) => (
                                <div key={s.action} className="help-shortcut">
                                    <span className="help-shortcut-action">{s.action}</span>
                                    <span className="help-shortcut-keys">
                    {s.keys.map((k, i) => (
                        <kbd key={i}>{k}</kbd>
                    ))}
                  </span>
                                </div>
                            ))}
                        </div>
                    </section>

                    {/* Status / contact strip */}
                    <section className="help-status-strip">
                        <div className="help-status-pill">
                            <span className="help-status-dot" />
                            All systems operational
                        </div>
                        <p>
                            Still stuck? Reach the team at{' '}
                            <a href="mailto:support@eakp.app">support@eakp.app</a> or open an issue in your
                            workspace.
                        </p>
                    </section>
                </div>
            </div>
            <BottomNav onOpenDocs={() => {}} />
        </div>
    );
}