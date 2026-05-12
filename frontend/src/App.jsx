import { useEffect, Component, Suspense, lazy } from 'react';
import { Routes, Route, Navigate, useLocation } from 'react-router-dom';
import { useAuthStore } from './store';
import { useSessionExpiry } from './hooks/useSessionExpiry';
const LoginPage = lazy(() => import('./pages/LoginPage'));
const RegisterPage = lazy(() => import('./pages/RegisterPage'));
const ForgotPasswordPage = lazy(() => import('./pages/ForgotPasswordPage'));
const ChatPage = lazy(() => import('./pages/ChatPage'));
const AdminPage = lazy(() => import('./pages/AdminPage'));
const SettingsPage = lazy(() => import('./pages/SettingsPage'));
const DashboardPage = lazy(() => import('./pages/DashboardPage'));
const NotFoundPage = lazy(() => import('./pages/NotFoundPage'));
const HelpPage = lazy(() => import('./pages/HelpPage'));
import CookieConsent from './components/auth/CookieConsent';
import ToastContainer from './components/shared/Toast';
import NetworkBanner from './components/shared/NetworkBanner';
import PageTransition from './components/shared/PageTransition';
import './App.css';

class ErrorBoundary extends Component {
    constructor(props) { super(props); this.state = { hasError: false, error: null }; }
    static getDerivedStateFromError(error) { return { hasError: true, error }; }
    componentDidCatch(error, info) { console.error('React ErrorBoundary:', error, info); }
    render() {
        if (this.state.hasError) {
            return (
                <div style={{ padding: 40, textAlign: 'center' }}>
                    <h2>Something went wrong</h2>
                    <p style={{ color: '#999' }}>{this.state.error?.message}</p>
                    <button onClick={() => globalThis.location.reload()} style={{ marginTop: 16, padding: '8px 24px', cursor: 'pointer' }}>
                        Reload Page
                    </button>
                </div>
            );
        }
        return this.props.children;
    }
}

function ProtectedRoute({ children }) {
    const { user, loading } = useAuthStore();
    const location = useLocation();
    if (loading) return <div className="loading-screen"><div className="spinner" /></div>;
    if (!user) {
        // Preserve where the user was trying to go so we can return after login
        const from = location.pathname + location.search;
        return <Navigate to="/login" state={{ from }} replace />;
    }
    return children;
}

function GuestRoute({ children }) {
    const { user, loading } = useAuthStore();
    if (loading) return <div className="loading-screen"><div className="spinner" /></div>;
    return user ? <Navigate to="/chat" replace /> : children;
}

function AdminRoute({ children }) {
    const { user, loading } = useAuthStore();
    const location = useLocation();
    if (loading) return <div className="loading-screen"><div className="spinner" /></div>;
    if (!user) {
        const from = location.pathname + location.search;
        return <Navigate to="/login" state={{ from }} replace />;
    }
    if (user.role !== 'ADMIN') return <Navigate to="/chat" replace />;
    return children;
}

function LazyFallback() {
    return <div className="loading-screen"><div className="spinner" /></div>;
}

export default function App() {
    const checkAuth = useAuthStore((s) => s.checkAuth);
    useEffect(() => { checkAuth(); }, [checkAuth]);
    useSessionExpiry();

    // Apply saved theme on mount (for non-auth pages)
    useEffect(() => {
        const saved = localStorage.getItem('eakp-theme') || 'dark';
        document.documentElement.setAttribute('data-theme', saved);
    }, []);

    return (
        <ErrorBoundary>
            <a href="#main-content" className="skip-to-content">Skip to content</a>
            <NetworkBanner />
            <PageTransition>
                <div id="main-content">
                    <Suspense fallback={<LazyFallback />}>
                        <Routes>
                            <Route path="/login" element={<GuestRoute><LoginPage /></GuestRoute>} />
                            <Route path="/register" element={<GuestRoute><RegisterPage /></GuestRoute>} />
                            <Route path="/forgot-password" element={<GuestRoute><ForgotPasswordPage /></GuestRoute>} />
                            <Route path="/chat/:conversationId?" element={<ProtectedRoute><ChatPage /></ProtectedRoute>} />
                            <Route path="/dashboard" element={<ProtectedRoute><DashboardPage /></ProtectedRoute>} />
                            <Route path="/admin" element={<AdminRoute><AdminPage /></AdminRoute>} />
                            <Route path="/settings" element={<ProtectedRoute><SettingsPage /></ProtectedRoute>} />
                            <Route path="/help" element={<ProtectedRoute><HelpPage /></ProtectedRoute>} />
                            <Route path="/" element={<Navigate to="/chat" />} />
                            <Route path="*" element={<NotFoundPage />} />
                        </Routes>
                    </Suspense>
                </div>
            </PageTransition>
            <CookieConsent />
            <ToastContainer aria-live="polite" />
        </ErrorBoundary>
    );
}
