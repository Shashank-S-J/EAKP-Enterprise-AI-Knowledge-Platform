import { useNavigate, useLocation } from 'react-router-dom';
import { useAuthStore } from '../../store';

export default function BottomNav({ onOpenDocs }) {
  const navigate = useNavigate();
  const location = useLocation();
  const user = useAuthStore((s) => s.user);

  const tabs = [
    { icon: 'forum', label: 'Chat', path: '/chat', onClick: () => navigate('/chat') },
    { icon: 'dashboard', label: 'Dashboard', path: '/dashboard', onClick: () => navigate('/dashboard') },
    { icon: 'inventory_2', label: 'Knowledge', onClick: onOpenDocs },
    ...(user?.role === 'ADMIN' ? [{ icon: 'shield_person', label: 'Admin', path: '/admin', onClick: () => navigate('/admin') }] : []),
    { icon: 'person', label: 'Profile', path: '/settings', onClick: () => navigate('/settings') },
  ];

  return (
    <nav className="bottom-nav">
      {tabs.map((t) => {
        const active = t.path && location.pathname.startsWith(t.path);
        return (
          <button
            key={t.label}
            className={`bottom-nav-item${active ? ' active' : ''}`}
            onClick={t.onClick}
          >
            <span className="material-symbols-outlined">{t.icon}</span>
            <span className="bottom-nav-label">{t.label}</span>
          </button>
        );
      })}
    </nav>
  );
}

