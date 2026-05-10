import { Link } from 'react-router-dom';

export default function NotFoundPage() {
  return (
    <div className="not-found-page">
      <div className="not-found-content">
        <div className="not-found-icon">
          <span className="material-symbols-outlined" style={{ fontSize: 80, color: 'var(--accent)' }}>explore_off</span>
        </div>
        <h1 className="not-found-title">404</h1>
        <h2 className="not-found-subtitle">Page Not Found</h2>
        <p className="not-found-desc">
          The page you're looking for doesn't exist or has been moved.
        </p>
        <div className="not-found-actions">
          <Link to="/chat" className="btn-primary" style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '12px 24px', borderRadius: 8, textDecoration: 'none' }}>
            <span className="material-symbols-outlined">chat</span>
            Back to Chat
          </Link>
          <Link to="/dashboard" className="btn-secondary" style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '12px 24px', borderRadius: 8, textDecoration: 'none', marginLeft: 12 }}>
            <span className="material-symbols-outlined">dashboard</span>
            Dashboard
          </Link>
        </div>
      </div>
    </div>
  );
}

