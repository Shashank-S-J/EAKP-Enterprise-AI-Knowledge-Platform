import { useEffect, useState, useRef } from 'react';
import { useLocation } from 'react-router-dom';

/**
 * Wraps page content with a refined enter animation on every route change.
 * Uses a brief loading shimmer on the very first paint so navigations never
 * feel like a hard jump.
 */
export default function PageTransition({ children }) {
    const location = useLocation();
    const [phase, setPhase] = useState('enter'); // 'enter' → 'visible'
    const prevPath = useRef(location.pathname);

    useEffect(() => {
        if (prevPath.current === location.pathname) return;
        prevPath.current = location.pathname;
        setPhase('enter');
        // First rAF: ensure 'enter' is painted, second rAF: switch to 'visible'
        let raf2;
        const raf1 = requestAnimationFrame(() => {
            raf2 = requestAnimationFrame(() => setPhase('visible'));
        });
        return () => {
            cancelAnimationFrame(raf1);
            if (raf2) cancelAnimationFrame(raf2);
        };
    }, [location.pathname]);

    // Trigger initial enter on mount
    useEffect(() => {
        const t = requestAnimationFrame(() => setPhase('visible'));
        return () => cancelAnimationFrame(t);
    }, []);

    return (
        <div
            className={`page-transition ${phase === 'visible' ? 'page-visible' : ''}`}
            data-route={location.pathname}
        >
            {/* Top progress shimmer — fades out as the page settles */}
            <span className="page-progress-bar" aria-hidden="true" />
            {children}
        </div>
    );
}
