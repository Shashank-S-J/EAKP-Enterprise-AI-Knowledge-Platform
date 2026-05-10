import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { auth } from '../api/client';
import AuthVisual from '../components/auth/AuthVisual';
import ThemeToggle from '../components/auth/ThemeToggle';

export default function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const navigate = useNavigate();

  // Reset token entry state
  const [showReset, setShowReset] = useState(false);
  const [resetToken, setResetToken] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [resetLoading, setResetLoading] = useState(false);
  const [resetSuccess, setResetSuccess] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await auth.forgotPassword(email);
      setSubmitted(true);
    } catch {
      // Always show success to prevent email enumeration
      setSubmitted(true);
    } finally {
      setLoading(false);
    }
  };

  const handleReset = async (e) => {
    e.preventDefault();
    setError('');
    if (newPassword !== confirmPassword) { setError('Passwords do not match'); return; }
    if (newPassword.length < 8) { setError('Password must be at least 8 characters'); return; }
    if (!/[A-Z]/.test(newPassword)) { setError('Password must contain an uppercase letter'); return; }
    if (!/[0-9]/.test(newPassword)) { setError('Password must contain a digit'); return; }
    if (!/[^A-Za-z0-9]/.test(newPassword)) { setError('Password must contain a special character'); return; }
    setResetLoading(true);
    try {
      await auth.resetPassword(resetToken, newPassword);
      setResetSuccess(true);
      setTimeout(() => navigate('/login'), 3000);
    } catch (err) {
      setError(err.message || 'Failed to reset password');
    } finally {
      setResetLoading(false);
    }
  };

  return (
    <div className="auth-page">
      <div className="auth-ambient">
        <div className="glow-1" />
        <div className="glow-2" />
        <div className="glow-3" />
      </div>

      <section className="auth-left auth-left--login">
        <AuthVisual variant="login" />
        <div className="auth-left-brand">
          <div className="auth-brand-logo">
            <div className="auth-brand-icon">
              <span className="material-symbols-outlined filled">hub</span>
            </div>
            <span className="auth-brand-name">EAKP</span>
          </div>
        </div>
        <div className="auth-left-bottom">
          <h2>Reset Your<br/>Password</h2>
          <p>We'll send you a secure link to reset your credentials.</p>
        </div>
      </section>

      <section className="auth-right">
        <ThemeToggle />
        <div className="auth-card">
          <div className="auth-card-header">
            <h1>
              {resetSuccess ? 'Password Reset!' : showReset ? 'Enter Reset Token' : submitted ? 'Check Your Email' : 'Forgot Password'}
            </h1>
            <p>
              {resetSuccess
                ? 'Your password has been reset. Redirecting to login...'
                : showReset
                ? 'Enter the reset token from your email and choose a new password.'
                : submitted
                ? `If an account exists for ${email}, we've sent a password reset link.`
                : "Enter your email address and we'll send you a reset link."}
            </p>
          </div>

          {error && (
            <div className="auth-error">
              <span className="material-symbols-outlined" style={{ fontSize: 16 }}>error</span>
              {error}
            </div>
          )}

          {resetSuccess ? (
            <div style={{ textAlign: 'center', padding: '2rem 0' }}>
              <span className="material-symbols-outlined" style={{ fontSize: 48, color: 'var(--success)' }}>check_circle</span>
              <p style={{ marginTop: 16, color: 'var(--text-secondary)' }}>
                Redirecting to login page...
              </p>
            </div>
          ) : showReset ? (
            <form onSubmit={handleReset}>
              <div className="form-group">
                <label htmlFor="reset-token">Reset Token</label>
                <div className="input-wrap">
                  <span className="material-symbols-outlined">key</span>
                  <input id="reset-token" type="text" placeholder="Paste your reset token" value={resetToken}
                    onChange={(e) => setResetToken(e.target.value)} required autoFocus />
                </div>
              </div>
              <div className="form-group">
                <label htmlFor="new-password">New Password</label>
                <div className="input-wrap">
                  <span className="material-symbols-outlined">lock</span>
                  <input id="new-password" type="password" placeholder="Min 8 chars, uppercase, digit, special" value={newPassword}
                    onChange={(e) => setNewPassword(e.target.value)} required minLength={8} autoComplete="new-password" />
                </div>
              </div>
              <div className="form-group">
                <label htmlFor="confirm-new-password">Confirm Password</label>
                <div className="input-wrap">
                  <span className="material-symbols-outlined">lock_reset</span>
                  <input id="confirm-new-password" type="password" placeholder="Confirm new password" value={confirmPassword}
                    onChange={(e) => setConfirmPassword(e.target.value)} required minLength={8} autoComplete="new-password" />
                </div>
              </div>
              <button type="submit" className="btn-primary" disabled={resetLoading || !resetToken || !newPassword}>
                {resetLoading ? 'Resetting…' : 'Reset Password'}
                {!resetLoading && <span className="material-symbols-outlined">lock_reset</span>}
              </button>
            </form>
          ) : !submitted ? (
            <form onSubmit={handleSubmit}>
              <div className="form-group">
                <label htmlFor="reset-email">Corporate Email</label>
                <div className="input-wrap">
                  <span className="material-symbols-outlined">mail</span>
                  <input
                    id="reset-email" type="email" placeholder="name@enterprise.com"
                    value={email} onChange={(e) => setEmail(e.target.value)}
                    required autoComplete="email" autoFocus
                  />
                </div>
              </div>
              <button type="submit" className="btn-primary" disabled={loading || !email}>
                {loading ? 'Sending…' : 'Send Reset Link'}
                {!loading && <span className="material-symbols-outlined">send</span>}
              </button>
            </form>
          ) : (
            <div style={{ textAlign: 'center', padding: '2rem 0' }}>
              <span className="material-symbols-outlined" style={{ fontSize: 48, color: 'var(--success)' }}>mark_email_read</span>
              <p style={{ marginTop: 16, color: 'var(--text-secondary)' }}>
                The link expires in 15 minutes. Check your spam folder if you don't see it.
              </p>
              <button
                className="btn-primary"
                style={{ marginTop: 16 }}
                onClick={() => { setShowReset(true); setError(''); }}
              >
                I have a reset token
                <span className="material-symbols-outlined">arrow_forward</span>
              </button>
            </div>
          )}

          <div className="auth-footer">
            <p>Remember your password? <Link to="/login">Sign in</Link></p>
          </div>
        </div>
      </section>
    </div>
  );
}

