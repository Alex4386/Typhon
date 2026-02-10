import { useEffect, useRef, useState, useCallback } from 'react';
import type { StatusData } from '../transport/types';
import { getApi } from '../transport/api';
import { getAuthToken, setAuthToken, clearAuthToken } from '../transport/auth';

import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { RefreshCw, Wifi, WifiOff, KeyRound } from 'lucide-react';

type TransportState = 'http' | 'offline';

export default function Dashboard() {
  const [status, setStatus] = useState<StatusData | null>(null);
  const [transport, setTransport] = useState<TransportState>('offline');
  const [showAuth, setShowAuth] = useState(false);
  const [tokenInput, setTokenInput] = useState('');
  const [error, setError] = useState('');
  const [logs, setLogs] = useState<string[]>([]);
  const logRef = useRef<HTMLDivElement>(null);

  const log = useCallback((msg: string) => {
    const ts = new Date().toLocaleTimeString();
    setLogs((prev) => [...prev, `[${ts}] ${msg}`]);
  }, []);

  const refreshStatus = useCallback(async () => {
    try {
      const api = getApi();
      const res = await api.get<StatusData>('/api/status');
      if (res.status === 200 && res.data) {
        setStatus(res.data);
        setTransport('http');
        log('Status refreshed');
      } else if (res.status === 401) {
        setError('Unauthorized. Set an auth token.');
        log('Status request returned 401 Unauthorized');
      } else {
        log('Status request returned ' + res.status);
      }
    } catch (e) {
      setError('Failed to fetch status: ' + (e as Error).message);
      log('Error: ' + (e as Error).message);
      setTransport('offline');
    }
  }, [log]);

  const applyToken = useCallback(() => {
    const token = tokenInput.trim();
    if (token) {
      setAuthToken(token);
      log('Auth token set');
      refreshStatus();
    }
  }, [tokenInput, log, refreshStatus]);

  const clearToken = useCallback(() => {
    clearAuthToken();
    setTokenInput('');
    log('Auth token cleared');
  }, [log]);

  useEffect(() => {
    log('Typhon Dashboard loaded');
    refreshStatus();
    const interval = setInterval(refreshStatus, 30000);
    return () => clearInterval(interval);
  }, [log, refreshStatus]);

  useEffect(() => {
    if (logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight;
    }
  }, [logs]);

  useEffect(() => {
    if (!error) return;
    const t = setTimeout(() => setError(''), 5000);
    return () => clearTimeout(t);
  }, [error]);

  useEffect(() => {
    const saved = getAuthToken();
    if (saved) setTokenInput(saved);
  }, []);

  const transportBadge = () => {
    if (transport === 'http') return <Badge variant="secondary"><Wifi className="size-3" /> HTTP</Badge>;
    return <Badge variant="outline"><WifiOff className="size-3" /> Offline</Badge>;
  };

  return (
    <div className="min-h-screen">
      {/* Header */}
      <header className="border-b border-border bg-card px-6 py-4 flex items-center justify-between">
        <h1 className="text-xl font-bold text-primary tracking-wide">Typhon Dashboard</h1>
        {transportBadge()}
      </header>

      {/* Auth bar */}
      {showAuth && (
        <div className="border-b border-border bg-card px-6 py-3 flex items-center gap-2">
          <Input
            type="password"
            placeholder="Bearer token or Basic credentials..."
            value={tokenInput}
            onChange={(e) => setTokenInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && applyToken()}
            className="flex-1"
          />
          <Button size="sm" onClick={applyToken}>Set Token</Button>
          <Button variant="secondary" size="sm" onClick={clearToken}>Clear</Button>
        </div>
      )}

      <main className="max-w-3xl mx-auto p-6 space-y-4">
        {error && (
          <div className="rounded-lg border border-destructive bg-destructive/10 p-4 text-sm text-destructive">
            {error}
          </div>
        )}

        {/* Server Status */}
        <Card>
          <CardHeader>
            <CardTitle>Server Status</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              {[
                { label: 'Status', value: status?.status ?? '--' },
                { label: 'Plugin', value: status?.plugin ?? '--' },
                { label: 'Version', value: status?.version ?? '--' },
                { label: 'Volcanoes', value: status?.volcanoCount ?? '--' },
              ].map((item) => (
                <div key={item.label} className="rounded-lg bg-muted p-4 text-center">
                  <div className="text-xs uppercase tracking-wider text-muted-foreground mb-1">{item.label}</div>
                  <div className="text-lg font-bold text-info">{item.value}</div>
                </div>
              ))}
            </div>
            <div className="flex gap-2">
              <Button size="sm" onClick={refreshStatus}><RefreshCw className="size-4" /> Refresh</Button>
              <Button variant="secondary" size="sm" onClick={() => setShowAuth(!showAuth)}><KeyRound className="size-4" /> Auth</Button>
            </div>
          </CardContent>
        </Card>

        {/* Connection Log */}
        <Card>
          <CardHeader>
            <CardTitle>Connection Log</CardTitle>
          </CardHeader>
          <CardContent>
            <div
              ref={logRef}
              className="rounded-md bg-muted p-3 font-mono text-xs max-h-48 overflow-y-auto text-muted-foreground break-all"
            >
              {logs.map((line, i) => (
                <div key={i}>{line}</div>
              ))}
            </div>
          </CardContent>
        </Card>
      </main>
    </div>
  );
}
