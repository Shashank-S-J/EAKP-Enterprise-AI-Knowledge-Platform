import { useState, useEffect } from 'react';
import { useNavigate, Link, useSearchParams } from 'react-router-dom';
import { auth, storeTokens } from '../api/client';
import { useAuthStore } from '../store';
import AuthVisual from '../components/auth/AuthVisual';
import LegalModal from '../components/shared/LegalModal';
import Logo from '../components/shared/Logo';
import ThemeToggle from '../components/auth/ThemeToggle';
import { useGoogleIdentity } from '../hooks/useGoogleIdentity';
import {
    redirectToGoogleAuth,
    consumeGoogleIdTokenFromUrl,
    shouldSkipGoogleOneTap,
} from '../utils/googleOAuth';

function getPasswordStrength(pw) {
    if (!pw) return { level: 0, label: '' };
    let score = 0;
    if (pw.length >= 8) score++;
    if (/[A-Z]/.test(pw)) score++;
    if (/[0-9]/.test(pw)) score++;
    if (/[^A-Za-z0-9]/.test(pw)) score++;
    const labels = ['', 'Weak', 'Fair', 'Strong', 'Very Strong'];
    return { level: score, label: labels[score] || '' };
}

export default function RegisterPage() {
    const [form, setForm] = useState({ email: '', password: '', confirmPassword: '', fullName: '', workspaceName: '' });
    const [agreed, setAgreed] = useState(false);
    const [showPassword, setShowPassword] = useState(false);
    const [error, setError] = useState('');
    const [loading, setLoading] = useState(false);
    const [legalModal, setLegalModal] = useState(null); // 'terms' | 'privacy' | null
    const navigate = useNavigate();
    const setUser = useAuthStore((s) => s.setUser);
    const [searchParams] = useSearchParams();
    const strength = getPasswordStrength(form.password);
    // Lazily load Google Identity Services script (auth pages only).
    const gsiReady = useGoogleIdentity();

    const update = (k) => (e) => setForm({ ...form, [k]: e.target.value });

    const finishAuth = async (data) => {
        storeTokens(data);
        const user = await auth.me();
        setUser(user);
        navigate('/chat');
    };

    // Handle GitHub OAuth callback on register page
    useEffect(() => {
        const code = searchParams.get('code');
        const oauthProvider = searchParams.get('oauth');
        if (oauthProvider === 'github' && code) {
            // eslint-disable-next-line react-hooks/set-state-in-effect -- one-shot mount handler reacting to URL params
            setLoading(true);
            setError('');
            const wsName = sessionStorage.getItem('oauth_workspace') || '';
            sessionStorage.removeItem('oauth_workspace');
            auth.githubCallback(code, wsName)
                .then(finishAuth)
                .catch((err) => setError(err.message || 'GitHub sign-up failed'))
                .finally(() => setLoading(false));
            return;
        }
        // Handle Google OAuth redirect callback (mobile-friendly path).
        // Google returns the ID token in the URL fragment.
        if (oauthProvider === 'google') {
            try {
                const result = consumeGoogleIdTokenFromUrl();
                if (result?.idToken) {
                    // eslint-disable-next-line react-hooks/set-state-in-effect
                    setLoading(true);
                    setError('');
                    globalThis.history.replaceState({}, '', '/register');
                    auth.googleCallback(result.idToken, result.workspaceName)
                        .then(finishAuth)
                        .catch((err) => setError(err.message || 'Google sign-up failed'))
                        .finally(() => setLoading(false));
                }
            } catch (err) {
                setError(err.message || 'Google sign-up failed');
            }
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps -- intentional mount-only OAuth callback
    }, []);

    const handleSubmit = async (e) => {
        e.preventDefault();
        setError('');
        if (!form.fullName.trim()) {
            setError('Full name is required');
            return;
        }
        if (!form.workspaceName.trim()) {
            setError('Workspace name is required');
            return;
        }
        if (form.password.length < 8) {
            setError('Password must be at least 8 characters');
            return;
        }
        if (form.password !== form.confirmPassword) {
            setError('Passwords do not match');
            return;
        }
        if (!agreed) {
            setError('Please agree to the Terms of Service');
            return;
        }
        setLoading(true);
        try {
            const data = await auth.register(form.email, form.password, form.fullName, form.workspaceName);
            await finishAuth(data);
        } catch (err) {
            setError(err.message || 'Registration failed');
        } finally {
            setLoading(false);
        }
    };

    const handleOAuthGoogle = async () => {
        if (!agreed) { setError('Please agree to the Terms & Privacy Policy to continue.'); return; }
        setError('');

        // Mobile / in-app webviews: skip One Tap entirely. It never displays
        // there, just yielded "dismissed" errors. Use OpenID Connect redirect.
        if (shouldSkipGoogleOneTap()) {
            setLoading(true);
            try {
                redirectToGoogleAuth('/register?oauth=google', form.workspaceName);
            } catch (err) {
                setError(err.message || 'Google sign-up is not configured.');
                setLoading(false);
            }
            return;
        }

        setLoading(true);
        try {
            const { google } = globalThis;
            if (!gsiReady || !google?.accounts?.id) {
                redirectToGoogleAuth('/register?oauth=google', form.workspaceName);
                return;
            }
            google.accounts.id.initialize({
                client_id: import.meta.env.VITE_GOOGLE_CLIENT_ID || '',
                callback: async (response) => {
                    try {
                        const data = await auth.googleCallback(response.credential, form.workspaceName);
                        await finishAuth(data);
                    } catch (err) { setError(err.message || 'OAuth sign-up failed'); } finally { setLoading(false); }
                },
            });
            google.accounts.id.prompt((n) => {
                if (n.isNotDisplayed() || n.isSkippedMoment()) {
                    // One Tap unavailable — fall back to redirect (mobile-safe).
                    try {
                        redirectToGoogleAuth('/register?oauth=google', form.workspaceName);
                    } catch (err) {
                        setError(err.message || 'Google sign-up is not configured.');
                        setLoading(false);
                    }
                }
            });
        } catch (err) { setError(err.message || 'Google sign-up failed'); setLoading(false); }
    };

    const handleOAuthGitHub = () => {
        if (!agreed) { setError('Please agree to the Terms & Privacy Policy to continue.'); return; }
        const clientId = import.meta.env.VITE_GITHUB_CLIENT_ID || '';
        if (!clientId) { setError('GitHub OAuth is not configured.'); return; }
        if (form.workspaceName) sessionStorage.setItem('oauth_workspace', form.workspaceName);
        const redirect = encodeURIComponent(globalThis.location.origin + '/register?oauth=github');
        globalThis.location.href = `https://github.com/login/oauth/authorize?client_id=${clientId}&redirect_uri=${redirect}&scope=user:email`;
    };

    return (
        <div className="auth-page">
            <div className="auth-grain" aria-hidden="true" />
            <div className="auth-ambient">
                <div className="glow-1" />
                <div className="glow-2" />
                <div className="glow-3" />
            </div>

            {/* LEFT: Brand + Interactive Visual + 3D Showcase */}
            <section className="auth-left auth-left--register">
                <AuthVisual variant="register" />
                <div className="auth-left-brand">
                    <div className="auth-brand-logo">
                        <div className="auth-brand-icon"><Logo size={24} /></div>
                        <span className="auth-brand-name">EAKP</span>
                    </div>
                </div>

                {/* 3D Showcase Area */}
                <div className="auth-showcase">
                    <div className="showcase-card">
                        <div className="showcase-glow" />
                        <div className="showcase-content">
                            <div className="showcase-orb">
                                <div className="showcase-orb-ring showcase-orb-ring--1" />
                                <div className="showcase-orb-ring showcase-orb-ring--2" />
                                <div className="showcase-orb-core">
                                    <span className="material-symbols-outlined filled">database</span>
                                </div>
                            </div>
                            <div className="showcase-icons">
                                <div className="showcase-icon-tile showcase-icon-tile--float1">
                                    <span className="material-symbols-outlined">psychology</span>
                                </div>
                                <div className="showcase-icon-tile showcase-icon-tile--float2">
                                    <span className="material-symbols-outlined">insights</span>
                                </div>
                            </div>
                        </div>
                    </div>
                    <h2>Build Your Knowledge Empire</h2>
                    <p>Empower your team with a centralized, AI-driven repository. Transform raw data into actionable insights instantly.</p>
                </div>

                <div className="auth-left-footer">
                    <span className="material-symbols-outlined" style={{ fontSize: 20, color: 'var(--secondary, #4cd7f6)' }}>shield_lock</span>
                    <span className="auth-trust-label">256-bit Encryption • Enterprise Grade</span>
                </div>
            </section>

            {/* RIGHT: Register Form */}
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
                        <h1>Create Account</h1>
                        <p>Start your AI-powered knowledge journey.</p>
                    </div>

                    {error && (
                        <div className="auth-error">
                            <span className="material-symbols-outlined" style={{ fontSize: 16 }}>error</span>
                            {error}
                        </div>
                    )}

                    <form onSubmit={handleSubmit}>
                        <div className="form-group">
                            <label htmlFor="reg-name">Full Name</label>
                            <div className="input-wrap">
                                <span className="material-symbols-outlined">person</span>
                                <input id="reg-name" type="text" placeholder="Jane Doe" value={form.fullName} onChange={update('fullName')} required autoComplete="name" />
                            </div>
                        </div>

                        <div className="form-group">
                            <label htmlFor="reg-email">Professional Email</label>
                            <div className="input-wrap">
                                <span className="material-symbols-outlined">mail</span>
                                <input id="reg-email" type="email" placeholder="jane@company.com" value={form.email} onChange={update('email')} required autoComplete="email" />
                            </div>
                        </div>

                        <div className="form-group">
                            <label htmlFor="reg-workspace">Workspace Name</label>
                            <div className="input-wrap">
                                <span className="material-symbols-outlined">domain</span>
                                <input id="reg-workspace" type="text" placeholder="Create your team's workspace" value={form.workspaceName} onChange={update('workspaceName')} required />
                            </div>
                        </div>

                        <div className="reg-password-grid">
                            <div className="form-group">
                                <label htmlFor="reg-password">Password</label>
                                <div className="input-wrap">
                                    <span className="material-symbols-outlined">lock</span>
                                    <input id="reg-password" type={showPassword ? 'text' : 'password'} placeholder="••••••••" value={form.password}
                                           onChange={update('password')} required minLength={8} autoComplete="new-password" />
                                    <button type="button" className="password-toggle" onClick={() => setShowPassword(!showPassword)} tabIndex={-1}>
                                        <span className="material-symbols-outlined">{showPassword ? 'visibility_off' : 'visibility'}</span>
                                    </button>
                                </div>
                            </div>
                            <div className="form-group">
                                <label htmlFor="reg-confirm">Confirm Password</label>
                                <div className="input-wrap">
                                    <span className="material-symbols-outlined">lock_reset</span>
                                    <input id="reg-confirm" type={showPassword ? 'text' : 'password'} placeholder="••••••••" value={form.confirmPassword}
                                           onChange={update('confirmPassword')} required minLength={8} autoComplete="new-password" />
                                </div>
                            </div>
                        </div>

                        {/* Password strength indicator */}
                        {form.password && (
                            <div className="password-strength">
                                <div className="strength-header">
                                    <span>Security Strength</span>
                                    <span className="strength-label">{strength.label}</span>
                                </div>
                                <div className="strength-bars">
                                    {[1,2,3,4].map((i) => (
                                        <div key={i} className={`strength-bar ${i <= strength.level ? 'active' : ''} ${strength.level <= 1 ? 'weak' : strength.level === 2 ? 'warn' : ''}`} />
                                    ))}
                                </div>
                            </div>
                        )}

                        <div className="terms-check">
                            <input type="checkbox" id="terms" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} />
                            <label htmlFor="terms">
                                I agree to the{' '}
                                <button type="button" className="link-btn" onClick={(e) => { e.preventDefault(); setLegalModal('terms'); }}>Terms of Service</button>
                                {' '}and{' '}
                                <button type="button" className="link-btn" onClick={(e) => { e.preventDefault(); setLegalModal('privacy'); }}>Privacy Policy</button>.
                            </label>
                        </div>

                        <button type="submit" className="btn-primary" disabled={loading}>
                            {loading ? 'Creating account…' : 'Create Account'}
                            {!loading && <span className="material-symbols-outlined">arrow_forward</span>}
                        </button>
                    </form>

                    <div className="divider">or sign up with</div>
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
                        <p>Already have an account? <Link to="/login">Sign In</Link></p>
                    </div>
                </div>
            </section>

            {legalModal && <LegalModal type={legalModal} onClose={() => setLegalModal(null)} />}
        </div>
    );
}