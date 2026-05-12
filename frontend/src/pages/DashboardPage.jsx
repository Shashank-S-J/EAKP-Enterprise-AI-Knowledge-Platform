import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { analytics } from '../api/client';
import { useAuthStore } from '../store';
import ThemeToggle from '../components/auth/ThemeToggle';
import '../styles/dashboard.css';

export default function DashboardPage() {
    const navigate = useNavigate();
    const { user } = useAuthStore();
    const [toastVisible, setToastVisible] = useState(false);
    const [toastProgress, setToastProgress] = useState(100);
    const [statsVisible, setStatsVisible] = useState(false);
    const [chartVisible, setChartVisible] = useState(false);
    const [feedVisible, setFeedVisible] = useState(false);
    const [searchFocused, setSearchFocused] = useState(false);
    const [hoveredBar, setHoveredBar] = useState(null);
    const [activeTimeRange, setActiveTimeRange] = useState('7D');
    const [sidebarHovered, setSidebarHovered] = useState(null);

    // Real data state
    const [overview, setOverview] = useState(null);
    const [ragQuality, setRagQuality] = useState(null);
    const [usageData, setUsageData] = useState(null);
    const [counters, setCounters] = useState({ docs: 0, chunks: 0, queries: 0 });

    // Load real analytics
    useEffect(() => {
        const loadData = async () => {
            try {
                const days = activeTimeRange === '24H' ? 1 : activeTimeRange === '7D' ? 7 : 30;
                const [ov, rq, us] = await Promise.allSettled([
                    analytics.overview(),
                    analytics.ragQuality(),
                    analytics.usage(days),
                ]);
                if (ov.status === 'fulfilled') setOverview(ov.value);
                if (rq.status === 'fulfilled') setRagQuality(rq.value);
                if (us.status === 'fulfilled') setUsageData(us.value);
                if (ov.status === 'fulfilled' || rq.status === 'fulfilled') setToastVisible(true);
            } catch { /* silent */ }
        };
        loadData();
    }, [activeTimeRange]);

    // Animate counters based on real data
    useEffect(() => {
        // eslint-disable-next-line react-hooks/set-state-in-effect -- staggered reveal animation on mount
        setStatsVisible(true);
        setTimeout(() => setChartVisible(true), 300);
        setTimeout(() => setFeedVisible(true), 600);

        if (!overview) return;
        const targets = {
            docs: overview.totalDocuments || 0,
            chunks: overview.totalChunks || 0,
            queries: overview.totalConversations || 0,
        };

        const duration = 1500;
        const steps = 40;
        const interval = duration / steps;
        let step = 0;
        const timer = setInterval(() => {
            step++;
            const progress = step / steps;
            const ease = 1 - Math.pow(1 - progress, 3);
            setCounters({
                docs: Math.round(targets.docs * ease),
                chunks: Math.round(targets.chunks * ease),
                queries: Math.round(targets.queries * ease),
            });
            if (step >= steps) clearInterval(timer);
        }, interval);
        return () => clearInterval(timer);
    }, [overview]);

    // Toast auto-dismiss
    useEffect(() => {
        if (!toastVisible) return;
        const start = Date.now();
        const total = 5000;
        const timer = setInterval(() => {
            const elapsed = Date.now() - start;
            const remaining = Math.max(0, 100 - (elapsed / total) * 100);
            setToastProgress(remaining);
            if (remaining <= 0) {
                clearInterval(timer);
                setToastVisible(false);
            }
        }, 50);
        return () => clearInterval(timer);
    }, [toastVisible]);

    // Build chart data from real usage
    const dailyQueries = usageData?.queriesPerDay || [];
    const barHeights = dailyQueries.length > 0
        ? dailyQueries.map(d => {
            const val = d.count || d.queries || 0;
            const max = Math.max(1, ...dailyQueries.map(x => x.count || x.queries || 0));
            return Math.round((val / max) * 100);
        })
        : [40, 60, 45, 80, 55, 70]; // fallback

    const navItems = [
        { icon: 'dashboard', label: 'Dashboard', active: true },
        { icon: 'chat', label: 'Chat', path: '/chat' },
        { icon: 'description', label: 'Knowledge Base', path: '/chat' },
        { icon: 'insights', label: 'Analytics', path: '/settings' },
        { icon: 'shield', label: 'Admin', path: '/admin', adminOnly: true },
    ];

    // Real activity feed from overview data
    const feedItems = [
        ...(overview?.recentActivity || []).map(a => ({
            icon: a.type === 'upload' ? 'cloud_upload' : a.type === 'chat' ? 'chat' : 'memory',
            color: a.type === 'upload' ? 'text-[#e89671]' : 'text-[#b6c2a3]',
            text: a.description || `Activity: ${a.type}`,
            time: a.time || 'Recently',
        })),
        // Fallback items if no real data
        ...(!overview?.recentActivity || overview.recentActivity.length === 0 ? [
            { icon: 'cloud_sync', color: 'text-[#e89671]', text: 'Knowledge base ready for queries.', time: 'System' },
            { icon: 'check_circle', color: 'text-[#7cb774]', text: 'All services operational.', time: 'System' },
        ] : []),
    ];

    const faithScore = ragQuality ? Math.round((ragQuality.avgFaithfulness ?? ragQuality.score ?? 0) * 100) : null;

    return (
        <div className="dashboard-root antialiased flex h-screen overflow-hidden bg-[#1f1e1c] text-[#f4f0e6]">
            {/* Sidebar */}
            <nav className="hidden md:flex flex-col fixed left-0 top-0 h-full p-4 z-40 bg-[#161513]/90 backdrop-blur-xl border-r border-[#4a4842]/20 w-64 sidebar-animate">
                <div className="mb-8 flex items-center gap-3 logo-pulse">
                    <div className="w-10 h-10 rounded-lg bg-[#cc785c] flex items-center justify-center shadow-[0_0_20px_rgba(204,120,92,0.4)]">
                        <span className="material-symbols-outlined text-white font-black" style={{ fontVariationSettings: "'FILL' 1" }}>api</span>
                    </div>
                    <div>
                        <h1 className="font-bold text-[#e89671] text-lg tracking-tight">EAKP</h1>
                        <p className="text-xs text-[#c9c5b8]">Enterprise AI</p>
                    </div>
                </div>
                <ul className="flex flex-col gap-2 flex-grow">
                    {navItems.filter(item => !item.adminOnly || user?.role === 'ADMIN').map((item, i) => (
                        <li key={item.label}>
                            <a
                                className={`px-4 py-3 flex items-center gap-3 rounded-lg transition-all duration-300 nav-item-enter ${
                                    item.active
                                        ? 'bg-[#cc785c] text-white font-bold shadow-[0_0_20px_rgba(204,120,92,0.4)] scale-[1.02]'
                                        : 'text-[#c9c5b8] hover:text-white hover:bg-[#3f3d37]/40 hover:translate-x-1'
                                } ${sidebarHovered === i && !item.active ? 'bg-[#3f3d37]/30' : ''}`}
                                style={{ animationDelay: `${i * 80}ms` }}
                                onMouseEnter={() => setSidebarHovered(i)}
                                onMouseLeave={() => setSidebarHovered(null)}
                                href="#"
                                onClick={(e) => { e.preventDefault(); if (item.path) navigate(item.path); }}
                            >
                                <span className="material-symbols-outlined" style={{ fontVariationSettings: `'FILL' ${item.active ? 1 : 0}` }}>{item.icon}</span>
                                <span className="text-[13px] font-medium tracking-wider uppercase">{item.label}</span>
                            </a>
                        </li>
                    ))}
                </ul>
                <div className="mt-auto pt-4 border-t border-[#4a4842]/20">
                    <ul className="flex flex-col gap-2">
                        {[{ icon: 'help', label: 'Support', path: '/help' }, { icon: 'settings', label: 'Settings', path: '/settings' }].map((item) => (
                            <li key={item.label}>
                                <a className="text-[#c9c5b8] hover:text-white px-4 py-3 flex items-center gap-3 transition-all hover:bg-[#3f3d37]/30 rounded-lg duration-300 hover:translate-x-1" href="#"
                                   onClick={(e) => { e.preventDefault(); if (item.path) navigate(item.path); }}>
                                    <span className="material-symbols-outlined" style={{ fontVariationSettings: "'FILL' 0" }}>{item.icon}</span>
                                    <span className="text-[13px] font-medium tracking-wider uppercase">{item.label}</span>
                                </a>
                            </li>
                        ))}
                    </ul>
                </div>
            </nav>

            {/* Main Content */}
            <main className="flex-1 flex flex-col md:ml-64 h-full relative overflow-y-auto scroll-smooth">
                {/* Header */}
                <header className="sticky top-0 z-50 flex justify-between items-center w-full px-6 h-16 bg-[#1f1e1c]/80 backdrop-blur-xl border-b border-[#4a4842]/20">
                    <div className="flex items-center w-1/3">
                        <div className={`relative w-full max-w-md hidden md:block transition-all duration-300 ${searchFocused ? 'scale-105' : ''}`}>
                            <span className="material-symbols-outlined absolute left-3 top-1/2 -translate-y-1/2 text-[#c9c5b8]">search</span>
                            <input
                                className="w-full bg-[#34322d] border border-[#4a4842] rounded-full pl-10 pr-4 py-2 text-[#f4f0e6] text-sm focus:outline-none focus:border-[#b6c2a3] focus:ring-2 focus:ring-[#b6c2a3]/30 focus:shadow-[0_0_20px_rgba(182,194,163,0.15)] transition-all placeholder:text-[#8b8778]"
                                placeholder="Search knowledge base..."
                                aria-label="Search knowledge base"
                                onFocus={() => setSearchFocused(true)}
                                onBlur={() => setSearchFocused(false)}
                            />
                        </div>
                    </div>
                    <div className="flex items-center gap-4">
                        <ThemeToggle />
                        <button className="w-10 h-10 rounded-full flex items-center justify-center text-[#c9c5b8] hover:bg-[#34322d]/50 transition-all duration-200 hover:text-[#e89671] hover:scale-110 notification-bell"
                                onClick={() => navigate('/settings')}>
                            <span className="material-symbols-outlined">settings</span>
                        </button>
                        <div className="w-8 h-8 rounded-full overflow-hidden border-2 border-[#4a4842] ml-2 hover:border-[#e89671] hover:scale-110 transition-all duration-300 hover:shadow-[0_0_12px_rgba(232,150,113,0.4)]"
                             onClick={() => navigate('/settings')}
                             onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') navigate('/settings'); }}
                             role="button"
                             tabIndex={0}
                             aria-label="User profile"
                             style={{ cursor: 'pointer' }}>
                            <div className="w-full h-full bg-gradient-to-br from-[#cc785c] to-[#b6c2a3] flex items-center justify-center text-xs font-bold text-white">
                                {user?.fullName?.charAt(0)?.toUpperCase() || 'U'}
                            </div>
                        </div>
                    </div>
                </header>

                {/* Dashboard Content */}
                <div className="p-8 flex flex-col gap-4">
                    <div className={`flex justify-between items-end mb-4 transition-all duration-700 ${statsVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-4'}`}>
                        <div>
                            <h2 className="text-[40px] leading-[48px] font-bold tracking-tight text-[#f4f0e6] mb-2">Enterprise AI Overview</h2>
                            <p className="text-lg text-[#c9c5b8]">Platform metrics and active knowledge graph status.</p>
                        </div>
                        <button className="bg-[#e89671] text-[#a0512f] font-medium text-[13px] tracking-wider uppercase px-6 py-3 rounded-lg hover:shadow-[0_0_25px_rgba(232,150,113,0.5)] hover:scale-105 active:scale-95 transition-all duration-300 flex items-center gap-2 group"
                                onClick={() => navigate('/chat')}>
                            <span className="material-symbols-outlined text-sm group-hover:rotate-90 transition-transform duration-300" style={{ fontVariationSettings: "'FILL' 1" }}>chat</span>
                            Start Chat
                        </button>
                    </div>

                    {/* Stats Grid - Real Data */}
                    <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                        {[
                            { icon: 'description', color: 'cyan', label: 'Documents', value: counters.docs.toLocaleString(), change: overview ? '+' + (overview.totalDocuments || 0) : 'â€”' },
                            { icon: 'hub', color: 'purple', label: 'Vector Chunks', value: counters.chunks >= 1000 ? (counters.chunks / 1000).toFixed(1) + 'K' : counters.chunks.toString(), change: faithScore != null ? `${faithScore}% faithful` : 'â€”' },
                            { icon: 'forum', color: 'orange', label: 'Conversations', value: counters.queries.toLocaleString(), change: usageData?.activeUsers ? `${usageData.activeUsers} active users` : 'â€”' },
                        ].map((stat, i) => (
                            <div
                                key={stat.label}
                                className={`bg-[#2a2926]/60 backdrop-blur-[12px] border border-[#4a4842]/30 rounded-xl p-6 hover:bg-[#2a2926]/80 transition-all duration-500 group relative overflow-hidden cursor-pointer hover:scale-[1.02] hover:shadow-[0_10px_40px_rgba(0,0,0,0.3)] stat-card ${statsVisible ? 'stat-card-visible' : ''}`}
                                style={{ transitionDelay: `${i * 150}ms` }}
                            >
                                <div className={`absolute top-0 right-0 w-32 h-32 rounded-full blur-3xl -mr-10 -mt-10 transition-all duration-700 group-hover:scale-150 ${
                                    i === 0 ? 'bg-[#b6c2a3]/10 group-hover:bg-[#b6c2a3]/25' : i === 1 ? 'bg-[#cc785c]/10 group-hover:bg-[#cc785c]/25' : 'bg-[#e7c365]/10 group-hover:bg-[#e7c365]/25'
                                }`} />
                                <div className="flex justify-between items-start mb-4 relative z-10">
                                    <div className={`w-10 h-10 rounded-lg flex items-center justify-center border transition-all duration-300 group-hover:scale-110 group-hover:rotate-6 ${
                                        i === 0 ? 'bg-[#b6c2a3]/10 border-[#b6c2a3]/20' : i === 1 ? 'bg-[#cc785c]/10 border-[#cc785c]/20' : 'bg-[#e7c365]/10 border-[#e7c365]/20'
                                    }`}>
                                        <span className={`material-symbols-outlined ${i === 0 ? 'text-[#b6c2a3]' : i === 1 ? 'text-[#e89671]' : 'text-[#e7c365]'}`}>{stat.icon}</span>
                                    </div>
                                    <span className={`text-[13px] font-medium ${i === 0 ? 'text-[#b6c2a3]' : i === 1 ? 'text-[#e89671]' : 'text-[#e7c365]'} bg-white/5 px-2 py-1 rounded group-hover:scale-110 transition-transform`}>{stat.change}</span>
                                </div>
                                <h3 className="text-sm text-[#c9c5b8] mb-1 relative z-10">{stat.label}</h3>
                                <div className="text-[32px] leading-[40px] font-semibold text-[#f4f0e6] relative z-10 tabular-nums">{stat.value}</div>
                            </div>
                        ))}
                    </div>

                    {/* Bento Grid */}
                    <div className="grid grid-cols-1 lg:grid-cols-3 gap-4 mt-4">
                        {/* Chart */}
                        <div className={`lg:col-span-2 bg-[#2a2926]/40 backdrop-blur-[12px] border border-[#4a4842]/20 rounded-xl p-6 flex flex-col transition-all duration-700 ${chartVisible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-6'}`}>
                            <div className="flex justify-between items-center mb-6">
                                <h3 className="text-2xl font-semibold text-[#f4f0e6]">Query Activity</h3>
                                <div className="flex gap-2">
                                    {['24H', '7D', '30D'].map((t) => (
                                        <button
                                            key={t}
                                            onClick={() => setActiveTimeRange(t)}
                                            className={`px-3 py-1 text-xs font-medium rounded-md transition-all duration-300 ${
                                                activeTimeRange === t
                                                    ? 'bg-[#3f3d37] text-white shadow-[0_0_10px_rgba(232,150,113,0.2)] scale-105'
                                                    : 'text-[#8b8778] hover:text-white'
                                            }`}
                                        >
                                            {t}
                                        </button>
                                    ))}
                                </div>
                            </div>
                            <div className="flex-1 min-h-[300px] border border-dashed border-[#4a4842]/30 rounded-lg flex items-end bg-[#161513]/50 relative overflow-hidden px-8 pb-4">
                                <div className="absolute inset-0 opacity-10" style={{ backgroundImage: 'linear-gradient(#4a4842 1px, transparent 1px), linear-gradient(90deg, #4a4842 1px, transparent 1px)', backgroundSize: '20px 20px' }} />
                                {barHeights.map((h, i) => (
                                    <div
                                        key={i}
                                        className="flex-1 mx-1 rounded-t-md cursor-pointer transition-all duration-500 relative group/bar"
                                        style={{ height: `${h}%`, background: hoveredBar === i ? '#b6c2a3' : 'rgba(182,194,163,0.7)', transitionTimingFunction: 'cubic-bezier(0.34, 1.56, 0.64, 1)' }}
                                        onMouseEnter={() => setHoveredBar(i)}
                                        onMouseLeave={() => setHoveredBar(null)}
                                    >
                                        {hoveredBar === i && (
                                            <div className="absolute -top-10 left-1/2 -translate-x-1/2 bg-[#1f1e1c] text-[#f4f0e6] text-xs px-3 py-1.5 rounded-lg shadow-xl whitespace-nowrap border border-[#4a4842]/50 animate-fade-in">
                                                {dailyQueries[i] ? (dailyQueries[i].count || dailyQueries[i].queries || 0) + ' queries' : `${h}%`}
                                            </div>
                                        )}
                                        <div className={`absolute bottom-0 left-0 right-0 bg-gradient-to-t from-[#b6c2a3]/20 to-transparent h-full rounded-t-md opacity-0 group-hover/bar:opacity-100 transition-opacity`} />
                                    </div>
                                ))}
                            </div>
                        </div>

                        {/* Activity Feed */}
                        <div className={`bg-[#2a2926]/40 backdrop-blur-[12px] border border-[#4a4842]/20 rounded-xl p-6 flex flex-col h-[400px] transition-all duration-700 ${feedVisible ? 'opacity-100 translate-x-0' : 'opacity-0 translate-x-6'}`}>
                            <div className="flex justify-between items-center mb-6">
                                <h3 className="text-2xl font-semibold text-[#f4f0e6]">System Activity</h3>
                                <button className="text-[#c9c5b8] hover:text-[#e89671] transition-colors hover:rotate-90 duration-300">
                                    <span className="material-symbols-outlined">more_horiz</span>
                                </button>
                            </div>
                            <div className="flex-1 overflow-y-auto pr-2 space-y-4">
                                {feedItems.map((item, i) => (
                                    <div key={i} className="flex gap-4 relative feed-item hover:bg-white/[0.02] rounded-lg p-2 -m-2 transition-colors" style={{ animationDelay: `${800 + i * 150}ms` }}>
                                        {i < feedItems.length - 1 && <div className="absolute left-[19px] top-10 bottom-[-8px] w-[1px] bg-[#4a4842]/30" />}
                                        <div className="w-8 h-8 rounded-full bg-[#3f3d37] flex items-center justify-center flex-shrink-0 z-10 border border-[#4a4842]/50">
                                            <span className={`material-symbols-outlined text-[16px] ${item.color}`}>{item.icon}</span>
                                        </div>
                                        <div>
                                            <p className="text-sm text-[#f4f0e6]">{item.text}</p>
                                            <p className="text-[11px] text-[#8b8778] mt-1 font-mono">{item.time}</p>
                                        </div>
                                    </div>
                                ))}
                            </div>
                            <button className="mt-4 w-full py-2 border border-[#4a4842]/50 rounded-lg text-[13px] font-medium text-[#c9c5b8] hover:bg-[#3f3d37]/30 hover:text-white hover:border-[#e89671]/40 hover:shadow-[0_0_15px_rgba(232,150,113,0.1)] transition-all duration-300 active:scale-95"
                                    onClick={() => navigate('/settings')}>
                                View Full Analytics
                            </button>
                        </div>
                    </div>
                </div>
            </main>

            {/* Toast */}
            {toastVisible && (
                <div className="fixed bottom-6 right-6 w-80 bg-[#34322d]/90 backdrop-blur-[20px] rounded-lg shadow-[0_20px_25px_-5px_rgba(0,0,0,0.4)] border-l-4 border-l-[#b6c2a3] border-y border-r border-y-[#4a4842]/30 border-r-[#4a4842]/30 overflow-hidden z-50 flex flex-col toast-enter">
                    <div className="p-4 flex items-start gap-3">
                        <span className="material-symbols-outlined text-[#b6c2a3] animate-bounce" style={{ fontVariationSettings: "'FILL' 1", animationDuration: '2s' }}>check_circle</span>
                        <div className="flex-1">
                            <h4 className="text-[13px] font-bold text-[#f4f0e6] uppercase tracking-wider">Data Loaded</h4>
                            <p className="text-sm text-[#c9c5b8] mt-1">Analytics refreshed successfully.</p>
                        </div>
                        <button onClick={() => setToastVisible(false)} className="text-[#c9c5b8] hover:text-white hover:rotate-90 transition-all duration-300">
                            <span className="material-symbols-outlined text-[18px]">close</span>
                        </button>
                    </div>
                    <div className="h-1 bg-[#3f3d37] w-full">
                        <div className="h-full bg-[#b6c2a3] transition-all duration-100 ease-linear" style={{ width: `${toastProgress}%` }} />
                    </div>
                </div>
            )}

            {/* Floating particles background effect */}
            <div className="fixed inset-0 pointer-events-none z-0 overflow-hidden">
                {[...new Array(6)].map((_, i) => (
                    <div key={i} className="particle" style={{ left: `${15 + i * 15}%`, animationDelay: `${i * 2}s`, animationDuration: `${8 + i * 2}s` }} />
                ))}
            </div>
        </div>
    );
}