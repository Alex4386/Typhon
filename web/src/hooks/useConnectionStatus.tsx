import { createContext, useContext, useState, useEffect, useCallback, useRef } from 'react';
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
  const intervalRef = useRef<ReturnType<typeof setInterval> | undefined>(undefined);
  const tpsIntervalRef = useRef<ReturnType<typeof setInterval> | undefined>(undefined);
  const checkedRef = useRef(false);

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

  // On first mount, probe /v1/health to check if we're on the unified server.
  // If it fails (e.g. different origin, no API here), redirect to /connect.
  useEffect(() => {
    if (checkedRef.current) return;
    checkedRef.current = true;

    const api = getApi();
    api
      .get<HealthData>('/health')
      .then(async (res) => {
        if (res.status === 200 && res.data && typeof res.data === 'object' && 'status' in res.data) {
          // Unified server — check if auth is needed before proceeding
          const authRes = await api.get<AuthData>('/auth');
          if (authRes.status === 200 && authRes.data?.authConfigured && !authRes.data?.authenticated) {
            navigate('/connect', { replace: true });
            return;
          }
          refresh();
          intervalRef.current = setInterval(refresh, 30000);
          tpsIntervalRef.current = setInterval(refreshTps, 5000);
        } else {
          // Unexpected response — not our server
          navigate('/connect', { replace: true });
        }
      })
      .catch(() => {
        // Health check failed — redirect to connect page
        navigate('/connect', { replace: true });
      });

    return () => {
      clearInterval(intervalRef.current);
      clearInterval(tpsIntervalRef.current);
    };
  }, [refresh, refreshTps, navigate]);

  return (
    <ConnectionContext.Provider value={{ online, version, volcanoes, tps, error, refresh }}>
      {children}
    </ConnectionContext.Provider>
  );
}

export function useConnectionStatus() {
  return useContext(ConnectionContext);
}
