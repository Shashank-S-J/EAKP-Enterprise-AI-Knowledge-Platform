import { useEffect, useMemo, useRef } from 'react';
import { useAuthStore } from '../../store';

// Suggested starters — concise, action-oriented copy. The first card is the
// "featured" hero (taller, takes the left column) to break grid monotony.
const SUGGESTIONS = [
    {
        icon: 'auto_awesome',
        title: 'Surface trends across periods',
        desc: 'Compare operational metrics and explain what changed.',
        featured: true,
    },
    {
        icon: 'description',
        title: 'Summarize a document',
        desc: 'Extract the highlights with citations.',
    },
    {
        icon: 'insights',
        title: 'Generate fresh insights',
        desc: 'Visualize patterns hidden in the data.',
    },
    {
        icon: 'troubleshoot',
        title: 'Audit a query',
        desc: 'Spot anomalies or logic gaps.',
    },
];

function getGreeting() {
    const h = new Date().getHours();
    if (h < 5) return 'Working late';
    if (h < 12) return 'Good morning';
    if (h < 17) return 'Good afternoon';
    if (h < 22) return 'Good evening';
    return 'Working late';
}

function firstNameOf(user) {
    const raw = user?.fullName || user?.email || '';
    return raw.split(/[\s@]/)[0] || '';
}

export default function EmptyState({ onSelect }) {
    const user = useAuthStore((s) => s.user);
    const greeting = useMemo(() => getGreeting(), []);
    const firstName = firstNameOf(user);
    const headlineWords = ['What', 'would', 'you', 'like', 'to', 'know?'];

    const gridRef = useRef(null);

    // Magnetic hover for suggestion cards. We only attach pointer listeners
    // on fine-pointer devices with hover capability and when the user hasn't
    // requested reduced motion. This keeps touch devices and a11y-conscious
    // users on a pure CSS path, and avoids paying for matchMedia per move.
    useEffect(() => {
        if (typeof window === 'undefined' || !window.matchMedia) return;
        const fine = window.matchMedia('(hover: hover) and (pointer: fine)').matches;
        const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        if (!fine || reduced) return;

        const grid = gridRef.current;
        if (!grid) return;
        const cards = Array.from(grid.querySelectorAll('[data-magnet]'));

        const STRENGTH_X = 12;
        const STRENGTH_Y = 10;

        const bindings = cards.map((card) => {
            const onMove = (e) => {
                const r = card.getBoundingClientRect();
                const x = ((e.clientX - r.left) / r.width - 0.5) * STRENGTH_X;
                const y = ((e.clientY - r.top) / r.height - 0.5) * STRENGTH_Y;
                // Locked to pointer during direct manipulation — no transition.
                card.style.transition = 'none';
                card.style.transform = `translate3d(${x.toFixed(2)}px, ${y.toFixed(2)}px, 0)`;
            };
            const onLeave = () => {
                // Eased spring-back only on release, per ui-animation guidance.
                card.style.transition = 'transform 360ms cubic-bezier(0.22, 1, 0.36, 1)';
                card.style.transform = 'translate3d(0, 0, 0)';
            };
            card.addEventListener('pointermove', onMove);
            card.addEventListener('pointerleave', onLeave);
            return { card, onMove, onLeave };
        });

        return () => {
            bindings.forEach(({ card, onMove, onLeave }) => {
                card.removeEventListener('pointermove', onMove);
                card.removeEventListener('pointerleave', onLeave);
                card.style.transform = '';
                card.style.transition = '';
            });
        };
    }, []);

    return (
        <div className="eakp-empty">
            <div className="eakp-empty__glow eakp-empty__glow--a" aria-hidden="true" />
            <div className="eakp-empty__glow eakp-empty__glow--b" aria-hidden="true" />
            <svg
                className="eakp-empty__noise"
                aria-hidden="true"
                xmlns="http://www.w3.org/2000/svg"
            >
                <filter id="eakp-empty-noise">
                    <feTurbulence type="fractalNoise" baseFrequency="0.9" numOctaves="2" stitchTiles="stitch" />
                    <feColorMatrix values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 0.5 0" />
                </filter>
                <rect width="100%" height="100%" filter="url(#eakp-empty-noise)" />
            </svg>

            <div className="eakp-empty__content">
                <div className="eakp-empty__greet">
                    <span className="eakp-empty__greet-dot" aria-hidden="true" />
                    {greeting}{firstName ? `, ${firstName}` : ''}
                </div>

                <h1 className="eakp-empty__head">
                    {headlineWords.map((w, i) => (
                        <span
                            key={`${w}-${i}`}
                            className="eakp-empty__word"
                            style={{ animationDelay: `${140 + i * 70}ms` }}
                        >
                            {w}
                            {i < headlineWords.length - 1 ? '\u00A0' : ''}
                        </span>
                    ))}
                </h1>

                <p className="eakp-empty__sub">
                    Ask anything across your workspace. Answers cite the documents I used.
                </p>

                <div className="eakp-empty__grid" ref={gridRef}>
                    {SUGGESTIONS.map((s, i) => (
                        <button
                            key={s.title}
                            type="button"
                            data-magnet
                            className={`eakp-empty__card${s.featured ? ' eakp-empty__card--featured' : ''}`}
                            style={{ animationDelay: `${640 + i * 80}ms` }}
                            onClick={() => onSelect(`${s.title}: ${s.desc}`)}
                        >
                            <span
                                className="eakp-empty__card-icon material-symbols-outlined"
                                aria-hidden="true"
                            >
                                {s.icon}
                            </span>
                            <span className="eakp-empty__card-title">{s.title}</span>
                            <span className="eakp-empty__card-desc">{s.desc}</span>
                            <span
                                className="eakp-empty__card-arrow material-symbols-outlined"
                                aria-hidden="true"
                            >
                                arrow_outward
                            </span>
                        </button>
                    ))}
                </div>
            </div>
        </div>
    );
}