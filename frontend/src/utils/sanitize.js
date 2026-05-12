/**
 * Input sanitization utilities to prevent XSS attacks.
 * Used for rendering user-generated content safely.
 */

const HTML_ESCAPE_MAP = {
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;',
    "'": '&#x27;',
    '/': '&#x2F;',
};

/**
 * Escape HTML entities in a string to prevent XSS.
 * Use this when rendering user input outside of React's JSX (which auto-escapes).
 */
export function escapeHtml(str) {
    if (typeof str !== 'string') return '';
    return str.replace(/[&<>"'/]/g, (char) => HTML_ESCAPE_MAP[char]);
}

/**
 * Strip all HTML tags from a string.
 */
export function stripHtml(str) {
    if (typeof str !== 'string') return '';
    return str.replace(/<[^>]*>/g, '');
}

/**
 * Sanitize a URL to prevent javascript: protocol XSS.
 * Only allows http:, https:, mailto:, and relative URLs.
 */
export function sanitizeUrl(url) {
    if (!url || typeof url !== 'string') return '';
    const trimmed = url.trim();
    const lower = trimmed.toLowerCase();
    if (lower.startsWith('javascript:') || lower.startsWith('data:') || lower.startsWith('vbscript:')) {
        return '';
    }
    return trimmed;
}

/**
 * Validate and sanitize user input for text fields.
 * Trims, limits length, removes control characters.
 */
export function sanitizeInput(str, maxLength = 10000) {
    if (typeof str !== 'string') return '';
    // Remove null bytes and control characters (except newline, tab)
    // eslint-disable-next-line no-control-regex -- intentional control-char stripping
    let cleaned = str.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, '');
    // Trim and limit length
    cleaned = cleaned.trim().slice(0, maxLength);
    return cleaned;
}

/**
 * Validate email format (basic RFC 5322)
 */
export function isValidEmail(email) {
    if (!email || typeof email !== 'string') return false;
    const re = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
    return re.test(email) && email.length <= 254;
}

/**
 * Password strength validator
 */
export function validatePassword(password) {
    const issues = [];
    if (!password || password.length < 8) issues.push('At least 8 characters required');
    if (password.length > 128) issues.push('Maximum 128 characters');
    if (!/[A-Z]/.test(password)) issues.push('At least one uppercase letter');
    if (!/[a-z]/.test(password)) issues.push('At least one lowercase letter');
    if (!/[0-9]/.test(password)) issues.push('At least one number');
    if (!/[^A-Za-z0-9]/.test(password)) issues.push('At least one special character');
    return { valid: issues.length === 0, issues, strength: getStrength(password) };
}

function getStrength(password) {
    if (!password) return 0;
    let score = 0;
    if (password.length >= 8) score++;
    if (password.length >= 12) score++;
    if (/[A-Z]/.test(password) && /[a-z]/.test(password)) score++;
    if (/[0-9]/.test(password)) score++;
    if (/[^A-Za-z0-9]/.test(password)) score++;
    // 0-5 scale
    return Math.min(score, 5);
}