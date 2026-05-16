import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeHighlight from 'rehype-highlight';
import { useState } from 'react';
import { conversations as convApi } from '../../api/client';

function CodeBlock({ children, className, ...props }) {
    const [copied, setCopied] = useState(false);
    const codeText = String(children).replace(/\n$/, '');
    const lang = className?.replace('language-', '') || '';

    const copyCode = () => {
        navigator.clipboard.writeText(codeText);
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
    };

    return (
        <div className="code-block-wrapper">
            <div className="code-block-header">
                <span className="code-lang">{lang}</span>
                <button className="copy-code-btn" onClick={copyCode} title="Copy code">
          <span className="material-symbols-outlined" style={{ fontSize: 12 }}>
            {copied ? 'check' : 'content_copy'}
          </span>
                    {copied ? ' Copied' : ' Copy'}
                </button>
            </div>
            <code className={className} {...props}>{children}</code>
        </div>
    );
}

// Normalize assistant content so paragraphs render reliably.
//
// LLMs frequently emit a single "\n" between sentences instead of the
// blank line markdown needs to start a new paragraph. The result is one
// dense wall of text. We insert a blank line between consecutive prose
// lines while leaving code fences, lists, headings, and blockquotes alone.
function normalizeAssistantMarkdown(text) {
    if (!text) return text;
    const parts = text.split(/(```[\s\S]*?```|~~~[\s\S]*?~~~)/g);
    return parts
        .map((part, i) => {
            // odd indices are fenced code blocks — keep as-is
            if (i % 2 === 1) return part;
            // Turn a lone "\n" between two non-empty lines into "\n\n",
            // unless the next line is a list item, heading, blockquote,
            // table row, or another blank line.
            return part.replace(
                /([^\n])\n(?!\n|[-*+]\s|\d+\.\s|#|>|\||\s*$)/g,
                '$1\n\n'
            );
        })
        .join('');
}

function formatTimestamp(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    const now = new Date();
    const time = d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
    if (d.toDateString() === now.toDateString()) return time;
    return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) + ', ' + time;
}

