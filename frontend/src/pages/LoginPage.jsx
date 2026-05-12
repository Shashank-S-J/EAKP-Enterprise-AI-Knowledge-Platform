import { useState, useEffect } from 'react';
import { useNavigate, Link, useSearchParams, useLocation } from 'react-router-dom';
import { auth, storeTokens } from '../api/client';
import { useAuthStore } from '../store';
import AuthVisual from '../components/auth/AuthVisual';
import LegalModal from '../components/shared/LegalModal';
import Logo from '../components/shared/Logo';
import ThemeToggle from '../components/auth/ThemeToggle';

// Whitelist of internal paths the login redirect is allowed to send users to.
// Prevents open-redirect via crafted ?from= or location.state values.
const SAFE_REDIRECT_PREFIXES = ['/chat', '/dashboard', '/admin', '/settings'];
function safeRedirectTarget(candidate) {
    if (typeof candidate !== 'string') return '/chat';
    // Must be an internal absolute path; reject schemes, protocol-relative, and back-nav tricks.
    if (!candidate.startsWith('/') || candidate.startsWith('//')) return '/chat';
    if (candidate.includes('..')) return '/chat';
    return SAFE_REDIRECT_PREFIXES.some((p) => candidate === p || candidate.startsWith(p + '/') || candidate.startsWith(p + '?'))
        ? candidate
        : '/chat';
}

