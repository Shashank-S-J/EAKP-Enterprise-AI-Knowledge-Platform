import { useState, useRef, useEffect, useCallback } from 'react';
import { useChatStore } from '../../store';
import { useToastStore } from '../../store/toastStore';
import { streamChat, documents as docsApi } from '../../api/client';
import { useNetworkStatus } from '../../hooks/useNetworkStatus';
import { validateBatch } from '../../utils/fileValidation';
import { sanitizeInput } from '../../utils/sanitize';
import MessageBubble from './MessageBubble';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';

const SUGGESTIONS = [
    { icon: 'lightbulb', color: 'var(--tertiary)', title: 'Analyze Trends', desc: 'Compare operational costs across periods.' },
    { icon: 'description', color: 'var(--accent)', title: 'Summarize Document', desc: 'Extract key points from uploaded docs.' },
    { icon: 'bar_chart', color: 'var(--secondary)', title: 'Generate Insights', desc: 'Visualize data across regions.' },
    { icon: 'code', color: 'var(--danger)', title: 'Audit Query', desc: 'Review logic for data anomalies.' },
];

export default function ChatArea({ onNewConv }) {
    const {
        messages, activeConversationId, streaming, streamingContent,
        setStreaming, appendStreamToken, clearStream, addMessage, createConversation,
        loadConversations, setMessages
    } = useChatStore();
    const toast = useToastStore();
    const online = useNetworkStatus();
    const [input, setInput] = useState('');
    const [files, setFiles] = useState([]);
    const [uploading, setUploading] = useState(false);
    const [thinking, setThinking] = useState(false);
    const [editingId, setEditingId] = useState(null);
    const [editText, setEditText] = useState('');
    const [sending, setSending] = useState(false);
    const [atBottom, setAtBottom] = useState(true);
    const messagesEndRef = useRef(null);
    const messagesAreaRef = useRef(null);
    const textareaRef = useRef(null);
    const editRef = useRef(null);
    const abortRef = useRef(null);
    const fileInputRef = useRef(null);

    // Track whether the user is near the bottom of the scroll area.
    // Claude-style: only auto-stick to bottom if the user hasn't scrolled up.
    const handleScroll = useCallback(() => {
        const el = messagesAreaRef.current;
        if (!el) return;
        const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
        setAtBottom(distance < 80);
    }, []);

    const scrollToBottom = useCallback((behavior = 'smooth') => {
        const el = messagesAreaRef.current;
        if (!el) return;
        el.scrollTo({ top: el.scrollHeight, behavior });
        setAtBottom(true);
    }, []);

    // Stick to bottom only when user is already at bottom; new user message always scrolls.
    useEffect(() => {
        if (atBottom) scrollToBottom(streaming ? 'auto' : 'smooth');
    }, [messages, streamingContent, thinking, atBottom, scrollToBottom, streaming]);

    useEffect(() => { if (textareaRef.current) { textareaRef.current.style.height = 'auto'; textareaRef.current.style.height = Math.min(textareaRef.current.scrollHeight, 200) + 'px'; } }, [input]);
    useEffect(() => { setTimeout(() => textareaRef.current?.focus(), 100); }, [activeConversationId]);
    useEffect(() => { if (editingId && editRef.current) { editRef.current.focus(); editRef.current.selectionStart = editRef.current.value.length; } }, [editingId]);

    const sendMessage = useCallback(async (text) => {
        const msg = sanitizeInput(text || input.trim(), 10000);
        if (!msg || streaming || sending) return;
        if (!online) { toast.error('You are offline. Please check your connection.'); return; }
        setSending(true);
        let convId = activeConversationId;

        if (files.length > 0) {
            setUploading(true);
            for (const f of files) { try { await docsApi.upload(f); } catch { toast.error(`Upload failed: ${f.name}`); } }
            setFiles([]); setUploading(false);
        }

        if (!convId) {
            try {
                // Auto-generate title from first message (first 60 chars)
                const autoTitle = msg.length > 60 ? msg.slice(0, 57) + '...' : msg;
                const conv = await createConversation(autoTitle);
                convId = conv.id; onNewConv(convId);
            }
            catch { toast.error('Failed to create conversation'); setSending(false); return; }
        }

        addMessage({ id: Date.now().toString(), role: 'USER', content: msg, sources: [], createdAt: new Date().toISOString() });
        setInput(''); setSending(false); setThinking(true); setStreaming(true); clearStream();

        let receivedFirstToken = false;
        abortRef.current = streamChat(convId, msg,
            (token) => { if (!receivedFirstToken) { receivedFirstToken = true; setThinking(false); } appendStreamToken(token); },
            () => {
                setThinking(false);
                const finalContent = useChatStore.getState().streamingContent;
                if (finalContent) addMessage({ id: (Date.now() + 1).toString(), role: 'ASSISTANT', content: finalContent, sources: [], createdAt: new Date().toISOString() });
                clearStream(); setStreaming(false); loadConversations();
            },
            (err) => {
                setThinking(false);
                addMessage({ id: (Date.now() + 1).toString(), role: 'ASSISTANT', content: '', _error: err, sources: [], createdAt: new Date().toISOString() });
                clearStream(); setStreaming(false);
            }
        );
    }, [input, streaming, activeConversationId, files, sending, online, toast, createConversation, addMessage, setStreaming, appendStreamToken, clearStream, loadConversations, onNewConv]);

    const handleKeyDown = (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); } };

    const stopStreaming = () => {
        if (abortRef.current) abortRef.current();
        setThinking(false);
        const finalContent = useChatStore.getState().streamingContent;
        if (finalContent) addMessage({ id: (Date.now() + 1).toString(), role: 'ASSISTANT', content: finalContent + ' [stopped]', sources: [], createdAt: new Date().toISOString() });
        clearStream(); setStreaming(false);
    };

    const handleEdit = (msgId, content) => { if (streaming) return; setEditingId(msgId); setEditText(content); };
    const submitEdit = () => {
        if (!editText.trim() || streaming) return;
        const idx = messages.findIndex((m) => m.id === editingId);
        if (idx === -1) return;
        setMessages(messages.slice(0, idx)); setEditingId(null); sendMessage(editText.trim()); setEditText('');
    };
    const cancelEdit = () => { setEditingId(null); setEditText(''); };

    const handleRetry = (assistantMsgId) => {
        if (streaming) return;
        const idx = messages.findIndex((m) => m.id === assistantMsgId);
        if (idx <= 0) return;
        const prevUserMsg = messages[idx - 1];
        if (prevUserMsg.role !== 'USER') return;
        setMessages(messages.slice(0, idx)); sendMessage(prevUserMsg.content);
    };

    const handleRetryError = (errMsgId) => {
        const idx = messages.findIndex((m) => m.id === errMsgId);
        if (idx <= 0) return;
        const prevUserMsg = messages[idx - 1];
        setMessages(messages.slice(0, idx)); sendMessage(prevUserMsg?.role === 'USER' ? prevUserMsg.content : '');
    };

    const addFiles = (newFiles) => {
        const allFiles = [...files, ...Array.from(newFiles)];
        const { valid, errors } = validateBatch(allFiles);
        if (!valid) {
            errors.forEach(err => toast.error(err));
            return;
        }
        setFiles(allFiles);
    };
    const removeFile = (idx) => setFiles((prev) => prev.filter((_, i) => i !== idx));

    const isEmpty = messages.length === 0 && !activeConversationId;

    return (
        <div className="chat-container">
            <div className="messages-area" ref={messagesAreaRef} onScroll={handleScroll}>
                <div className="chat-ambient" />
                {isEmpty ? (
                    <div className="empty-state">
                        <h2>What would you like to know?</h2>
                        <div className="suggestions">
                            {SUGGESTIONS.map((s) => (
                                <div key={s.title} className="suggestion-card" role="button" tabIndex={0}
                                     onClick={() => { setInput(s.title + ': ' + s.desc); setTimeout(() => textareaRef.current?.focus(), 50); }}
                                     onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setInput(s.title + ': ' + s.desc); setTimeout(() => textareaRef.current?.focus(), 50); } }}
                                >
                                    <span className="material-symbols-outlined suggestion-icon" style={{color: s.color}}>{s.icon}</span>
                                    <div>
                                        <div className="suggestion-title">{s.title}</div>
                                        <div className="suggestion-desc">{s.desc}</div>
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                ) : (
                    <>
                        {messages.map((m) => {
                            if (editingId === m.id) {
                                return (
                                    <div key={m.id} className="message user message-editing">
                                        <div className="edit-message-card">
                      <textarea
                          ref={editRef}
                          className="edit-textarea"
                          value={editText}
                          onChange={(e) => {
                              setEditText(e.target.value);
                              e.target.style.height = 'auto';
                              e.target.style.height = Math.min(e.target.scrollHeight, 280) + 'px';
                          }}
                          onKeyDown={(e) => {
                              if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); submitEdit(); }
                              if (e.key === 'Escape') cancelEdit();
                          }}
                          rows={1}
                          aria-label="Edit your message"
                      />
                                            <div className="edit-actions">
                                                <span className="edit-hint">Editing creates a new branch from this point.</span>
                                                <div className="edit-actions-buttons">
                                                    <button className="btn-edit-cancel" type="button" onClick={cancelEdit}>Cancel</button>
                                                    <button className="btn-edit-save" type="button" onClick={submitEdit} disabled={!editText.trim()}>
                                                        <span className="material-symbols-outlined" style={{ fontSize: 14 }}>send</span>
                                                        Send
                                                    </button>
                                                </div>
                                            </div>
                                        </div>
                                    </div>
                                );
                            }
                            if (m._error) {
                                return (
                                    <div key={m.id} className="message assistant">
                                        <div className="message-meta">
                                            <div className="message-avatar" style={{background:'var(--danger)'}}>
                                                <span className="material-symbols-outlined" style={{fontSize:14,color:'#690005'}}>warning</span>
                                            </div>
                                            <span className="role-label">EAKP</span>
                                        </div>
                                        <div className="message-bubble">
                                            <div className="error-retry">
                                                <span>Failed to get response: {m._error}</span>
                                                <button onClick={() => handleRetryError(m.id)} className="action-btn">
                                                    <span className="material-symbols-outlined" style={{fontSize:14}}>refresh</span> Retry
                                                </button>
                                            </div>
                                        </div>
                                    </div>
                                );
                            }
                            return <MessageBubble key={m.id} message={m} onEdit={handleEdit} onRetry={handleRetry} conversationId={activeConversationId} />;
                        })}

                        {/* Thinking Orb */}
                        {thinking && !streamingContent && (
                            <div className="thinking-orb-container">
                                <div className="thinking-orb">
                                    <div className="orb-glow" />
                                    <div className="orb-inner" />
                                    <span className="material-symbols-outlined">graphic_eq</span>
                                </div>
                            </div>
                        )}

                        {/* Streaming content */}
                        {streaming && streamingContent && (
                            <div className="message assistant" aria-live="polite" aria-atomic="false">
                                <div className="message-meta">
                                    <div className="message-avatar">
                                        <span className="material-symbols-outlined filled" style={{fontSize:14}}>smart_toy</span>
                                    </div>
                                    <span className="role-label">EAKP Core</span>
                                </div>
                                <div className="message-bubble">
                                    <div className="message-content streaming-cursor">
                                        <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]}>{streamingContent}</ReactMarkdown>
                                    </div>
                                </div>
                            </div>
                        )}
                    </>
                )}
                <div ref={messagesEndRef} />
            </div>

            {/* Floating scroll-to-bottom button (Claude-style) */}
            {!atBottom && messages.length > 0 && (
                <button
                    type="button"
                    className="scroll-to-bottom-btn"
                    onClick={() => scrollToBottom('smooth')}
                    title="Scroll to latest"
                    aria-label="Scroll to latest message"
                >
                    <span className="material-symbols-outlined">arrow_downward</span>
                </button>
            )}

            {/* Input Area */}
            <div className="input-area">
                {streaming && (
                    <div className="input-status-banner">
                        <span className="material-symbols-outlined">sync</span>
                        EAKP is generating a response…
                        <button className="action-btn" onClick={stopStreaming} style={{ marginLeft: 8, padding: '2px 8px' }}>
                            <span className="material-symbols-outlined" style={{ fontSize: 14 }}>stop</span> Stop
                        </button>
                    </div>
                )}
                {files.length > 0 && (
                    <div className="composer-attachments" role="list" aria-label="Attached files">
                        {files.map((f, i) => {
                            const ext = (f.name.split('.').pop() || '').toLowerCase();
                            const iconMap = {
                                pdf: 'picture_as_pdf', docx: 'description', doc: 'description',
                                txt: 'article', md: 'article',
                                csv: 'table_view', xlsx: 'table_view', xls: 'table_view',
                                json: 'data_object', html: 'code',
                                pptx: 'slideshow', ppt: 'slideshow',
                                png: 'image', jpg: 'image', jpeg: 'image', gif: 'image', webp: 'image', svg: 'image',
                            };
                            const icon = iconMap[ext] || 'attach_file';
                            const sizeKb = f.size / 1024;
                            const sizeLabel = sizeKb >= 1024 ? `${(sizeKb / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(sizeKb))} KB`;
                            return (
                                <div key={i} className={`composer-attachment-card ext-${ext}`} role="listitem">
                                    <div className="composer-attachment-icon">
                                        <span className="material-symbols-outlined filled">{icon}</span>
                                    </div>
                                    <div className="composer-attachment-meta">
                                        <div className="composer-attachment-name" title={f.name}>{f.name}</div>
                                        <div className="composer-attachment-sub">
                                            <span className="composer-attachment-ext">{ext.toUpperCase() || 'FILE'}</span>
                                            <span className="composer-attachment-dot" aria-hidden="true">•</span>
                                            <span className="composer-attachment-size">{sizeLabel}</span>
                                        </div>
                                    </div>
                                    <button
                                        type="button"
                                        className="composer-attachment-remove"
                                        onClick={() => removeFile(i)}
                                        aria-label={`Remove ${f.name}`}
                                        title="Remove"
                                    >
                                        <span className="material-symbols-outlined">close</span>
                                    </button>
                                </div>
                            );
                        })}
                    </div>
                )}
                <div className="input-wrapper"
                     onDragOver={(e) => { e.preventDefault(); e.currentTarget.classList.add('drag-active'); }}
                     onDragLeave={(e) => { e.currentTarget.classList.remove('drag-active'); }}
                     onDrop={(e) => { e.preventDefault(); e.currentTarget.classList.remove('drag-active'); if (e.dataTransfer.files.length) addFiles(e.dataTransfer.files); }}
                >
                    {/* Drag overlay */}
                    <div className="input-drag-overlay">
                        <div className="input-drag-content">
                            <span className="material-symbols-outlined">cloud_upload</span>
                            <span>Drop files to attach</span>
                        </div>
                    </div>
                    <input type="file" ref={fileInputRef} style={{ display: 'none' }} multiple
                           accept=".pdf,.docx,.doc,.txt,.pptx,.xlsx,.csv,.html,.md"
                           onChange={(e) => { if (e.target.files.length) addFiles(e.target.files); e.target.value = ''; }} />
                    <button className="input-btn" onClick={() => fileInputRef.current?.click()} title="Attach documents" disabled={streaming}>
                        <span className="material-symbols-outlined">attach_file</span>
                    </button>
                    <textarea
                        ref={textareaRef} value={input} onChange={(e) => setInput(e.target.value)}
                        onKeyDown={handleKeyDown}
                        placeholder={online ? "Ask anything about your data..." : "You're offline..."}
                        aria-label="Chat message input"
                        rows={1} maxLength={10000} disabled={uploading || streaming || !online}
                    />
                    <div className="input-actions">
                        {streaming ? (
                            <button className="send-btn" onClick={stopStreaming} title="Stop generating">
                                <span className="material-symbols-outlined filled">stop</span>
                            </button>
                        ) : (
                            <button className="send-btn" onClick={() => sendMessage()} disabled={(!input.trim() && !files.length) || sending} title="Send message">
                                <span className="material-symbols-outlined filled">send</span>
                            </button>
                        )}
                    </div>
                </div>
                <div className="input-disclaimer">
                    {input.length > 8000 && (
                        <span className="input-char-count" style={{ color: input.length > 9500 ? 'var(--danger)' : 'var(--warning)', marginRight: 12 }}>
              {input.length.toLocaleString()} / 10,000
            </span>
                    )}
                    EAKP AI may produce inaccurate information about people, places, or facts.
                </div>
            </div>
        </div>
    );
}
