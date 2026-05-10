import { useState, useEffect } from 'react';

export default function ThemeToggle() {
  const [theme, setTheme] = useState(() => {
    return localStorage.getItem('eakp-theme') || 'dark';
  });

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('eakp-theme', theme);
  }, [theme]);

  return (
    <div className="theme-toggle">
      <button
        className={`theme-toggle-btn ${theme === 'dark' ? 'active' : ''}`}
        onClick={() => setTheme('dark')}
        aria-label="Dark mode"
      >
        <span className="material-symbols-outlined filled" style={{ fontSize: 16 }}>dark_mode</span>
      </button>
      <button
        className={`theme-toggle-btn ${theme === 'light' ? 'active' : ''}`}
        onClick={() => setTheme('light')}
        aria-label="Light mode"
      >
        <span className="material-symbols-outlined" style={{ fontSize: 16 }}>light_mode</span>
      </button>
    </div>
  );
}

