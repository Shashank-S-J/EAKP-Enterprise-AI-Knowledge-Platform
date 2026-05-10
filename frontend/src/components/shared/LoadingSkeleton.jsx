/**
 * Reusable loading skeleton components for EAKP.
 * Used as placeholders while data is loading.
 */

export function ConversationSkeleton({ count = 5 }) {
  return (
    <div className="skeleton-list" role="status" aria-label="Loading conversations">
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="skeleton skeleton-card" style={{ animationDelay: `${i * 0.1}s` }} />
      ))}
    </div>
  );
}

export function MessageSkeleton({ count = 3 }) {
  return (
    <div role="status" aria-label="Loading messages">
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="skeleton-message" style={{ animationDelay: `${i * 0.15}s` }}>
          <div className="skeleton skeleton-avatar" />
          <div className="skeleton-message-content">
            <div className="skeleton skeleton-text long" />
            <div className="skeleton skeleton-text medium" />
            <div className="skeleton skeleton-text short" />
          </div>
        </div>
      ))}
    </div>
  );
}

export function DocumentSkeleton({ count = 4 }) {
  return (
    <div className="skeleton-list" role="status" aria-label="Loading documents">
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="skeleton skeleton-doc" style={{ animationDelay: `${i * 0.08}s` }} />
      ))}
    </div>
  );
}

export function UploadProgressRing({ progress = 0, filename = '' }) {
  const radius = 26;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - (progress / 100) * circumference;

  return (
    <div className="upload-progress-overlay" role="progressbar" aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100}>
      <div className="upload-progress-ring">
        <svg viewBox="0 0 64 64">
          <circle className="track" cx="32" cy="32" r={radius} />
          <circle
            className="fill"
            cx="32" cy="32" r={radius}
            strokeDasharray={circumference}
            strokeDashoffset={offset}
          />
        </svg>
      </div>
      <div className="upload-progress-text">{Math.round(progress)}%</div>
      {filename && <div className="upload-progress-filename" title={filename}>{filename}</div>}
    </div>
  );
}

export function StatCardSkeleton({ count = 4 }) {
  return (
    <div className="admin-stats-grid" role="status" aria-label="Loading statistics">
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="skeleton" style={{ height: 120, borderRadius: 12, animationDelay: `${i * 0.1}s` }} />
      ))}
    </div>
  );
}

