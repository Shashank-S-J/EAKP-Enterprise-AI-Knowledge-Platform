/**
 * Cookie utility module for EAKP.
 *
 * Two categories of cookies:
 *  1. **Strictly Necessary** – authentication tokens. These are set without
 *     asking the user because the app cannot function without them (GDPR Art 6(1)(f),
 *     ePrivacy Directive Art 5(3) exemption).
 *  2. **Functional / Analytics** – preferences, sidebar state, theme, etc.
 *     These require user consent before being stored.
 *
 * Access tokens are stored as session cookies (expire when browser closes or
 * when the token itself expires).  Refresh tokens have a longer max-age.
 *
 * NOTE: For true httpOnly protection the tokens should be set by the backend
 * via Set-Cookie headers. This client-side approach is a pragmatic fallback
 * when the backend only returns tokens in JSON bodies.
 */

// ─── Low-level helpers ──────────────────────────────────────────────────────

function setCookie(name, value, { maxAge, path = '/', sameSite = 'Strict', secure = location.protocol === 'https:' } = {}) {
  let cookie = `${encodeURIComponent(name)}=${encodeURIComponent(value)}; path=${path}; SameSite=${sameSite}`;
  if (maxAge != null) cookie += `; max-age=${maxAge}`;
  if (secure) cookie += '; Secure';
  document.cookie = cookie;
}

function getCookie(name) {
  const match = document.cookie.match(new RegExp(`(?:^|; )${encodeURIComponent(name).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}

function deleteCookie(name, path = '/') {
  document.cookie = `${encodeURIComponent(name)}=; path=${path}; max-age=0`;
}

// ─── Strictly-necessary auth cookies (no consent needed) ────────────────────

const ACCESS_TOKEN_KEY  = 'eakp_at';
const REFRESH_TOKEN_KEY = 'eakp_rt';

/** Store access token – session cookie (or max-age matching JWT expiry). */
export function setAccessToken(token, expiresAt) {
  const maxAge = expiresAt ? Math.max(0, Math.floor((expiresAt - Date.now()) / 1000)) : undefined;
  setCookie(ACCESS_TOKEN_KEY, token, { maxAge });
}

export function getAccessToken() {
  return getCookie(ACCESS_TOKEN_KEY);
}

/** Store refresh token – 7-day max-age by default. */
export function setRefreshToken(token, maxAgeSec = 7 * 24 * 3600) {
  setCookie(REFRESH_TOKEN_KEY, token, { maxAge: maxAgeSec });
}

export function getRefreshToken() {
  return getCookie(REFRESH_TOKEN_KEY);
}

export function clearAuthTokens() {
  deleteCookie(ACCESS_TOKEN_KEY);
  deleteCookie(REFRESH_TOKEN_KEY);
}

// ─── Consent management ─────────────────────────────────────────────────────

const CONSENT_KEY = 'eakp_cookie_consent';

/** Returns 'accepted' | null.  Rejection is session-only (not persisted). */
export function getConsent() {
  return getCookie(CONSENT_KEY);
}

/**
 * Persist acceptance for 365 days.
 * Only call with 'accepted' — rejection should NOT be persisted
 * (use sessionStorage in the UI component instead).
 */
export function setConsent(value) {
  if (value === 'accepted') {
    setCookie(CONSENT_KEY, value, { maxAge: 365 * 24 * 3600 });
  }
}

// ─── Functional cookies (need consent) ──────────────────────────────────────

export function setFunctionalCookie(name, value, maxAgeSec = 30 * 24 * 3600) {
  if (getConsent() !== 'accepted') return false;
  setCookie(`eakp_fn_${name}`, value, { maxAge: maxAgeSec });
  return true;
}

export function getFunctionalCookie(name) {
  return getCookie(`eakp_fn_${name}`);
}

export function deleteFunctionalCookie(name) {
  deleteCookie(`eakp_fn_${name}`);
}

/** Remove all functional cookies when user revokes consent. */
export function clearFunctionalCookies() {
  // We can only clear the ones we know about
  const prefixes = ['eakp_fn_'];
  document.cookie.split('; ').forEach((c) => {
    const eqIdx = c.indexOf('=');
    const key = eqIdx > -1 ? decodeURIComponent(c.substring(0, eqIdx)) : c;
    if (prefixes.some((p) => key.startsWith(p))) {
      deleteCookie(key);
    }
  });
}

// ─── Migration: move any tokens left in localStorage into cookies ───────────

export function migrateFromLocalStorage() {
  const at = localStorage.getItem('accessToken');
  const rt = localStorage.getItem('refreshToken');
  if (at) { setAccessToken(at); localStorage.removeItem('accessToken'); }
  if (rt) { setRefreshToken(rt); localStorage.removeItem('refreshToken'); }
}

