import { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';

/**
 * Wraps page content with a fade/slide entrance animation on route change.
 */
export default function PageTransition({ children }) {
  const location = useLocation();
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    setVisible(false);
    const t = requestAnimationFrame(() => setVisible(true));
    return () => cancelAnimationFrame(t);
  }, [location.pathname]);

  return (
    <div className={`page-transition ${visible ? 'page-visible' : ''}`}>
      {children}
    </div>
  );
}

