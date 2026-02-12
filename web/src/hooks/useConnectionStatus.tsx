import { createContext, useContext, useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import type { VersionData, VolcanoSummary, HealthData, TpsData } from '@/transport/types';
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
  const checkedRef = useRef(false);

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
        setError('Unauthorized. Set an auth token in Settings.');
      } else {
        setError('Failed to fetch: ' + msg);
      }
    }
  }, []);

  // On first mount, probe /v1/health to check if we're on the unified server.
  // If it fails (e.g. different origin, no API here), redirect to /connect.
  useEffect(() => {
    if (checkedRef.current) return;
    checkedRef.current = true;

    const api = getApi();
    api
      .get<HealthData>('/health')
      .then((res) => {
        if (res.status === 200 && res.data && typeof res.data === 'object' && 'status' in res.data) {
          // Unified server — proceed normally
          refresh();
          intervalRef.current = setInterval(refresh, 30000);
        } else {
          // Unexpected response — not our server
          navigate('/connect', { replace: true });
        }
      })
      .catch(() => {
        // Health check failed — redirect to connect page
        navigate('/connect', { replace: true });
      });

    return () => clearInterval(intervalRef.current);
  }, [refresh, navigate]);

  return (
    <ConnectionContext.Provider value={{ online, version, volcanoes, tps, error, refresh }}>
      {children}
    </ConnectionContext.Provider>
  );
}

export function useConnectionStatus() {
  return useContext(ConnectionContext);
}
