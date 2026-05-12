import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuthStore, useSidebarStore } from '../store';
import { useToastStore } from '../store/toastStore';
import { analytics, profile as profileApi, workspaceConfig as wsConfigApi, documents as docsApi } from '../api/client';
import Sidebar from '../components/sidebar/Sidebar';
import DocumentsPanel from '../components/documents/DocumentsPanel';
import BottomNav from '../components/shared/BottomNav';
import ThemeToggle from '../components/auth/ThemeToggle';

function MetricCard({ icon, iconColor, label, value, trend }) {
    return (
        <div className="metric-card glass-panel">
            <div className="metric-card-header">
                <div className="metric-card-icon" style={{ background: `${iconColor}15`, color: iconColor }}>
                    <span className="material-symbols-outlined">{icon}</span>
                </div>
                {trend != null && (
                    <span className={`stat-trend ${trend >= 0 ? 'up' : 'down'}`}>
            <span className="material-symbols-outlined" style={{ fontSize: 12 }}>
              {trend >= 0 ? 'trending_up' : 'trending_down'}
            </span>
                        {trend >= 0 ? '+' : ''}{trend}%
          </span>
                )}
            </div>
            <p className="metric-card-label">{label}</p>
            <h3 className="metric-card-value">{value ?? '—'}</h3>
        </div>
    );
}

function RagDonut({ score }) {
    const circumference = 2 * Math.PI * 40;
    const offset = circumference * (1 - (score || 0) / 100);
    return (
        <div className="donut-chart">
            <svg viewBox="0 0 100 100" width="192" height="192">
                <circle cx="50" cy="50" r="40" fill="transparent" stroke="var(--bg-surface-container-highest)" strokeWidth="8" />
                <circle
                    cx="50" cy="50" r="40" fill="transparent"
                    stroke="var(--accent)" strokeWidth="8"
                    strokeDasharray={circumference} strokeDashoffset={offset}
                    strokeLinecap="round"
                    style={{ transform: 'rotate(-90deg)', transformOrigin: '50% 50%', transition: 'stroke-dashoffset 1s ease' }}
                />
            </svg>
            <div className="donut-chart-center">
                <span className="donut-chart-value">{score ?? '—'}</span>
                <span className="donut-chart-label">/ 100</span>
            </div>
        </div>
    );
}

function formatNum(n) {
    if (n == null) return '—';
    if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
    if (n >= 1000) return (n / 1000).toFixed(1) + 'k';
    return n.toString();
}

