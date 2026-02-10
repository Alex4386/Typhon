import { useEffect, useRef, useState, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { StatusData } from '../transport/types';
import { setAuthToken } from '../transport/auth';
import { getApi, setHttpApiBase } from '../transport/api';

import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardAction } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Loader2, RefreshCw, Wifi, WifiOff } from 'lucide-react';

type Phase = 'form' | 'connecting' | 'connected' | 'error';

export default function Connect() {
  const [searchParams] = useSearchParams();
  const [phase, setPhase] = useState<Phase>('form');
  const [status, setStatus] = useState<StatusData | null>(null);
  const [logs, setLogs] = useState<string[]>([]);
  const [errorMsg, setErrorMsg] = useState('');
  const logRef = useRef<HTMLDivElement>(null);
  const attempted = useRef(false);

  const [serverInput, setServerInput] = useState(searchParams.get('server') || '');
  const [tokenInput, setTokenInput] = useState(searchParams.get('token') || '');

  const log = useCallback((msg: string) => {
    const ts = new Date().toLocaleTimeString();
    setLogs((prev) => [...prev, `[${ts}] ${msg}`]);
  }, []);

  useEffect(() => {
    if (logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight;
    }
  }, [logs]);

  const doConnect = useCallback(async (server: string, token: string) => {
    setPhase('connecting');
    setErrorMsg('');

    try {
      if (token) {
        log('Setting auth token...');
        setAuthToken('Bearer ' + token);
      }

      const apiBase = server.replace(/\/+$/, '') + '/api';
      setHttpApiBase(apiBase);
      log('Connecting to ' + server + '...');

      setPhase('connected');

      log('Fetching server status...');
      const api = getApi();
      const res = await api.get<StatusData>('/api/status');
      if (res.status === 200 && res.data) {
        setStatus(res.data);
        log('Server: ' + res.data.plugin + ' v' + res.data.version);
      } else {
        log('Status request returned ' + res.status);
      }
    } catch (e) {
      setPhase('error');
      setErrorMsg((e as Error).message);
      log('Connection failed: ' + (e as Error).message);
    }
  }, [log]);

  // Auto-connect from URL params
  useEffect(() => {
    if (attempted.current) return;

    const server = searchParams.get('server');
    const token = searchParams.get('token') || '';

    if (server) {
      attempted.current = true;
      doConnect(server, token);
    }
  }, [searchParams, doConnect]);

  const handleSubmit = useCallback(() => {
    const server = serverInput.trim();
    if (!server) return;
    attempted.current = true;
    doConnect(server, tokenInput.trim());
  }, [serverInput, tokenInput, doConnect]);

  const refreshStatus = useCallback(async () => {
    try {
      const api = getApi();
      const res = await api.get<StatusData>('/api/status');
      if (res.status === 200 && res.data) {
        setStatus(res.data);
        log('Status refreshed');
      }
    } catch (e) {
      log('Error: ' + (e as Error).message);
    }
  }, [log]);

  useEffect(() => {
    if (phase !== 'connected') return;
    const interval = setInterval(refreshStatus, 30000);
    return () => clearInterval(interval);
  }, [phase, refreshStatus]);

  const transportBadge = () => {
    if (phase === 'connected') {
      return <Badge variant="secondary"><Wifi className="size-3" /> HTTP</Badge>;
    }
    if (phase === 'connecting') {
      return <Badge variant="secondary"><Loader2 className="size-3 animate-spin" /> Connecting</Badge>;
    }
    if (phase === 'error') return <Badge variant="destructive"><WifiOff className="size-3" /> Failed</Badge>;
    return <Badge variant="outline"><WifiOff className="size-3" /> Disconnected</Badge>;
  };

  return (
    <div className="min-h-screen">
      {/* Header */}
      <header className="border-b border-border bg-card px-6 py-4 flex items-center justify-between">
        <h1 className="text-xl font-bold text-primary tracking-wide">Typhon Remote</h1>
        {transportBadge()}
      </header>

      <main className="max-w-3xl mx-auto p-6 space-y-4">
        {errorMsg && (
          <div className="rounded-lg border border-destructive bg-destructive/10 p-4 text-sm text-destructive">
            {errorMsg}
          </div>
        )}

        {/* Connect form */}
        {phase === 'form' && (
          <Card>
            <CardHeader>
              <CardTitle>Connect to Server</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <label className="text-sm font-medium text-muted-foreground">Server URL</label>
                <Input
                  placeholder="http://your-server:18080"
                  value={serverInput}
                  onChange={(e) => setServerInput(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && handleSubmit()}
                />
              </div>
              <div className="space-y-2">
                <label className="text-sm font-medium text-muted-foreground">Auth Token (optional)</label>
                <Input
                  type="password"
                  placeholder="Bearer token..."
                  value={tokenInput}
                  onChange={(e) => setTokenInput(e.target.value)}
                  onKeyDown={(e) => e.key === 'Enter' && handleSubmit()}
                />
              </div>
              <Button onClick={handleSubmit}>Connect</Button>
              <p className="text-xs text-muted-foreground">
                Run <code className="bg-muted px-1 rounded">/typhon web</code> in Minecraft to get a connect link with a temporary token.
              </p>
            </CardContent>
          </Card>
        )}

        {/* Connecting state */}
        {phase === 'connecting' && (
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <Loader2 className="size-4 animate-spin" /> Connecting to {serverInput}...
              </CardTitle>
            </CardHeader>
          </Card>
        )}

        {/* Connected — server status */}
        {phase === 'connected' && status && (
          <Card>
            <CardHeader>
              <CardTitle>Server Status</CardTitle>
              <CardAction>
                <Button variant="ghost" size="sm" onClick={refreshStatus}>
                  <RefreshCw className="size-4" /> Refresh
                </Button>
              </CardAction>
            </CardHeader>
            <CardContent>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                {[
                  { label: 'Status', value: status.status },
                  { label: 'Plugin', value: status.plugin },
                  { label: 'Version', value: status.version },
                  { label: 'Volcanoes', value: status.volcanoCount },
                ].map((item) => (
                  <div key={item.label} className="rounded-lg bg-muted p-4 text-center">
                    <div className="text-xs uppercase tracking-wider text-muted-foreground mb-1">{item.label}</div>
                    <div className="text-lg font-bold text-info">{item.value}</div>
                  </div>
                ))}
              </div>
            </CardContent>
          </Card>
        )}

        {/* Connection log */}
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
