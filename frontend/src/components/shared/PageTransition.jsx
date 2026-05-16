import { useLocation } from 'react-router-dom';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';

/**
 * Cinematic shared-axis route transition.
 * Outgoing page slides + fades out on the X axis while the new page slides
 * in from the opposite side. Subtle scale + filter give it depth without
 * feeling like a slideshow.
 *
 * Honors prefers-reduced-motion: collapses to a quick crossfade.
 */
export default function PageTransition({ children }) {
    const location = useLocation();
    const reduced = useReducedMotion();

    const variants = reduced
        ? {
            initial: { opacity: 0 },
            animate: { opacity: 1, transition: { duration: 0.18, ease: 'easeOut' } },
            exit:    { opacity: 0, transition: { duration: 0.12, ease: 'easeIn' } },
        }
        : {
            initial: { opacity: 0, x: 28, scale: 0.985, filter: 'blur(6px)' },
            animate: {
                opacity: 1, x: 0, scale: 1, filter: 'blur(0px)',
                transition: { duration: 0.52, ease: [0.22, 1, 0.36, 1] },
            },
            exit: {
                opacity: 0, x: -22, scale: 0.99, filter: 'blur(4px)',
                transition: { duration: 0.32, ease: [0.65, 0, 0.35, 1] },
            },
        };

    return (
        <AnimatePresence mode="wait" initial={false}>
            <motion.div
                key={location.pathname}
                className="page-transition"
                data-route={location.pathname}
                variants={variants}
                initial="initial"
                animate="animate"
                exit="exit"
                style={{ willChange: 'transform, opacity, filter' }}
            >
                {children}
            </motion.div>
        </AnimatePresence>
    );
}