export default function SettingsPage() {
    const navigate = useNavigate();
    const { user, setUser } = useAuthStore();
    const { open: sidebarOpen, toggle: toggleSidebar } = useSidebarStore();
    const toast = useToastStore();

    const [activeTab, setActiveTab] = useState(0);
    const [docsOpen, setDocsOpen] = useState(false);
    const [docsCount, setDocsCount] = useState(0);

    // Tab 1 — Analytics state
    const [overview, setOverview] = useState(null);
    const [ragQuality, setRagQuality] = useState(null);
    const [usageData, setUsageData] = useState(null);
    const [usageDays, setUsageDays] = useState(7);
    const [analyticsLoading, setAnalyticsLoading] = useState(true);

    // Tab 2 — Profile state
    const [fullName, setFullName] = useState(user?.fullName || '');
    const [currentPassword, setCurrentPassword] = useState('');
    const [newPassword, setNewPassword] = useState('');
    const [confirmPassword, setConfirmPassword] = useState('');
    const [profileSaving, setProfileSaving] = useState(false);
    const [passwordSaving, setPasswordSaving] = useState(false);

    // Tab 3 — Workspace config state
    const [wsConfig, setWsConfig] = useState(null);
    const [configLoading, setConfigLoading] = useState(false);
    const [configSaving, setConfigSaving] = useState(false);

    // Load doc count
    useEffect(() => {
        docsApi.list()
            .then((list) => setDocsCount(Array.isArray(list) ? list.length : 0))
            .catch(() => {});
    }, []);

    const loadAnalytics = useCallback(async () => {
        setAnalyticsLoading(true);
        try {
            const [ov, rq, us] = await Promise.allSettled([
                analytics.overview(),
                analytics.ragQuality(),
                analytics.usage(usageDays),
            ]);
            if (ov.status === 'fulfilled') setOverview(ov.value);
            if (rq.status === 'fulfilled') setRagQuality(rq.value);
            if (us.status === 'fulfilled') setUsageData(us.value);
        } catch { /* silent */ }
        setAnalyticsLoading(false);
    }, [usageDays]);

    const loadConfig = useCallback(async () => {
        setConfigLoading(true);
        try {
            const cfg = await wsConfigApi.get();
            setWsConfig(cfg);
        } catch {
            setWsConfig({ workspaceName: '', slug: '', defaultModel: 'gpt-4o-mini', chunkSize: 512, chunkOverlap: 128, embeddingModel: 'text-embedding-3-small' });
        }
        setConfigLoading(false);
    }, []);

    // Load analytics on mount or when switching to tab 0
    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch-on-tab-change pattern
        if (activeTab === 0) loadAnalytics();
    }, [activeTab, loadAnalytics]);

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- fetch-on-tab-change pattern
        if (activeTab === 2 && !wsConfig) loadConfig();
    }, [activeTab, wsConfig, loadConfig]);

    const handleProfileSave = async () => {
        setProfileSaving(true);
        try {
            await profileApi.update({ fullName });
            setUser({ ...user, fullName });
            toast.success('Profile updated');
        } catch (e) { toast.error(e.message || 'Failed to update profile'); }
        setProfileSaving(false);
    };

    const handlePasswordChange = async () => {
        if (newPassword !== confirmPassword) { toast.error('Passwords do not match'); return; }
        if (newPassword.length < 8) { toast.error('Password must be at least 8 characters'); return; }
        setPasswordSaving(true);
        try {
            await profileApi.changePassword({ currentPassword, newPassword });
            setCurrentPassword(''); setNewPassword(''); setConfirmPassword('');
            toast.success('Password updated');
        } catch (e) { toast.error(e.message || 'Failed to change password'); }
        setPasswordSaving(false);
    };

    const handleConfigSave = async () => {
        setConfigSaving(true);
        try {
            await wsConfigApi.update(wsConfig);
            toast.success('Configuration saved');
        } catch (e) { toast.error(e.message || 'Failed to save configuration'); }
        setConfigSaving(false);
    };

    const initials = user?.fullName
        ? user.fullName.split(' ').map(n => n[0]).join('').toUpperCase().slice(0, 2)
        : user?.email?.slice(0, 2).toUpperCase() || '??';

    const tabs = ['Analytics Overview', 'Profile Management', 'Workspace Configuration'];

    // Usage chart data
    const usageChartData = usageData?.queriesPerDay || [];
    const maxQueries = Math.max(1, ...usageChartData.map(d => d.count || d.queries || 0));

    // Doc stats for pie
    const readyDocs = overview?.readyDocuments ?? overview?.totalDocuments ?? 0;
    const totalDocs = overview?.totalDocuments ?? 0;
    const indexedPct = totalDocs > 0 ? Math.round((readyDocs / totalDocs) * 100) : 0;

    // Top questions
    const topQuestions = usageData?.topQuestions || [];

    // Faithfulness data
    const faithScore = ragQuality ? Math.round((ragQuality.avgFaithfulness ?? ragQuality.score ?? 0) * 100) : null;

    return (
        <div className="app-layout">
            <Sidebar
                onSelectConv={(id) => navigate(`/chat/${id}`)}
                onOpenDocs={() => setDocsOpen(!docsOpen)}
                docsCount={docsCount}
            />
            <div className="main-content">
                {/* Header */}
                <div className="chat-header">
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        {!sidebarOpen && (
                            <button className="chat-header-btn" onClick={toggleSidebar} title="Open sidebar">
                                <span className="material-symbols-outlined">menu</span>
                            </button>
                        )}
                        <span className="material-symbols-outlined" style={{ color: 'var(--accent)', fontSize: 24 }}>settings</span>
                        <h2>Settings & Analytics</h2>
                    </div>
                    <div className="chat-header-actions">
                        <ThemeToggle />
                        <button className="chat-header-btn" onClick={() => navigate('/chat')} title="Back to chat">
                            <span className="material-symbols-outlined">arrow_back</span>
                        </button>
                    </div>
                </div>

                <div className="settings-page">
                    <p style={{ color: 'var(--text-secondary)', marginBottom: '1.5rem', fontSize: 16 }}>
                        Manage your enterprise environment, analyze platform usage, and configure workspace protocols.
                    </p>

                    {/* Tabs */}
                    <div className="settings-tabs">
                        {tabs.map((t, i) => (
                            <button
                                key={t}
                                className={`settings-tab ${activeTab === i ? 'active' : ''}`}
                                onClick={() => setActiveTab(i)}
                            >
                                {t}
                                {activeTab === i && <div className="settings-tab-glow" />}
                            </button>
                        ))}
                    </div>

                    {/* Tab 0 — Analytics Overview */}
                    {activeTab === 0 && (
                        <div className="settings-analytics" style={{ animation: 'fadeIn 0.3s ease' }}>
                            {analyticsLoading ? (
                                <div className="admin-stats-grid">
                                    {[1,2,3,4].map(i => <div key={i} className="skeleton" style={{height:120,borderRadius:12}} />)}
                                </div>
                            ) : (
                                <>
                                    {/* Metric Cards */}
                                    <div className="admin-stats-grid" style={{ marginBottom: '2rem' }}>
                                        <MetricCard icon="description" iconColor="var(--secondary)" label="Total Documents" value={formatNum(overview?.totalDocuments)} trend={null} />
                                        <MetricCard icon="segment" iconColor="var(--tertiary)" label="Vector Chunks" value={formatNum(overview?.totalChunks)} trend={null} />
                                        <MetricCard icon="forum" iconColor="var(--accent)" label="Active Conversations" value={formatNum(overview?.totalConversations)} trend={null} />
                                        <MetricCard icon="group" iconColor="var(--danger)" label="Total Users" value={formatNum(overview?.totalMessages ?? overview?.cacheEntries)} trend={null} />
                                    </div>

                                    {/* RAG Quality + Usage Chart */}
                                    <div className="admin-analytics-grid">
                                        {/* RAG Quality */}
                                        <div className="admin-card" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
                                            <h2 style={{ alignSelf: 'flex-start' }}>
                                                <span className="material-symbols-outlined">analytics</span>
                                                RAG Quality Score
                                            </h2>
                                            <RagDonut score={faithScore} />
                                            <p style={{ fontSize: 13, color: 'var(--text-secondary)', textAlign: 'center', marginTop: 8 }}>
                                                Based on retrieval relevance and generation accuracy.
                                            </p>
                                            <div style={{ width: '100%', marginTop: 16 }}>
                                                <div className="rag-stat-pill" style={{ marginBottom: 8 }}>
                          <span className="value" style={{ color: 'var(--danger)' }}>
                            {ragQuality?.hallucinationRate != null ? `${Math.round(ragQuality.hallucinationRate * 100)}%` : '—'}
                          </span>
                                                    <span className="label">Hallucination Rate</span>
                                                </div>
                                                <div className="rag-stat-pill">
                          <span className="value" style={{ color: 'var(--success)' }}>
                            {ragQuality ? (ragQuality.avgFaithfulness ?? ragQuality.score ?? 0).toFixed(2) : '—'}
                          </span>
                                                    <span className="label">Avg. Confidence</span>
                                                </div>
                                            </div>
                                        </div>

                                        {/* Usage Chart */}
                                        <div className="admin-card">
                                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
                                                <h2>
                                                    <span className="material-symbols-outlined">bar_chart</span>
                                                    System Usage
                                                </h2>
                                                <select
                                                    className="glass-input"
                                                    style={{ padding: '6px 12px', borderRadius: 6, fontSize: 13 }}
                                                    value={usageDays}
                                                    onChange={(e) => setUsageDays(Number(e.target.value))}
                                                >
                                                    <option value={7}>Last 7 Days</option>
                                                    <option value={30}>Last 30 Days</option>
                                                </select>
                                            </div>

                                            {/* Summary boxes */}
                                            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 20 }}>
                                                <div className="admin-summary-box">
                                                    <span className="material-symbols-outlined" style={{ fontSize: 20, color: 'var(--secondary)' }}>group</span>
                                                    <div>
                                                        <p style={{ fontSize: 12, color: 'var(--text-muted)' }}>Active Users</p>
                                                        <p style={{ fontSize: 20, fontWeight: 700 }}>{formatNum(usageData?.activeUsers)}</p>
                                                    </div>
                                                </div>
                                                <div className="admin-summary-box">
                                                    <span className="material-symbols-outlined" style={{ fontSize: 20, color: 'var(--tertiary)' }}>memory</span>
                                                    <div>
                                                        <p style={{ fontSize: 12, color: 'var(--text-muted)' }}>Cache Entries</p>
                                                        <p style={{ fontSize: 20, fontWeight: 700 }}>{formatNum(usageData?.cacheEntries)}</p>
                                                    </div>
                                                </div>
                                            </div>

                                            {/* Bar Chart */}
                                            <div className="usage-bar-chart">
                                                {usageChartData.length === 0 ? (
                                                    <p style={{ color: 'var(--text-muted)', textAlign: 'center', width: '100%', alignSelf: 'center', fontSize: 13 }}>No usage data available</p>
                                                ) : usageChartData.map((d, i) => {
                                                    const val = d.count || d.queries || 0;
                                                    const pct = (val / maxQueries) * 100;
                                                    const dayLabel = d.date ? new Date(d.date).toLocaleDateString('en-US', { weekday: 'short' }) : `D${i+1}`;
                                                    const isToday = i === usageChartData.length - 1;
                                                    return (
                                                        <div key={i} className="usage-bar" style={{ height: `${Math.max(pct, 2)}%` }}>
                                                            <div className="usage-tooltip">{val} queries</div>
                                                            <div className="usage-day-label" style={{ color: isToday ? 'var(--accent)' : undefined, fontWeight: isToday ? 700 : undefined }}>
                                                                {dayLabel}
                                                            </div>
                                                        </div>
                                                    );
                                                })}
                                            </div>

                                            {/* Faithfulness bar */}
                                            <div style={{ marginTop: 24 }}>
                                                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
                                                    <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>Faithfulness Distribution</span>
                                                    <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--accent)' }}>High Confidence ({faithScore ?? '—'}%)</span>
                                                </div>
                                                {(() => {
                                                    const hallRateVal = ragQuality?.hallucinationRate != null ? Math.round(ragQuality.hallucinationRate * 100) : null;
                                                    return (
                                                        <div className="faithfulness-bar">
                                                            <div className="faithfulness-segment" style={{ width: `${faithScore ?? 70}%`, background: 'var(--accent)' }} />
                                                            <div className="faithfulness-segment" style={{ width: `${hallRateVal != null ? 100 - (faithScore ?? 70) - hallRateVal : 20}%`, background: 'var(--tertiary)' }} />
                                                            <div className="faithfulness-segment" style={{ width: `${hallRateVal ?? 10}%`, background: 'var(--danger)' }} />
                                                        </div>
                                                    );
                                                })()}
                                            </div>
                                        </div>
                                    </div>

                                    {/* Top Queries + Doc Index Pie */}
                                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.5rem' }}>
                                        {/* Top Queries */}
                                        <div className="admin-card">
                                            <h2>
                                                <span className="material-symbols-outlined">search</span>
                                                Top Semantic Queries
                                            </h2>
                                            {topQuestions.length > 0 ? (
                                                <ul className="query-list">
                                                    {topQuestions.slice(0, 5).map((q, i) => (
                                                        <li key={i} className="query-list-item">
                                                            <div className="query-rank">{i + 1}</div>
                                                            <span className="query-text">"{q.question || q.query || `Query ${i+1}`}"</span>
                                                            <span className="query-count">{q.count || q.requests || '—'} req</span>
                                                        </li>
                                                    ))}
                                                </ul>
                                            ) : (
                                                <p style={{ color: 'var(--text-muted)', fontSize: 13, textAlign: 'center', padding: '2rem 0' }}>
                                                    No query data available yet. Start chatting to see your most common questions.
                                                </p>
                                            )}
                                        </div>

                                        {/* Doc Index Pie */}
                                        <div className="admin-card" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
                                            <h2 style={{ alignSelf: 'flex-start', width: '100%' }}>
                                                <span className="material-symbols-outlined">pie_chart</span>
                                                Document Indexing Status
                                            </h2>
                                            <div
                                                className="doc-index-pie"
                                                style={{
                                                    background: `conic-gradient(var(--accent) 0% ${indexedPct}%, var(--tertiary) ${indexedPct}% ${indexedPct + 5}%, var(--bg-surface-container-highest) ${indexedPct + 5}% 100%)`,
                                                }}
                                            >
                                                <div className="doc-index-pie-inner">
                                                    <span style={{ fontSize: 24, fontWeight: 700 }}>{indexedPct}%</span>
                                                    <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>Indexed</span>
                                                </div>
                                            </div>
                                            <div style={{ display: 'flex', gap: 16, fontSize: 13, color: 'var(--text-secondary)', marginTop: 16 }}>
                                                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}><div style={{ width: 10, height: 10, borderRadius: '50%', background: 'var(--accent)' }} /> Active</div>
                                                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}><div style={{ width: 10, height: 10, borderRadius: '50%', background: 'var(--tertiary)' }} /> Processing</div>
                                                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}><div style={{ width: 10, height: 10, borderRadius: '50%', background: 'var(--bg-surface-container-highest)' }} /> Failed</div>
                                            </div>
                                        </div>
                                    </div>
                                </>
                            )}
                        </div>
                    )}

                    {/* Tab 1 — Profile Management */}
                    {activeTab === 1 && (
                        <div className="settings-profile" style={{ animation: 'fadeIn 0.3s ease' }}>
                            <div className="admin-card" style={{ maxWidth: 640 }}>
                                <h2>
                                    <span className="material-symbols-outlined">person</span>
                                    Profile Information
                                </h2>
                                <div style={{ display: 'flex', alignItems: 'center', gap: 20, marginBottom: 24 }}>
                                    <div className="settings-avatar">{initials}</div>
                                    <div>
                                        <div style={{ fontSize: 18, fontWeight: 600 }}>{user?.fullName || user?.email}</div>
                                        <div style={{ fontSize: 13, color: 'var(--text-muted)' }}>{user?.role || 'USER'}</div>
                                    </div>
                                </div>

                                <div className="form-group">
                                    <label htmlFor="settings-email">EMAIL</label>
                                    <div className="input-wrap">
                                        <span className="material-symbols-outlined">email</span>
                                        <input id="settings-email" type="email" value={user?.email || ''} disabled style={{ background: 'var(--bg-surface-container-highest)', opacity: 0.7 }} />
                                    </div>
                                </div>

                                <div className="form-group">
                                    <label htmlFor="settings-role">ROLE</label>
                                    <div className="input-wrap">
                                        <span className="material-symbols-outlined">badge</span>
                                        <input id="settings-role" type="text" value={user?.role || 'USER'} disabled style={{ background: 'var(--bg-surface-container-highest)', opacity: 0.7 }} />
                                    </div>
                                </div>

                                <div className="form-group">
                                    <label htmlFor="settings-fullname">FULL NAME</label>
                                    <div className="input-wrap">
                                        <span className="material-symbols-outlined">person</span>
                                        <input id="settings-fullname" type="text" value={fullName} onChange={(e) => setFullName(e.target.value)} placeholder="Your full name" />
                                    </div>
                                </div>

                                <button className="primary-gradient-btn" onClick={handleProfileSave} disabled={profileSaving}
                                        style={{ padding: '12px 24px', borderRadius: 8, fontSize: 14, fontWeight: 600, marginTop: 8 }}>
                                    {profileSaving ? 'Saving...' : 'Save Changes'}
                                </button>
                            </div>

                            <div className="admin-card" style={{ maxWidth: 640, marginTop: 20 }}>
                                <h2>
                                    <span className="material-symbols-outlined">lock</span>
                                    Change Password
                                </h2>
                                {user?.authProvider && user.authProvider !== 'LOCAL' ? (
                                    <p style={{ color: 'var(--text-muted)', fontSize: 14 }}>
                                        You signed in with {user.authProvider}. Password management is handled by your OAuth provider.
                                    </p>
                                ) : (
                                    <>
                                        <div className="form-group">
                                            <label htmlFor="settings-curpw">CURRENT PASSWORD</label>
                                            <div className="input-wrap">
                                                <span className="material-symbols-outlined">key</span>
                                                <input id="settings-curpw" type="password" value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)} placeholder="Current password" />
                                            </div>
                                        </div>
                                        <div className="form-group">
                                            <label htmlFor="settings-newpw">NEW PASSWORD</label>
                                            <div className="input-wrap">
                                                <span className="material-symbols-outlined">lock</span>
                                                <input id="settings-newpw" type="password" value={newPassword} onChange={(e) => setNewPassword(e.target.value)} placeholder="New password (min 8 chars)" />
                                            </div>
                                        </div>
                                        <div className="form-group">
                                            <label htmlFor="settings-confirmpw">CONFIRM PASSWORD</label>
                                            <div className="input-wrap">
                                                <span className="material-symbols-outlined">lock</span>
                                                <input id="settings-confirmpw" type="password" value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)} placeholder="Confirm new password" />
                                            </div>
                                        </div>
                                        <button className="primary-gradient-btn" onClick={handlePasswordChange} disabled={passwordSaving || !currentPassword || !newPassword}
                                                style={{ padding: '12px 24px', borderRadius: 8, fontSize: 14, fontWeight: 600, marginTop: 8 }}>
                                            {passwordSaving ? 'Updating...' : 'Update Password'}
                                        </button>
                                    </>
                                )}
                            </div>
                        </div>
                    )}

                    {/* Tab 2 — Workspace Configuration */}
                    {activeTab === 2 && (
                        <div className="settings-config" style={{ animation: 'fadeIn 0.3s ease' }}>
                            {configLoading ? (
                                <div className="admin-card" style={{ maxWidth: 640 }}>
                                    {[1,2,3,4].map(i => <div key={i} className="skeleton" style={{ height: 48, marginBottom: 16, borderRadius: 8 }} />)}
                                </div>
                            ) : wsConfig && (
                                <div className="admin-card" style={{ maxWidth: 640 }}>
                                    <h2>
                                        <span className="material-symbols-outlined">tune</span>
                                        Workspace Configuration
                                    </h2>

                                    <div className="form-group">
                                        <label htmlFor="ws-name">WORKSPACE NAME</label>
                                        <div className="input-wrap">
                                            <span className="material-symbols-outlined">corporate_fare</span>
                                            <input id="ws-name" type="text" value={wsConfig.workspaceName || ''} onChange={(e) => {
                                                const name = e.target.value;
                                                const slug = name.toLowerCase().replaceAll(/[^a-z0-9]+/g, '-').replaceAll(/^-|-$/g, '');
                                                setWsConfig({ ...wsConfig, workspaceName: name, slug });
                                            }} placeholder="Workspace name" />
                                        </div>
                                    </div>

                                    <div className="form-group">
                                        <label htmlFor="ws-slug">SLUG</label>
                                        <div className="input-wrap">
                                            <span className="material-symbols-outlined">link</span>
                                            <input id="ws-slug" type="text" value={wsConfig.slug || ''} onChange={(e) => setWsConfig({ ...wsConfig, slug: e.target.value })} placeholder="workspace-slug" />
                                        </div>
                                    </div>

                                    <div className="form-group">
                                        <label htmlFor="ws-model">DEFAULT MODEL</label>
                                        <select id="ws-model" className="glass-input config-select" value={wsConfig.defaultModel || 'gpt-4o-mini'}
                                                onChange={(e) => setWsConfig({ ...wsConfig, defaultModel: e.target.value })}>
                                            <option value="gpt-4o-mini">gpt-4o-mini</option>
                                            <option value="gpt-4o">gpt-4o</option>
                                            <option value="claude-3.5-sonnet">claude-3.5-sonnet</option>
                                            <option value="deepseek-r1:7b">deepseek-r1:7b (local)</option>
                                            <option value="llama-3.3-70b-versatile">llama-3.3-70b-versatile (Groq)</option>
                                        </select>
                                    </div>

                                    <div className="form-group">
                                        <label htmlFor="ws-chunksize">CHUNK SIZE: {wsConfig.chunkSize || 512}</label>
                                        <input id="ws-chunksize" type="range" className="config-range" min="128" max="4096" step="128"
                                               value={wsConfig.chunkSize || 512}
                                               onChange={(e) => setWsConfig({ ...wsConfig, chunkSize: Number(e.target.value) })} />
                                        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, color: 'var(--text-muted)' }}>
                                            <span>128</span><span>4096</span>
                                        </div>
                                    </div>

                                    <div className="form-group">
                                        <label htmlFor="ws-chunkoverlap">CHUNK OVERLAP: {wsConfig.chunkOverlap || 128}</label>
                                        <input id="ws-chunkoverlap" type="range" className="config-range" min="0" max="512" step="64"
                                               value={wsConfig.chunkOverlap || 128}
                                               onChange={(e) => setWsConfig({ ...wsConfig, chunkOverlap: Number(e.target.value) })} />
                                        <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 11, color: 'var(--text-muted)' }}>
                                            <span>0</span><span>512</span>
                                        </div>
                                    </div>

                                    <div className="form-group">
                                        <label htmlFor="ws-embedding">EMBEDDING MODEL</label>
                                        <select id="ws-embedding" className="glass-input config-select" value={wsConfig.embeddingModel || 'text-embedding-3-small'}
                                                onChange={(e) => setWsConfig({ ...wsConfig, embeddingModel: e.target.value })}>
                                            <option value="text-embedding-3-small">text-embedding-3-small (1536d)</option>
                                            <option value="text-embedding-3-large">text-embedding-3-large (3072d)</option>
                                            <option value="nomic-embed-text">nomic-embed-text (768d, local)</option>
                                            <option value="sentence-transformers/all-MiniLM-L6-v2">all-MiniLM-L6-v2 (384d, free)</option>
                                        </select>
                                    </div>

                                    <button className="primary-gradient-btn" onClick={handleConfigSave} disabled={configSaving}
                                            style={{ padding: '12px 24px', borderRadius: 8, fontSize: 14, fontWeight: 600, marginTop: 12 }}>
                                        {configSaving ? 'Saving...' : 'Save Configuration'}
                                    </button>
                                </div>
                            )}
                        </div>
                    )}
                </div>
            </div>
            <DocumentsPanel open={docsOpen} onClose={() => setDocsOpen(false)} />
            <BottomNav onOpenDocs={() => setDocsOpen(!docsOpen)} />
        </div>
    );
}