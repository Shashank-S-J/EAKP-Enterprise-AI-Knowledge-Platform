import { useNetworkStatus } from '../../hooks/useNetworkStatus';

export default function NetworkBanner() {
  const online = useNetworkStatus();

  if (online) return null;

  return (
    <div className="network-banner" role="alert" aria-live="assertive">
      <span className="material-symbols-outlined">wifi_off</span>
      <span>You're offline. Some features may be unavailable.</span>
    </div>
  );
}

