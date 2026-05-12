import { useEffect, useRef } from 'react';
import { useToastStore } from '../store/toastStore';
import { getAccessToken } from '../utils/cookies';

/**
 * Monitors JWT access token expiry and warns the user 2 minutes before.
 * Prevents silent redirects that lose user's in-progress work.
 */
export function useSessionExpiry() {
    const warned = useRef(false);
    const toast = useToastStore();

    useEffect(() => {
        const check = () => {
            const token = getAccessToken();
            if (!token) return;

            try {
                // Decode JWT payload (base64url)
                const payload = JSON.parse(atob(token.split('.')[1].replaceAll(/-/g, '+').replaceAll(/_/g, '/')));
                const expiresAt = payload.exp * 1000;
                const timeLeft = expiresAt - Date.now();

                // Warn 2 minutes before expiry
                if (timeLeft > 0 && timeLeft < 120_000 && !warned.current) {
                    warned.current = true;
                    toast.warning('Your session will expire soon. Please save your work.');
                }

                // Reset warning flag when a new token is issued
                if (timeLeft > 120_000) {
                    warned.current = false;
                }
            } catch {
                // Invalid token format — ignore
            }
        };

        const interval = setInterval(check, 30_000);
        return () => clearInterval(interval);
        // eslint-disable-next-line react-hooks/exhaustive-deps -- toast is store action; stable reference
    }, []);
}