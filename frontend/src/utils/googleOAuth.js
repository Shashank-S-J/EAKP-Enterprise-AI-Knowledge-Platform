// Mobile-friendly Google OAuth helper.
//
// Google Identity Services' `prompt()` (One Tap / FedCM) only works on
// desktop browsers where the user has an active Google session, third-party
// cookies enabled, and FedCM support. On every mobile browser we tested it
// silently returns `isNotDisplayed: true` and there is no account picker.
//
// This module implements the OpenID Connect implicit flow with
// `response_type=id_token` so we get back an ID token in the URL fragment —
// exactly what our backend `/api/v1/auth/oauth/google/callback` expects.
// The same flow works on every browser (desktop and mobile).

const NONCE_KEY = 'eakp-google-nonce';
const WORKSPACE_KEY = 'eakp-oauth-workspace';

function randomNonce() {
    // 128 bits of entropy, base36 — works on every modern mobile browser.
    if (globalThis.crypto?.getRandomValues) {
        const a = new Uint32Array(4);
        globalThis.crypto.getRandomValues(a);
        return Array.from(a, (n) => n.toString(36)).join('');
    }
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

/**
 * Redirect the browser to Google's OAuth consent screen, asking for an
 * OpenID Connect ID token. After the user authorizes, Google sends them
 * back to `redirectPath` (under the current origin) with the ID token in
 * the URL fragment (`#id_token=...`).
 *
 * @param {string} redirectPath - Internal path Google redirects back to,
 *                                e.g. '/login?oauth=google'. Must be
 *                                registered as an authorized redirect URI
 *                                in the Google Cloud console.
 * @param {string} [workspaceName] - Optional workspace name (for register).
 *                                   Stashed in sessionStorage so we can
 *                                   pass it to the backend after redirect.
 */
export function redirectToGoogleAuth(redirectPath, workspaceName) {
    const clientId = import.meta.env.VITE_GOOGLE_CLIENT_ID || '';
    if (!clientId) {
        throw new Error('Google OAuth is not configured.');
    }
    const nonce = randomNonce();
    sessionStorage.setItem(NONCE_KEY, nonce);
    if (workspaceName) {
        sessionStorage.setItem(WORKSPACE_KEY, workspaceName);
    } else {
        sessionStorage.removeItem(WORKSPACE_KEY);
    }
    const redirect = globalThis.location.origin + redirectPath;
    const params = new URLSearchParams({
        client_id: clientId,
        redirect_uri: redirect,
        response_type: 'id_token',
        scope: 'openid email profile',
        nonce,
        prompt: 'select_account',
    });
    globalThis.location.href = `https://accounts.google.com/o/oauth2/v2/auth?${params.toString()}`;
}

/**
 * Pull a Google ID token out of the URL fragment if Google just sent the
 * user back from the consent screen. Returns null when there's nothing to
 * consume. Also cleans the fragment off the URL so a refresh doesn't
 * replay the (already-spent) ID token.
 */
export function consumeGoogleIdTokenFromUrl() {
    if (typeof globalThis.location === 'undefined') return null;
    const hash = globalThis.location.hash || '';
    if (!hash || hash.length < 2) return null;
    const params = new URLSearchParams(hash.slice(1));
    const idToken = params.get('id_token');
    if (!idToken) return null;
    const error = params.get('error');
    if (error) {
        // Clean the fragment so a refresh doesn't replay the error.
        globalThis.history.replaceState({}, '', globalThis.location.pathname);
        throw new Error(error);
    }
    const workspace = sessionStorage.getItem(WORKSPACE_KEY) || '';
    sessionStorage.removeItem(NONCE_KEY);
    sessionStorage.removeItem(WORKSPACE_KEY);
    // Strip the fragment.
    globalThis.history.replaceState({}, '', globalThis.location.pathname);
    return { idToken, workspaceName: workspace };
}

/**
 * Detect environments where Google One Tap is known to be unreliable.
 * Used to skip the `prompt()` step entirely on mobile and jump straight
 * to redirect — avoids the "Google sign-in was dismissed" flash.
 */
export function shouldSkipGoogleOneTap() {
    if (typeof navigator === 'undefined') return false;
    const ua = navigator.userAgent || '';
    // Coarse pointer = touch-first device. Combined with mobile UA, this is
    // the One Tap dead-zone.
    const isTouch = globalThis.matchMedia?.('(pointer: coarse)')?.matches;
    const isMobileUa = /Android|iPhone|iPad|iPod|Mobile|Opera Mini|IEMobile/i.test(ua);
    // In-app webviews (FB, IG, Twitter, LinkedIn, etc.) — One Tap never works.
    const isWebview = /(FBAN|FBAV|Instagram|Twitter|Line|LinkedInApp|MicroMessenger)/i.test(ua);
    return Boolean(isWebview || (isTouch && isMobileUa));
}