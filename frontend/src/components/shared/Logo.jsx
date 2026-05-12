/**
 * EAKP Logo — "Knowledge Spire"
 *
 * Concept: three stacked arcs represent layered knowledge (data → chunks → insight),
 * topped by a spark node — the moment an answer emerges. The mark is built to feel
 * like a calm, modern monogram rather than a tech-icon cliché.
 *
 * Props:
 *  - size      : px (default 32)
 *  - withWord  : show the "EAKP" wordmark beside the glyph
 *  - subtitle  : small caption under the wordmark (optional)
 *  - variant   : 'solid' (default, filled accent disc) | 'outline' (line only)
 */
export default function Logo({
                                 size = 32,
                                 withWord = false,
                                 subtitle,
                                 variant = 'solid',
                                 className = '',
                             }) {
    const id = `logo-grad-${variant}`;
    return (
        <span className={`eakp-logo ${className}`} aria-label="EAKP">
      <svg
          width={size}
          height={size}
          viewBox="0 0 48 48"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
          role="img"
          aria-hidden={withWord ? 'true' : undefined}
      >
        <defs>
          <linearGradient id={id} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor="#cc785c" />
            <stop offset="100%" stopColor="#a0512f" />
          </linearGradient>
          <linearGradient id={`${id}-spark`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#fbe9dc" />
            <stop offset="100%" stopColor="#f0a98a" />
          </linearGradient>
        </defs>

          {/* Disc */}
          {variant === 'solid' ? (
              <rect x="2" y="2" width="44" height="44" rx="12" fill={`url(#${id})`} />
          ) : (
              <rect x="2" y="2" width="44" height="44" rx="12" stroke="currentColor" strokeWidth="2" />
          )}

          {/* Three stacked knowledge arcs — widest at bottom, narrowing upward */}
          <g
              stroke={variant === 'solid' ? '#fbe9dc' : 'currentColor'}
              strokeWidth="2.2"
              strokeLinecap="round"
              fill="none"
          >
          {/* base arc — broad foundation */}
              <path d="M11 33 Q24 26.5 37 33" />
              {/* mid arc — refined chunks */}
              <path d="M14 27 Q24 21 34 27" opacity="0.85" />
              {/* top arc — final insight */}
              <path d="M17 21.5 Q24 17 31 21.5" opacity="0.7" />
        </g>

          {/* Spark node — the emergent answer */}
          <circle
              cx="24"
              cy="13"
              r="2.6"
              fill={variant === 'solid' ? `url(#${id}-spark)` : 'currentColor'}
          />
          {/* faint halo around spark */}
          {variant === 'solid' && (
              <circle cx="24" cy="13" r="4.6" stroke="#fbe9dc" strokeOpacity="0.35" strokeWidth="1" />
          )}
      </svg>

            {withWord && (
                <span className="eakp-logo-text">
          <span className="eakp-logo-word">EAKP</span>
                    {subtitle && <span className="eakp-logo-sub">{subtitle}</span>}
        </span>
            )}
    </span>
    );
}