export default function LoginPage() {
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [showPassword, setShowPassword] = useState(false);
    const [error, setError] = useState('');
    const [loading, setLoading] = useState(false);
    const [legalModal, setLegalModal] = useState(null);
    const [oauthAgreed, setOauthAgreed] = useState(
        () => localStorage.getItem('eakp-oauth-agreed') === 'true'
    );
    const [showAgreeWarning, setShowAgreeWarning] = useState(false);
    const navigate = useNavigate();
    const setUser = useAuthStore((s) => s.setUser);
    const [searchParams] = useSearchParams();
    const location = useLocation();

    const finishAuth = async (data) => {
        storeTokens(data);
        const user = await auth.me();
        setUser(user);
        const target = safeRedirectTarget(location.state?.from);
        navigate(target, { replace: true });
    };

    // Handle GitHub OAuth callback
    useEffect(() => {
        const code = searchParams.get('code');
        const oauthProvider = searchParams.get('oauth');
        if (oauthProvider === 'github' && code) {
            // eslint-disable-next-line react-hooks/set-state-in-effect -- one-shot mount handler reacting to URL params
            setLoading(true);
            setError('');
            // Strip OAuth params from the URL immediately so refreshing or sharing
            // the link doesn't replay the (now-spent) authorization code.
            globalThis.history.replaceState({}, '', '/login');
            auth.githubCallback(code)
                .then(finishAuth)
                .catch((err) => setError(err.message || 'GitHub login failed'))
                .finally(() => setLoading(false));
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps -- intentional mount-only OAuth callback
    }, []);

    const handleSubmit = async (e) => {
        e.preventDefault();
        setError('');
        setLoading(true);
        try {
            const data = await auth.login(email, password);
            await finishAuth(data);
        } catch (err) {
            setError(err.message || 'Login failed');
        } finally {
            setLoading(false);
        }
    };

    const ensureAgreed = () => {
        if (!oauthAgreed) {
            setShowAgreeWarning(true);
            setError('Please accept the Terms & Privacy Policy to continue with social sign-in.');
            return false;
        }
        setShowAgreeWarning(false);
        return true;
    };

    const handleOAuthGoogle = async () => {
        if (!ensureAgreed()) return;
        setError('');
        setLoading(true);
        try {
            const { google } = globalThis;
            if (!google?.accounts?.id) {
                setError('Google Sign-In SDK not loaded.');
                setLoading(false);
                return;
            }
            google.accounts.id.initialize({
                client_id: import.meta.env.VITE_GOOGLE_CLIENT_ID || '',
                callback: async (response) => {
                    try {
                        const data = await auth.googleCallback(response.credential);
                        await finishAuth(data);
                    } catch (err) {
                        setError(err.message || 'OAuth login failed');
                    } finally {
                        setLoading(false);
                    }
                },
            });
            google.accounts.id.prompt((notification) => {
                if (notification.isNotDisplayed() || notification.isSkippedMoment()) {
                    setError('Google sign-in was dismissed.');
                    setLoading(false);
                }
            });
        } catch (err) {
            setError(err.message || 'Google login failed');
            setLoading(false);
        }
    };

    const handleOAuthGitHub = () => {
        if (!ensureAgreed()) return;
        const clientId = import.meta.env.VITE_GITHUB_CLIENT_ID || '';
        if (!clientId) { setError('GitHub OAuth is not configured.'); return; }
        const redirect = encodeURIComponent(globalThis.location.origin + '/login?oauth=github');
        globalThis.location.href = `https://github.com/login/oauth/authorize?client_id=${clientId}&redirect_uri=${redirect}&scope=user:email`;
    };

    return (
        <div className="auth-page">
            {/* Ambient glows */}
            <div className="auth-ambient">
                <div className="glow-1" />
                <div className="glow-2" />
                <div className="glow-3" />
            </div>

            {/* LEFT: Brand + Interactive Visual */}
            <section className="auth-left auth-left--login">
                <AuthVisual variant="login" />
                <div className="auth-left-brand">
                    <div className="auth-brand-logo">
                        <div className="auth-brand-icon"><Logo size={24} /></div>
                        <span className="auth-brand-name">EAKP</span>
                    </div>
                </div>

                <div className="auth-left-bottom">
                    <h2>Enterprise AI<br/>Knowledge Platform</h2>
                    <p>Bridging the gap between complex data processing and intuitive human insight.</p>
                    <div className="auth-status-pill">
                        <div className="auth-status-dot" />
                        <span>System Active</span>
                    </div>
                </div>
            </section>

            {/* RIGHT: Login Form */}
            <section className="auth-right">
                <ThemeToggle />
                <div className="auth-mobile-brand">
                    <div className="auth-brand-logo">
                        <div className="auth-brand-icon"><Logo size={24} /></div>
                        <span className="auth-brand-name">EAKP</span>
                    </div>
                </div>

                <div className="auth-card">
                    <div className="auth-card-header">
                        <h1>Welcome Back</h1>
                        <p>Sign in to access your enterprise intelligence.</p>
                    </div>

                    {error && (
                        <div className="auth-error">
                            <span className="material-symbols-outlined" style={{ fontSize: 16 }}>error</span>
                            {error}
                        </div>
                    )}

                    <form onSubmit={handleSubmit}>
                        <div className="form-group">
                            <label htmlFor="login-email">Corporate Email</label>
                            <div className="input-wrap">
                                <span className="material-symbols-outlined">mail</span>
                                <input id="login-email" type="email" placeholder="name@enterprise.com" value={email}
                                       onChange={(e) => setEmail(e.target.value)} required autoComplete="email" />
                            </div>
                        </div>

                        <div className="form-group">
                            <div className="label-row">
                                <label htmlFor="login-password">Password</label>
                                <Link to="/forgot-password">Forgot Password?</Link>
                            </div>
                            <div className="input-wrap">
                                <span className="material-symbols-outlined">lock</span>
                                <input id="login-password" type={showPassword ? 'text' : 'password'} placeholder="••••••••" value={password}
                                       onChange={(e) => setPassword(e.target.value)} required autoComplete="current-password" />
                                <button type="button" className="password-toggle" onClick={() => setShowPassword(!showPassword)} tabIndex={-1}>
                                    <span className="material-symbols-outlined">{showPassword ? 'visibility_off' : 'visibility'}</span>
                                </button>
                            </div>
                        </div>

                        <button type="submit" className="btn-primary" disabled={loading}>
                            {loading ? 'Signing in…' : 'Sign In'}
                            {!loading && <span className="material-symbols-outlined">arrow_forward</span>}
                        </button>
                    </form>

                    <div className="divider">or continue with</div>
                    <label className={`oauth-consent ${showAgreeWarning ? 'oauth-consent--warn' : ''}`}>
                        <input
                            type="checkbox"
                            checked={oauthAgreed}
                            onChange={(e) => {
                                setOauthAgreed(e.target.checked);
                                localStorage.setItem('eakp-oauth-agreed', e.target.checked ? 'true' : 'false');
                                if (e.target.checked) { setShowAgreeWarning(false); setError(''); }
                            }}
                            aria-describedby="oauth-consent-text"
                        />
                        <span id="oauth-consent-text">
              I agree to the{' '}
                            <button type="button" className="link-btn" onClick={() => setLegalModal('terms')}>Terms of Service</button>{' '}
                            and{' '}
                            <button type="button" className="link-btn" onClick={() => setLegalModal('privacy')}>Privacy Policy</button>.
            </span>
                    </label>
                    <div className="oauth-buttons">
                        <button className="btn-oauth" onClick={handleOAuthGoogle} disabled={loading}>
                            <svg width="18" height="18" viewBox="0 0 24 24"><path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 01-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z" fill="#4285F4"/><path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/><path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z" fill="#FBBC05"/><path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/></svg>
                            Google
                        </button>
                        <button className="btn-oauth" onClick={handleOAuthGitHub} disabled={loading}>
                            <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor"><path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z"/></svg>
                            GitHub
                        </button>
                    </div>

                    <div className="auth-footer">
                        <p>Don't have an account? <Link to="/register">Register</Link></p>
                        <div className="auth-footer-legal">
                            <button type="button" className="link-btn" onClick={() => setLegalModal('terms')}>Terms</button>
                            <span>·</span>
                            <button type="button" className="link-btn" onClick={() => setLegalModal('privacy')}>Privacy</button>
                        </div>
                    </div>
                </div>
            </section>

            {legalModal && <LegalModal type={legalModal} onClose={() => setLegalModal(null)} />}
        </div>
    );
}