import { useState, useEffect } from 'react';
import { getConsent, setConsent, clearFunctionalCookies } from '../../utils/cookies';
import LegalModal from '../shared/LegalModal';

/**
 * GDPR / ePrivacy cookie consent banner.
 *
 * Behaviour:
 *  - Auth cookies (strictly necessary) are ALWAYS set — no consent needed.
 *  - Functional cookies (sidebar prefs, theme, etc.) need consent.
 *  - If the user **accepts** → a persistent cookie remembers this for 365 days.
 *    Banner will not appear again.
 *  - If the user **rejects** → we only note it for the current session.
 *    The banner will reappear on the next visit (new browser session) until
 *    the user eventually accepts.
 */
export default function CookieConsent() {
  const [visible, setVisible] = useState(false);
  const [showPrivacy, setShowPrivacy] = useState(false);

  useEffect(() => {
    const consent = getConsent();
    // Show banner if:
    //  - no decision at all (first visit)
    //  - OR previously rejected (session-only, so cookie won't exist across sessions,
    //    but we also check sessionStorage for the current session)
    if (consent === 'accepted') return; // already accepted persistently — never show
    if (sessionStorage.getItem('eakp_consent_dismissed')) return; // rejected this session

    const t = setTimeout(() => setVisible(true), 800);
    return () => clearTimeout(t);
  }, []);

  const accept = () => {
    setConsent('accepted'); // persistent cookie — 365 days
    setVisible(false);
  };

  const reject = () => {
    // Do NOT persist rejection — only dismiss for this browser session
    clearFunctionalCookies();
    sessionStorage.setItem('eakp_consent_dismissed', '1');
    setVisible(false);
  };

  if (!visible) return null;

  return (
    <>
      <div className="cookie-banner" role="dialog" aria-label="Cookie consent">
        <div className="cookie-banner-content">
          <div className="cookie-banner-text">
            <strong>🍪 Cookie Notice</strong>
            <p>
              We use <em>strictly necessary</em> cookies to keep you signed in — these
              cannot be disabled. We also use optional <em>functional</em> cookies to
              remember your preferences (sidebar state, theme, etc.). You can accept or
              reject the optional cookies below. See our{' '}
              <button type="button" className="link-btn" onClick={() => setShowPrivacy(true)}>Privacy Policy</button>
              {' '}for details.
            </p>
          </div>
          <div className="cookie-banner-actions">
            <button className="btn-cookie-reject" onClick={reject}>
              Reject optional
            </button>
            <button className="btn-cookie-accept" onClick={accept}>
              Accept all
            </button>
          </div>
        </div>
      </div>
      {showPrivacy && <LegalModal type="privacy" onClose={() => setShowPrivacy(false)} />}
    </>
  );
}

