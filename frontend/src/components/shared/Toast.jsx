import { useToastStore } from '../../store/toastStore';
import { useEffect, useState } from 'react';

const ICONS = { success: 'check_circle', error: 'error', info: 'info', warning: 'warning' };
const DURATIONS = { success: 4000, error: 6000, info: 4000, warning: 5000 };

function Toast({ toast, onRemove }) {
  const [progress, setProgress] = useState(100);
  const duration = DURATIONS[toast.type] || 4000;

  useEffect(() => {
    const start = Date.now();
    const tick = () => {
      const elapsed = Date.now() - start;
      const remaining = Math.max(0, 100 - (elapsed / duration) * 100);
      setProgress(remaining);
      if (remaining > 0) requestAnimationFrame(tick);
    };
    const raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [duration]);

  return (
    <div className={`toast toast-${toast.type}`}>
      <span className="material-symbols-outlined" style={{ fontSize: 16 }}>
        {ICONS[toast.type] || 'info'}
      </span>
      <span className="toast-msg">{toast.message}</span>
      <button
        className="toast-close"
        onClick={() => onRemove(toast.id)}
        aria-label="Dismiss"
      >
        <span className="material-symbols-outlined" style={{ fontSize: 14 }}>
          close
        </span>
      </button>
      <div className="toast-progress" style={{ width: `${progress}%` }} />
    </div>
  );
}

export default function ToastContainer() {
  const { toasts, remove } = useToastStore();
  if (!toasts.length) return null;

  return (
    <div className="toast-container" aria-live="polite">
      {toasts.map((t) => (
        <Toast key={t.id} toast={t} onRemove={remove} />
      ))}
    </div>
  );
}