export default function MessageBubble({ message, onRetry, onEdit, conversationId }) {
    const { id, role, content, sources, faithfulness, createdAt, attachments } = message;
    const isUser = role === 'USER';
    const [copied, setCopied] = useState(false);
    const [feedback, setFeedback] = useState(null); // 'positive' | 'negative' | null

    const copyText = () => {
        navigator.clipboard.writeText(content);
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
    };

    const faithLevel = faithfulness != null
        ? faithfulness >= 0.8 ? 'high' : faithfulness >= 0.5 ? 'medium' : 'low'
        : null;

    const mdComponents = {
        pre: ({ children }) => <pre>{children}</pre>,
        code: ({ children, className, inline, ...props }) => {
            if (inline || !className) return <code className={className} {...props}>{children}</code>;
            return <CodeBlock className={className} {...props}>{children}</CodeBlock>;
        },
    };

    // Map filetype/extension to a Material Symbols icon (mirrors composer chips).
    const iconForExt = (ext) => {
        const m = {
            pdf: 'picture_as_pdf', docx: 'description', doc: 'description',
            txt: 'article', md: 'article',
            csv: 'table_view', xlsx: 'table_view', xls: 'table_view',
            json: 'data_object', html: 'code',
            pptx: 'slideshow', ppt: 'slideshow',
        };
        return m[(ext || '').toLowerCase()] || 'attach_file';
    };

    return (
        <div className={`message ${isUser ? 'user' : 'assistant'} message-enter`}>
            {/* Assistant avatar */}
            {!isUser && (
                <div className="message-avatar-float">
                    <span className="material-symbols-outlined filled" style={{ fontSize: 14 }}>psychology</span>
                </div>
            )}

            <div className={`message-bubble-wrap ${isUser ? 'bubble-right' : 'bubble-left'}`}>
                {/* Floating confidence badge for assistant */}
                {!isUser && faithLevel && (
                    <div className={`confidence-badge-float ${faithLevel}`}>
                        <span className="confidence-dot" />
                        <span className="confidence-text">
              {faithLevel === 'high' ? 'HIGH CONFIDENCE' : faithLevel === 'medium' ? 'MEDIUM CONFIDENCE' : 'LOW CONFIDENCE'}
            </span>
                    </div>
                )}

                <div className="message-bubble">
                    {/* User-attached documents (ChatGPT-style: pinned to the message
              that included them, even though follow-ups still see them via
              the conversation context). */}
                    {isUser && attachments && attachments.length > 0 && (
                        <div className="message-attachments" role="list" aria-label="Attached files">
                            {attachments.map((a) => {
                                const ext = (a.fileType || (a.filename || '').split('.').pop() || '').toLowerCase();
                                return (
                                    <div key={a.id} className={`message-attachment-chip ext-${ext}`} role="listitem" title={a.filename}>
                    <span className="material-symbols-outlined filled" style={{ fontSize: 14 }}>
                      {iconForExt(ext)}
                    </span>
                                        <span className="message-attachment-name">{a.filename}</span>
                                    </div>
                                );
                            })}
                        </div>
                    )}

                    <div className="message-content">
                        {isUser ? <p>{content}</p> : (
                            <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]} components={mdComponents}>
                                {normalizeAssistantMarkdown(content)}
                            </ReactMarkdown>
                        )}
                    </div>

                    {/* Source citations */}
                    {sources && sources.length > 0 && (
                        <div className="message-sources-section">
                            <div className="sources-header">
                                <span className="material-symbols-outlined" style={{ fontSize: 16 }}>menu_book</span>
                                <span className="sources-label">Sources ({sources.length})</span>
                            </div>
                            <div className="sources-list">
                                {sources.map((s, i) => (
                                    <button key={i} className="source-chip" title={s.snippet || s.source}>
                    <span className="material-symbols-outlined" style={{ fontSize: 14, color: i % 2 === 0 ? 'var(--tertiary)' : 'var(--accent)' }}>
                      {(s.source || '').endsWith('.pdf') ? 'description' : 'code'}
                    </span>
                                        <span>{s.source || `Source ${i + 1}`}</span>
                                    </button>
                                ))}
                            </div>
                        </div>
                    )}

                    {/* Faithfulness inline badge */}
                    {faithLevel && (
                        <div className={`faithfulness-badge ${faithLevel}`}>
              <span className="material-symbols-outlined" style={{ fontSize: 12 }}>
                {faithLevel === 'high' ? 'verified_user' : 'shield'}
              </span>
                            {Math.round(faithfulness * 100)}% grounded
                        </div>
                    )}

                    {/* Action bar */}
                    <div className="message-actions-bar">
                        <div className="actions-left">
                            {createdAt && <span className="msg-timestamp">{formatTimestamp(createdAt)}</span>}
                            {isUser && onEdit && (
                                <button onClick={() => onEdit(id, content)} className="action-btn" title="Edit message">
                                    <span className="material-symbols-outlined" style={{ fontSize: 14 }}>edit</span> Edit
                                </button>
                            )}
                            {!isUser && (
                                <button onClick={copyText} className="action-btn" title="Copy response">
                  <span className="material-symbols-outlined" style={{ fontSize: 14 }}>
                    {copied ? 'check' : 'content_copy'}
                  </span>
                                    {copied ? 'Copied' : 'Copy'}
                                </button>
                            )}
                            {!isUser && onRetry && (
                                <button onClick={() => onRetry(id)} className="action-btn" title="Regenerate">
                                    <span className="material-symbols-outlined" style={{ fontSize: 14 }}>refresh</span> Retry
                                </button>
                            )}
                        </div>
                        <div className="actions-right">
                            {!isUser && (
                                <>
                                    <button
                                        className={`feedback-btn ${feedback === 'positive' ? 'active' : ''}`}
                                        title="Good response"
                                        onClick={() => {
                                            const rating = feedback === 'positive' ? null : 'positive';
                                            setFeedback(rating);
                                            if (rating && conversationId) {
                                                convApi.feedback(conversationId, id, rating).catch(() => {});
                                            }
                                        }}
                                    >
                                        <span className="material-symbols-outlined" style={{ fontSize: 16 }}>thumb_up</span>
                                    </button>
                                    <button
                                        className={`feedback-btn ${feedback === 'negative' ? 'active' : ''}`}
                                        title="Bad response"
                                        onClick={() => {
                                            const rating = feedback === 'negative' ? null : 'negative';
                                            setFeedback(rating);
                                            if (rating && conversationId) {
                                                convApi.feedback(conversationId, id, rating).catch(() => {});
                                            }
                                        }}
                                    >
                                        <span className="material-symbols-outlined" style={{ fontSize: 16 }}>thumb_down</span>
                                    </button>
                                </>
                            )}
                        </div>
                    </div>
                </div>
            </div>

            {/* Timestamp below bubble */}
            {isUser && createdAt && (
                <div className="message-time-right">{formatTimestamp(createdAt)}</div>
            )}
        </div>
    );
}