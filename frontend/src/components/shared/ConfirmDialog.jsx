import { useState, useCallback, useEffect, useRef } from 'react';

/**
 * Accessible confirmation dialog to replace browser confirm().
 * Usage:
 *   const { ConfirmDialog, confirm } = useConfirmDialog();
 *   const ok = await confirm('Are you sure?', 'This action cannot be undone.');
 */
export function useConfirmDialog() {
    const [state, setState] = useState(null);

    const confirm = useCallback((title, message) => {
        return new Promise((resolve) => {
            setState({ title, message, resolve });
        });
    }, []);

    const handleConfirm = () => { state?.resolve(true); setState(null); };
    const handleCancel = () => { state?.resolve(false); setState(null); };

    function ConfirmDialog() {
        const dialogRef = useRef(null);

        // Trap focus within dialog and handle Escape
        useEffect(() => {
            if (!state) return;
            const dialog = dialogRef.current;
            if (!dialog) return;
            const focusable = dialog.querySelectorAll('button, [tabindex]:not([tabindex="-1"])');
            if (focusable.length) focusable[0].focus();

            const handleKey = (e) => {
                if (e.key === 'Escape') { handleCancel(); return; }
                if (e.key === 'Tab') {
                    const first = focusable[0];
                    const last = focusable[focusable.length - 1];
                    if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
                    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
                }
            };
            document.addEventListener('keydown', handleKey);
            return () => document.removeEventListener('keydown', handleKey);
            // eslint-disable-next-line react-hooks/exhaustive-deps -- state object updates trigger handler refresh; deeper deps unnecessary
        }, [state]);

        if (!state) return null;
        return (
            <div className="confirm-overlay" onClick={handleCancel}>
                <div ref={dialogRef} className="confirm-dialog" onClick={(e) => e.stopPropagation()} role="alertdialog" aria-modal="true" aria-labelledby="confirm-title" aria-describedby="confirm-msg">
                    <h3 id="confirm-title">
                        <span className="material-symbols-outlined" style={{ fontSize: 20, color: 'var(--warning)' }}>warning</span>
                        {state.title}
                    </h3>
                    <p id="confirm-msg">{state.message}</p>
                    <div className="confirm-actions">
                        <button className="confirm-cancel-btn" onClick={handleCancel}>Cancel</button>
                        <button className="confirm-ok-btn" onClick={handleConfirm} autoFocus>Confirm</button>
                    </div>
                </div>
            </div>
        );
    }

    return { ConfirmDialog, confirm };
}
