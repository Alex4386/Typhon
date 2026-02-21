import { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import type { VersionData, VolcanoSummary, HealthData, TpsData, AuthData } from '@/transport/types';
import { getApi } from '@/transport/api';

interface ConnectionState {
  online: boolean;
  version: VersionData | null;
  volcanoes: VolcanoSummary[];
  tps: TpsData | null;
  error: string;
  refresh: () => Promise<void>;
}

const ConnectionContext = createContext<ConnectionState>({
  online: false,
  version: null,
  volcanoes: [],
  tps: null,
  error: '',
  refresh: async () => {},
});

export function ConnectionProvider({ children }: { children: React.ReactNode }) {
  const navigate = useNavigate();
  const [online, setOnline] = useState(false);
  const [version, setVersion] = useState<VersionData | null>(null);
  const [volcanoes, setVolcanoes] = useState<VolcanoSummary[]>([]);
  const [tps, setTps] = useState<TpsData | null>(null);
  const [error, setError] = useState('');
  const [ready, setReady] = useState(false);

  const refreshTps = useCallback(async () => {
    const api = getApi();
    try {
      const tpsRes = await api.get<TpsData>('/tps');
      if (tpsRes.status === 200 && tpsRes.data) {
        setTps(tpsRes.data);
      }
    } catch {
      // TPS fetch failures are non-critical; silently ignore
    }
  }, []);

  const refresh = useCallback(async () => {
    const api = getApi();
    try {
      const [verRes, volRes, tpsRes] = await Promise.all([
        api.get<VersionData>('/version'),
        api.get<VolcanoSummary[]>('/volcanoes'),
        api.get<TpsData>('/tps'),
      ]);

      if (verRes.status === 200 && verRes.data) {
        setVersion(verRes.data);
        setOnline(true);
      }
      if (volRes.status === 200 && volRes.data) {
        setVolcanoes(volRes.data);
      }
      if (tpsRes.status === 200 && tpsRes.data) {
        setTps(tpsRes.data);
      }
      setError('');
    } catch (e) {
      setOnline(false);
      const msg = (e as Error).message;
      if (msg.includes('401') || msg.includes('Unauthorized')) {
        navigate('/connect', { replace: true });
      } else {
        setError('Failed to fetch: ' + msg);
      }
    }
  }, [navigate]);

  // One-time health check to verify we're on the unified server.
  useEffect(() => {
    let cancelled = false;
    const api = getApi();
    api
      .get<HealthData>('/health')
      .then(async (res) => {
        if (cancelled) return;
        if (res.status === 200 && res.data && typeof res.data === 'object' && 'status' in res.data) {
          const authRes = await api.get<AuthData>('/auth');
          if (cancelled) return;
          if (authRes.status === 200 && authRes.data?.authConfigured && !authRes.data?.authenticated) {
            navigate('/connect', { replace: true });
            return;
          }
          setReady(true);
        } else {
          navigate('/connect', { replace: true });
        }
      })
      .catch(() => {
        if (!cancelled) navigate('/connect', { replace: true });
      });

    return () => { cancelled = true; };
  }, [navigate]);

  // Periodic data refresh — only starts after health check succeeds.
  useEffect(() => {
    if (!ready) return;
    refresh();
    const interval = setInterval(refresh, 30000);
    const tpsInterval = setInterval(refreshTps, 5000);
    return () => {
      clearInterval(interval);
      clearInterval(tpsInterval);
    };
  }, [ready, refresh, refreshTps]);

  return (
    <ConnectionContext.Provider value={{ online, version, volcanoes, tps, error, refresh }}>
      {children}
    </ConnectionContext.Provider>
  );
}

export function useConnectionStatus() {
  return useContext(ConnectionContext);
}
