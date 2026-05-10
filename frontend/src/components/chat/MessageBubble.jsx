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

function formatTimestamp(iso) {
  if (!iso) return '';
  const d = new Date(iso);
  const now = new Date();
  const time = d.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' });
  if (d.toDateString() === now.toDateString()) return time;
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) + ', ' + time;
}

export default function MessageBubble({ message, onRetry, onEdit, conversationId }) {
  const { id, role, content, sources, faithfulness, createdAt } = message;
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
          <div className="message-content">
            {isUser ? <p>{content}</p> : (
              <ReactMarkdown remarkPlugins={[remarkGfm]} rehypePlugins={[rehypeHighlight]} components={mdComponents}>
                {content}
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
