import { useState, useEffect, useRef } from 'react';

/**
 * Debounce a value — useful for search inputs, API calls, etc.
 */
export function useDebounce(value, delay = 300) {
    const [debounced, setDebounced] = useState(value);

    useEffect(() => {
        const timer = setTimeout(() => setDebounced(value), delay);
        return () => clearTimeout(timer);
    }, [value, delay]);

    return debounced;
}

/**
 * Returns true if the user prefers reduced motion.
 */
export function usePrefersReducedMotion() {
    const [reduced, setReduced] = useState(() => {
        if (typeof window === 'undefined') return false;
        return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    });

    useEffect(() => {
        const mq = window.matchMedia('(prefers-reduced-motion: reduce)');
        const handler = (e) => setReduced(e.matches);
        mq.addEventListener('change', handler);
        return () => mq.removeEventListener('change', handler);
    }, []);

    return reduced;
}

/**
 * Intersection observer hook for scroll-triggered animations.
 */
export function useInView(options = {}) {
    const ref = useRef(null);
    const [inView, setInView] = useState(false);

    useEffect(() => {
        const el = ref.current;
        if (!el) return;
        const observer = new IntersectionObserver(
            ([entry]) => { if (entry.isIntersecting) { setInView(true); observer.unobserve(el); } },
            { threshold: 0.1, ...options }
        );
        observer.observe(el);
        return () => observer.disconnect();
        // eslint-disable-next-line react-hooks/exhaustive-deps -- options is a config arg; intentional one-shot observer
    }, []);

    return [ref, inView];
}

/**
 * Scroll-reveal — attaches IntersectionObserver and toggles
 * `.is-visible` on the element when it enters the viewport.
 * Pair with `.eakp-reveal` utility class.
 */
export function useScrollReveal(options = {}) {
    const ref = useRef(null);

    useEffect(() => {
        const el = ref.current;
        if (!el) return undefined;
        const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        if (reduced) { el.classList.add('is-visible'); return undefined; }

        const observer = new IntersectionObserver(
            (entries) => {
                entries.forEach((entry) => {
                    if (entry.isIntersecting) {
                        entry.target.classList.add('is-visible');
                        observer.unobserve(entry.target);
                    }
                });
            },
            { threshold: 0.12, rootMargin: '0px 0px -8% 0px', ...options }
        );
        observer.observe(el);
        return () => observer.disconnect();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    return ref;
}

/**
 * Magnetic hover — pointer-driven translate on fine-pointer + hover devices.
 * Honors prefers-reduced-motion. Returns a ref to attach to the target.
 *
 * @param {number} strength — translation magnitude factor (0.2–0.6 typical)
 */
export function useMagnetic(strength = 0.35) {
    const ref = useRef(null);

    useEffect(() => {
        const el = ref.current;
        if (!el || typeof window === 'undefined') return undefined;

        const fineHover = window.matchMedia('(hover: hover) and (pointer: fine)').matches;
        const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        if (!fineHover || reduced) return undefined;

        const onMove = (e) => {
            const rect = el.getBoundingClientRect();
            const x = (e.clientX - rect.left - rect.width / 2) * strength;
            const y = (e.clientY - rect.top - rect.height / 2) * strength;
            el.style.transition = 'none';
            el.style.transform = `translate3d(${x}px, ${y}px, 0)`;
        };
        const onLeave = () => {
            el.style.transition = 'transform 360ms cubic-bezier(0.34, 1.56, 0.64, 1)';
            el.style.transform = 'translate3d(0, 0, 0)';
        };

        el.addEventListener('pointermove', onMove);
        el.addEventListener('pointerleave', onLeave);
        return () => {
            el.removeEventListener('pointermove', onMove);
            el.removeEventListener('pointerleave', onLeave);
            el.style.transform = '';
            el.style.transition = '';
        };
    }, [strength]);

    return ref;
}

/**
 * Spotlight — tracks pointer position and writes --mx / --my CSS vars
 * to the element. Pair with `.eakp-spotlight` utility.
 */
export function useSpotlight() {
    const ref = useRef(null);

    useEffect(() => {
        const el = ref.current;
        if (!el) return undefined;
        const fineHover = window.matchMedia('(hover: hover) and (pointer: fine)').matches;
        if (!fineHover) return undefined;

        const onMove = (e) => {
            const rect = el.getBoundingClientRect();
            el.style.setProperty('--mx', `${e.clientX - rect.left}px`);
            el.style.setProperty('--my', `${e.clientY - rect.top}px`);
        };
        el.addEventListener('pointermove', onMove);
        return () => el.removeEventListener('pointermove', onMove);
    }, []);

    return ref;
}

/**
 * Parallax — light scroll-driven translateY using rAF + IntersectionObserver gate.
 * Honors prefers-reduced-motion.
 */
export function useParallax(speed = 0.25) {
    const ref = useRef(null);

    useEffect(() => {
        const el = ref.current;
        if (!el) return undefined;
        const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        if (reduced) return undefined;

        let raf = 0;
        let inView = false;

        const update = () => {
            const rect = el.getBoundingClientRect();
            const center = rect.top + rect.height / 2 - window.innerHeight / 2;
            el.style.transform = `translate3d(0, ${(-center * speed).toFixed(1)}px, 0)`;
            raf = 0;
        };
        const schedule = () => { if (!raf && inView) raf = requestAnimationFrame(update); };
        const io = new IntersectionObserver(
            ([entry]) => { inView = entry.isIntersecting; if (inView) schedule(); },
            { threshold: 0 }
        );
        io.observe(el);
        window.addEventListener('scroll', schedule, { passive: true });

        return () => {
            io.disconnect();
            window.removeEventListener('scroll', schedule);
            if (raf) cancelAnimationFrame(raf);
            el.style.transform = '';
        };
    }, [speed]);

    return ref;
}

/**
 * Returns a stable scroll-reveal helper for lists/groups.
 * Apply `.eakp-reveal` + a stagger class to children; this hook
 * just gives you a parent ref that triggers all descendants at once.
 */
export function useGroupReveal(options = {}) {
    const ref = useRef(null);

    useEffect(() => {
        const el = ref.current;
        if (!el) return undefined;
        const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        if (reduced) {
            el.querySelectorAll('.eakp-reveal').forEach((c) => c.classList.add('is-visible'));
            return undefined;
        }

        const io = new IntersectionObserver(
            ([entry]) => {
                if (entry.isIntersecting) {
                    el.querySelectorAll('.eakp-reveal').forEach((c) => c.classList.add('is-visible'));
                    io.disconnect();
                }
            },
            { threshold: 0.12, ...options }
        );
        io.observe(el);
        return () => io.disconnect();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    return ref;
}

/**
 * Tiny helper to compose multiple refs (e.g. useScrollReveal + useMagnetic).
 * Returns a callback ref that forwards the node to every passed ref.
 */
export function mergeRefs(...refs) {
    return (node) => {
        refs.forEach((ref) => {
            if (!ref) return;
            if (typeof ref === 'function') ref(node);
            else ref.current = node;
        });
    };
}