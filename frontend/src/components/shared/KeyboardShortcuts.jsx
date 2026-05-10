import { useEffect } from 'react';

const SHORTCUTS = [
  { keys: ['Ctrl', 'Shift', 'N'], desc: 'New conversation' },
  { keys: ['Ctrl', 'Shift', 'E'], desc: 'Export conversation' },
  { keys: ['Enter'], desc: 'Send message' },
  { keys: ['Shift', 'Enter'], desc: 'New line in message' },
  { keys: ['Escape'], desc: 'Close panel / Cancel edit' },
  { keys: ['Ctrl', '/'], desc: 'Show keyboard shortcuts' },
];

export default function KeyboardShortcuts({ open, onClose }) {
  useEffect(() => {
    if (!open) return;
    const handler = (e) => { if (e.key === 'Escape') onClose(); };
    globalThis.addEventListener('keydown', handler);
    return () => globalThis.removeEventListener('keydown', handler);
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div className="modal-overlay" onClick={onClose} role="dialog" aria-modal="true" aria-label="Keyboard shortcuts">
      <div className="modal-content shortcuts-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2>
            <span className="material-symbols-outlined">keyboard</span>
            Keyboard Shortcuts
          </h2>
          <button className="modal-close" onClick={onClose} aria-label="Close">
            <span className="material-symbols-outlined">close</span>
          </button>
        </div>
        <div className="shortcuts-list">
          {SHORTCUTS.map(({ keys, desc }) => (
            <div key={desc} className="shortcut-row">
              <span className="shortcut-desc">{desc}</span>
              <div className="shortcut-keys">
                {keys.map((k) => (
                  <kbd key={k} className="kbd">{k}</kbd>
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

