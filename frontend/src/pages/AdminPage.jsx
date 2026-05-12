import { useEffect, useState, useRef, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { admin, analytics } from '../api/client';
import { useToastStore } from '../store/toastStore';
import BottomNav from '../components/shared/BottomNav';
import { useConfirmDialog } from '../components/shared/ConfirmDialog';
import ThemeToggle from '../components/auth/ThemeToggle';

function AnimatedCount({ value, duration = 800 }) {
    const [display, setDisplay] = useState(0);
    const ref = useRef(null);
    useEffect(() => {
        const target = typeof value === 'number' ? value : 0;
        // eslint-disable-next-line react-hooks/set-state-in-effect -- legitimate animation tween
        if (target === 0) { setDisplay(0); return; }
        const start = Date.now();
        const tick = () => {
            const elapsed = Date.now() - start;
            const progress = Math.min(elapsed / duration, 1);
            const eased = 1 - Math.pow(1 - progress, 3);
            setDisplay(Math.round(eased * target));
            if (progress < 1) ref.current = requestAnimationFrame(tick);
        };
        ref.current = requestAnimationFrame(tick);
        return () => cancelAnimationFrame(ref.current);
    }, [value, duration]);
    return <>{display}</>;
}

function StatCard({ icon, value, label, status, trend, sparkline }) {
    const dir = trend?.dir || 'neutral';
    const pct = trend?.pct ?? 0;
    return (
        <div className={`admin-stat-card ${status}`}>
            <div className="stat-icon-left">
                <span className="material-symbols-outlined">{icon}</span>
            </div>
            {pct !== 0 && (
                <span className={`stat-trend ${dir}`}>
          <span className="material-symbols-outlined" style={{ fontSize: 12 }}>
            {dir === 'up' ? 'trending_up' : dir === 'down' ? 'trending_down' : 'trending_flat'}
          </span>
                    {dir === 'up' ? '+' : dir === 'down' ? '-' : ''}{Math.abs(pct)}%
        </span>
            )}
            <div className="stat-value"><AnimatedCount value={value} /></div>
            <div className="stat-label">{label}</div>
            {sparkline && sparkline.length > 0 && (
                <div className="stat-sparkline">
                    {sparkline.map((v, i) => (
                        <div key={i} className="stat-sparkline-bar" style={{ height: `${v}%`, opacity: 0.3 + (i / sparkline.length) * 0.7 }} />
                    ))}
                </div>
            )}
        </div>
    );
}

export default function AdminPage() {
    const navigate = useNavigate();
    const toast = useToastStore();
    const { ConfirmDialog, confirm: confirmAction } = useConfirmDialog();

    const [health, setHealth] = useState(null);
    const [workspaces, setWorkspaces] = useState([]);
    const [selectedWs, setSelectedWs] = useState(null);
    const [wsUsers, setWsUsers] = useState([]);
    const [ragQuality, setRagQuality] = useState(null);
    const [loading, setLoading] = useState(true);
    const [refreshing, setRefreshing] = useState(false);
    const [lastUpdated, setLastUpdated] = useState(null);

    // New analytics state
    const [globalRag, setGlobalRag] = useState(null);
    const [usageData, setUsageData] = useState(null);
    const [usageMode, setUsageMode] = useState('daily');

    const loadData = useCallback(async () => {
        setLoading(true);
        try {
            const [h, ws, rq, us] = await Promise.allSettled([
                admin.health(),
                admin.workspaces(),
                analytics.ragQuality(),
                analytics.usage(7),
            ]);
            if (h.status === 'fulfilled') setHealth(h.value);
            if (ws.status === 'fulfilled') setWorkspaces(ws.value);
            if (rq.status === 'fulfilled') setGlobalRag(rq.value);
            if (us.status === 'fulfilled') setUsageData(us.value);
            setLastUpdated(new Date());
        } catch { toast.error('Failed to load admin data'); }
        finally { setLoading(false); }
    }, [toast]);

    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- initial data fetch on mount
        loadData();
    }, [loadData]);

    const handleRefresh = async () => {
        setRefreshing(true);
        await loadData();
        setTimeout(() => setRefreshing(false), 1000);
    };

    const selectWorkspace = async (ws) => {
        setSelectedWs(ws);
        try {
            const [users, rq] = await Promise.all([
                admin.workspaceUsers(ws.id || ws.workspaceId),
                admin.workspaceRagQuality(ws.id || ws.workspaceId),
            ]);
            setWsUsers(users); setRagQuality(rq);
        } catch { toast.error('Failed to load workspace details'); }
    };

    const handleEvictCache = async (wsId) => {
        try { await admin.evictCache(wsId); toast.info('Cache evicted'); }
        catch { toast.error('Failed to evict cache'); }
    };

    const handleDeactivateUser = async (wsId, userId) => {
        const ok = await confirmAction('Deactivate User', 'This will prevent the user from accessing the platform. Are you sure?');
        if (!ok) return;
        try {
            await admin.deactivateUser(wsId, userId);
            setWsUsers(wsUsers.map((u) => u.id === userId ? { ...u, active: false } : u));
            toast.info('User deactivated');
        } catch { toast.error('Failed to deactivate user'); }
    };

    const handleActivateUser = async (wsId, userId) => {
        const ok = await confirmAction('Reactivate User', 'This will restore the user\'s access to the platform. Continue?');
        if (!ok) return;
        try {
            await admin.activateUser(wsId, userId);
            setWsUsers(wsUsers.map((u) => u.id === userId ? { ...u, active: true } : u));
            toast.info('User activated');
        } catch { toast.error('Failed to activate user'); }
    };

    if (loading) return (
        <div className="admin-page">
            <div className="admin-header">
                <div className="skeleton" style={{width:40,height:40,borderRadius:'50%'}} />
                <div className="skeleton" style={{width:200,height:28,borderRadius:8}} />
                <div style={{flex:1}} />
                <div className="skeleton" style={{width:40,height:40,borderRadius:'50%'}} />
            </div>
            <div className="admin-stats-grid">
                {[1,2,3,4].map(i => <div key={i} className="skeleton" style={{height:140,borderRadius:12}} />)}
            </div>
            <div className="skeleton" style={{height:300,borderRadius:12,marginBottom:24}} />
            <div className="skeleton" style={{height:300,borderRadius:12}} />
            <BottomNav onOpenDocs={() => {}} />
        </div>
    );

    const totalDocs = health?.totalDocuments ?? 0;
    const pendingDocs = health?.pendingDocuments ?? 0;
    const failedDocs = health?.failedDocuments ?? 0;
    const dlqDepth = health?.dlqDepth ?? 0;

    // Global RAG quality data
    const globalFaith = globalRag?.avgFaithfulness;
    const globalFaithPct = globalFaith != null ? Math.round(globalFaith * 100) : null;
    const hallRate = globalRag?.hallucinationRate != null ? Math.round(globalRag.hallucinationRate * 100) : null;

    // Usage chart
    const dailyQueries = usageData?.queriesPerDay || [];
    const maxQ = Math.max(1, ...dailyQueries.map(d => d.count || d.queries || 0));
    const sparkTrend = usageData?.faithfulnessTrend || [];

    // Workspace RAG for selected
    const avgFaith = ragQuality?.averageFaithfulness ?? ragQuality?.avgFaithfulness;
    const faithPct = avgFaith != null ? Math.round(avgFaith * 100) : null;

    return (
        <div className="admin-page">
            {/* Header */}
            <div className="admin-header">
                <button className="admin-back-btn" onClick={() => navigate('/chat')} title="Back to chat">
                    <span className="material-symbols-outlined">arrow_back</span>
                </button>
                <h1>
                    <span className="material-symbols-outlined filled">shield</span>
                    Admin Dashboard
                </h1>
                <button className={`admin-refresh-btn ${refreshing ? 'spinning' : ''}`} onClick={handleRefresh} title="Refresh">
                    <span className="material-symbols-outlined">refresh</span>
                </button>
                <ThemeToggle />
                {lastUpdated && (
                    <span style={{ fontSize: 11, color: 'var(--text-muted)', marginLeft: 8 }}>
            Updated {lastUpdated.toLocaleTimeString()}
          </span>
                )}
            </div>

            {/* Stat Cards with Sparklines & Trends */}
            <div className="admin-stats-grid">
                <StatCard icon="description" value={totalDocs} label="Total Documents"
                          status={totalDocs > 0 ? 'healthy' : 'warning'}
                          trend={health?.trends?.totalDocuments}
                          sparkline={health?.sparklines?.totalDocuments} />
                <StatCard icon="schedule" value={pendingDocs} label="Pending Docs"
                          status={pendingDocs === 0 ? 'healthy' : 'warning'}
                          trend={health?.trends?.pending}
                          sparkline={health?.sparklines?.pending} />
                <StatCard icon="error" value={failedDocs} label="Failed Docs"
                          status={failedDocs === 0 ? 'healthy' : 'critical'}
                          trend={health?.trends?.failed}
                          sparkline={health?.sparklines?.failed} />
                <StatCard icon="inbox" value={dlqDepth} label="DLQ Depth"
                          status={dlqDepth === 0 ? 'healthy' : 'critical'}
                          trend={health?.trends?.dlq}
                          sparkline={health?.sparklines?.dlq} />
            </div>

            {/* RAG Quality + Usage Analytics */}
            <div className="admin-analytics-grid">
                {/* RAG Quality Card */}
                <div className="admin-card">
                    <h2>
                        <span className="material-symbols-outlined">analytics</span>
                        RAG Quality
                    </h2>
                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '16px 0' }}>
                        <div className="rag-donut" style={{
                            background: globalFaithPct != null
                                ? `conic-gradient(${globalFaithPct >= 80 ? 'var(--success)' : globalFaithPct >= 60 ? 'var(--warning)' : 'var(--danger)'} ${(globalFaithPct * 3.6)}deg, var(--bg-surface-container-highest) 0deg)`
                                : 'var(--bg-surface-container-highest)'
                        }}>
                            <div className="rag-donut-inner">
                                <span style={{ fontSize: 32, fontWeight: 700 }}>{globalFaithPct != null ? `${globalFaithPct}%` : '—'}</span>
                                <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>Faithfulness</span>
                            </div>
                        </div>
                    </div>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                        <div className="rag-stat-pill">
                            <span className="value" style={{ color: 'var(--danger)' }}>{hallRate != null ? `${hallRate}%` : '—'}</span>
                            <span className="label">Hallucination Rate</span>
                        </div>
                        <div className="rag-stat-pill">
                            <span className="value" style={{ color: 'var(--success)' }}>{globalFaith != null ? globalFaith.toFixed(2) : '—'}</span>
                            <span className="label">Avg. Confidence</span>
                        </div>
                    </div>
                    {/* 7-day sparkline */}
                    {sparkTrend.length > 0 && (
                        <div style={{ marginTop: 16 }}>
                            <p style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 8 }}>7-Day Trend</p>
                            <div className="stat-sparkline" style={{ height: 40 }}>
                                {sparkTrend.map((v, i) => (
                                    <div key={i} style={{ flex: 1, height: `${v}%`, background: 'var(--accent)', borderRadius: '2px 2px 0 0', opacity: 0.2 + (i / sparkTrend.length) * 0.8 }} />
                                ))}
                            </div>
                        </div>
                    )}
                </div>

                {/* Usage Analytics Card */}
                <div className="admin-card">
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
                        <h2>
                            <span className="material-symbols-outlined">bar_chart</span>
                            Usage Analytics
                        </h2>
                        <div style={{ display: 'flex', gap: 4 }}>
                            <button className={`usage-toggle-btn ${usageMode === 'daily' ? 'active' : ''}`} onClick={() => setUsageMode('daily')}>Daily</button>
                            <button className={`usage-toggle-btn ${usageMode === 'weekly' ? 'active' : ''}`} onClick={() => setUsageMode('weekly')}>Weekly</button>
                        </div>
                    </div>
                    {/* Summary boxes */}
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 20 }}>
                        <div className="admin-summary-box">
                            <span className="material-symbols-outlined" style={{ fontSize: 20, color: 'var(--secondary)' }}>group</span>
                            <div>
                                <p style={{ fontSize: 12, color: 'var(--text-muted)' }}>Active Users (24h)</p>
                                <p style={{ fontSize: 20, fontWeight: 700 }}>{usageData?.activeUsers ?? health?.activeUsers ?? '—'}</p>
                            </div>
                        </div>
                        <div className="admin-summary-box">
                            <span className="material-symbols-outlined" style={{ fontSize: 20, color: 'var(--tertiary)' }}>memory</span>
                            <div>
                                <p style={{ fontSize: 12, color: 'var(--text-muted)' }}>Cache Hit Rate</p>
                                <p style={{ fontSize: 20, fontWeight: 700 }}>{health?.cacheHitRate ?? '—'}</p>
                            </div>
                        </div>
                    </div>
                    {/* Bar chart */}
                    <div className="usage-bar-chart">
                        {dailyQueries.length > 0 ? dailyQueries.map((d, i) => {
                            const val = d.count || d.queries || 0;
                            const pct = (val / maxQ) * 100;
                            const day = d.date ? new Date(d.date).toLocaleDateString('en-US', { weekday: 'short' }) : ['Mon','Tue','Wed','Thu','Fri','Sat','Sun'][i % 7];
                            const isLast = i === dailyQueries.length - 1;
                            return (
                                <div key={i} className={`usage-bar ${isLast ? 'today' : ''}`} style={{ height: `${Math.max(pct, 3)}%` }}>
                                    <div className="usage-tooltip">{val}</div>
                                    <div className="usage-day-label" style={{ color: isLast ? 'var(--accent)' : undefined, fontWeight: isLast ? 700 : undefined }}>{day}</div>
                                </div>
                            );
                        }) : (
                            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', width: '100%', height: '100%', color: 'var(--text-muted)', fontSize: 13 }}>
                                No usage data available yet
                            </div>
                        )}
                    </div>
                </div>
            </div>

            {/* Workspaces Table */}
            <div className="admin-card">
                <h2>
                    <span className="material-symbols-outlined">database</span>
                    Workspaces ({workspaces.length})
                </h2>
                {workspaces.length === 0 ? (
                    <div className="admin-empty-state">
                        <span className="material-symbols-outlined" style={{ fontSize: 48, color: 'var(--text-muted)' }}>folder_off</span>
                        <h3>No workspaces yet</h3>
                        <p>Workspaces will appear here once users register and create them.</p>
                    </div>
                ) : (
                    <table className="admin-table">
                        <thead>
                        <tr>
                            <th>Name</th>
                            <th>Slug</th>
                            <th>Users</th>
                            <th>Actions</th>
                        </tr>
                        </thead>
                        <tbody>
                        {workspaces.map((ws) => {
                            const wsId = ws.id || ws.workspaceId;
                            return (
                                <tr key={wsId} onClick={() => selectWorkspace(ws)}>
                                    <td>{ws.name || ws.workspaceName || '-'}</td>
                                    <td style={{color:'var(--text-muted)'}}>{ws.slug || '-'}</td>
                                    <td>{ws.userCount ?? '-'}</td>
                                    <td>
                                        <button className="action-btn" onClick={(e) => { e.stopPropagation(); handleEvictCache(wsId); }} title="Evict cache">
                                            <span className="material-symbols-outlined" style={{fontSize:14}}>refresh</span> Evict Cache
                                        </button>
                                    </td>
                                </tr>
                            );
                        })}
                        </tbody>
                    </table>
                )}
            </div>

            {/* Selected Workspace Detail */}
            {selectedWs && (
                <div className="admin-card">
                    <h2>
                        <span className="material-symbols-outlined">domain</span>
                        {selectedWs.name || selectedWs.workspaceName}
                    </h2>

                    {/* RAG Quality Gauge */}
                    {ragQuality && (
                        <>
                            <h3>RAG Quality</h3>
                            <div className="rag-gauge">
                                <div className="rag-gauge-circle" style={{
                                    background: `conic-gradient(${faithPct >= 80 ? 'var(--success)' : faithPct >= 60 ? 'var(--warning)' : 'var(--danger)'} ${(faithPct || 0) * 3.6}deg, var(--bg-surface-container-highest) 0deg)`
                                }}>
                                    <div style={{width:76,height:76,borderRadius:'50%',background:'var(--bg-surface-container)',display:'flex',alignItems:'center',justifyContent:'center'}}>
                                        <span className="rag-gauge-value">{faithPct ?? '—'}%</span>
                                    </div>
                                </div>
                                <div className="rag-gauge-stats">
                                    <div className="rag-stat-pill">
                                        <span className="value">{ragQuality?.hallucinationRate != null ? `${Math.round(ragQuality.hallucinationRate * 100)}%` : '—'}</span>
                                        <span className="label">Hallucination Rate</span>
                                    </div>
                                    <div className="rag-stat-pill">
                                        <span className="value">{ragQuality?.totalEvaluated ?? ragQuality?.totalAnswers ?? '—'}</span>
                                        <span className="label">Total Evaluated</span>
                                    </div>
                                </div>
                            </div>
                        </>
                    )}

                    {/* Users Table */}
                    <h3>Users ({wsUsers.length})</h3>
                    <table className="admin-table">
                        <thead>
                        <tr>
                            <th>Email</th>
                            <th>Name</th>
                            <th>Role</th>
                            <th>Active</th>
                            <th>Actions</th>
                        </tr>
                        </thead>
                        <tbody>
                        {wsUsers.map((u) => (
                            <tr key={u.id || u.userId}>
                                <td>{u.email}</td>
                                <td>{u.fullName || u.name || '-'}</td>
                                <td>{u.role || '-'}</td>
                                <td>
                    <span style={{color: u.active !== false ? 'var(--success)' : 'var(--danger)'}}>
                      {u.active !== false ? '✓ Active' : '✗ Inactive'}
                    </span>
                                </td>
                                <td>
                                    {u.active !== false ? (
                                        <button className="action-btn" onClick={() => handleDeactivateUser(selectedWs.id || selectedWs.workspaceId, u.id || u.userId)} title="Deactivate">
                                            <span className="material-symbols-outlined" style={{fontSize:14}}>person_off</span> Deactivate
                                        </button>
                                    ) : (
                                        <button className="action-btn" onClick={() => handleActivateUser(selectedWs.id || selectedWs.workspaceId, u.id || u.userId)} title="Reactivate" style={{color:'var(--success)'}}>
                                            <span className="material-symbols-outlined" style={{fontSize:14}}>person_add</span> Reactivate
                                        </button>
                                    )}
                                </td>
                            </tr>
                        ))}
                        </tbody>
                    </table>
                </div>
            )}

            <BottomNav onOpenDocs={() => {}} />
            <ConfirmDialog />
        </div>
    );
}