import { useEffect, useState } from 'react';

const GSI_SRC = 'https://accounts.google.com/gsi/client';

/**
 * Lazily inject the Google Identity Services script. Only the auth pages
 * (Login / Register) need this SDK, so we avoid loading ~50KB on every
 * route by deferring the script until those pages mount.
 *
 * Returns `ready: true` once `window.google.accounts.id` is available.
 * Safe to call from multiple components — the script tag is reused.
 */
export function useGoogleIdentity() {
    const [ready, setReady] = useState(
        () => typeof window !== 'undefined' && !!window.google?.accounts?.id
    );

    useEffect(() => {
        if (ready) return;
        if (typeof document === 'undefined') return;

        let cancelled = false;
        const markReady = () => { if (!cancelled) setReady(true); };

        // Re-check on mount in case the script loaded between render and effect.
        // Defer via microtask so we never call setState synchronously in an effect.
        if (window.google?.accounts?.id) {
            queueMicrotask(markReady);
            return () => { cancelled = true; };
        }

        const existing = document.querySelector(`script[src="${GSI_SRC}"]`);
        if (existing) {
            existing.addEventListener('load', markReady, { once: true });
            return () => {
                cancelled = true;
                existing.removeEventListener('load', markReady);
            };
        }

        const s = document.createElement('script');
        s.src = GSI_SRC;
        s.async = true;
        s.defer = true;
        s.onload = markReady;
        document.head.appendChild(s);
        return () => { cancelled = true; };
    }, [ready]);

    return ready;
}