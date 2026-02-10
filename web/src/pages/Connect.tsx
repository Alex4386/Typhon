import { useEffect, useRef, useState, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { StatusData } from '../transport/types';
import { setAuthToken } from '../transport/auth';
import { connectWebRTC, isWebRTCActive, getApi, resetWebRTCState, setHttpApiBase, processServerOffer, finalizeServerOffer } from '../transport/api';
import type { WebRTCTransport } from '../transport/webrtc-transport';

import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardDescription, CardAction } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { Copy, Check, Loader2, RefreshCw, Wifi, WifiOff } from 'lucide-react';

type Phase = 'form' | 'processing-offer' | 'waiting-answer' | 'connecting' | 'connected' | 'error';

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

  // Server-offers-first WebRTC state
  const [answerString, setAnswerString] = useState('');
  const [copied, setCopied] = useState(false);
  const transportRef = useRef<WebRTCTransport | null>(null);

  const log = useCallback((msg: string) => {
    const ts = new Date().toLocaleTimeString();
    setLogs((prev) => [...prev, `[${ts}] ${msg}`]);
  }, []);

  useEffect(() => {
    if (logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight;
    }
  }, [logs]);

  // --- HTTP connect flow (existing) ---
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

      const signalingUrl = server.replace(/\/+$/, '') + '/api/webrtc/offer';
      log('Connecting via WebRTC to ' + server + '...');

      resetWebRTCState();
      const adapter = await connectWebRTC(signalingUrl);

      if (adapter) {
        log('WebRTC connected!');
      } else {
        log('WebRTC unavailable, using HTTP fallback to ' + server);
      }

      setPhase('connected');

      log('Fetching server status...');
      const api = getApi();
      const res = await api.get<StatusData>('/api/status');
      if (res.status === 200 && res.data) {
        setStatus(res.data);
        log('Server: ' + res.data.plugin + ' v' + res.data.version +
            ' (' + (isWebRTCActive() ? 'WebRTC' : 'HTTP') + ')');
      } else {
        log('Status request returned ' + res.status);
      }
    } catch (e) {
      setPhase('error');
      setErrorMsg((e as Error).message);
      log('Connection failed: ' + (e as Error).message);
    }
  }, [log]);

  // --- Server-offers-first WebRTC flow ---
  const doProcessOffer = useCallback(async (compressedOffer: string, stunServer?: string, token?: string) => {
    setPhase('processing-offer');
    setErrorMsg('');

    try {
      if (token) {
        log('Setting auth token...');
        setAuthToken('Bearer ' + token);
      }

      log('Processing server WebRTC offer' + (stunServer ? ' (STUN: ' + stunServer + ')' : '') + '...');
      const { transport, answer } = await processServerOffer(compressedOffer, stunServer);
      transportRef.current = transport;
      setAnswerString(answer);
      setPhase('waiting-answer');
      log('Answer generated (' + answer.length + ' chars). Copy it and run: /typhon web webrtc-answer <code>');

      // Start waiting for DataChannel to open in the background
      log('Waiting for server to accept the answer...');
      finalizeServerOffer(transport).then(() => {
        log('WebRTC DataChannel connected!');
        setPhase('connected');

        log('Fetching server status...');
        const api = getApi();
        api.get<StatusData>('/api/status').then((res) => {
          if (res.status === 200 && res.data) {
            setStatus(res.data);
            log('Server: ' + res.data.plugin + ' v' + res.data.version + ' (WebRTC)');
          }
        });
      }).catch((e) => {
        log('WebRTC connection failed: ' + (e as Error).message);
        // Don't change phase — user can still copy the answer and retry
      });
    } catch (e) {
      setPhase('error');
      setErrorMsg((e as Error).message);
      log('Failed to process offer: ' + (e as Error).message);
    }
  }, [log]);

  // Auto-connect from URL params
  useEffect(() => {
    if (attempted.current) return;

    const offer = searchParams.get('offer');
    const server = searchParams.get('server');
    const token = searchParams.get('token') || undefined;
    const stun = searchParams.get('stun') || undefined;

    if (offer) {
      attempted.current = true;
      doProcessOffer(offer, stun, token);
    } else if (server) {
      attempted.current = true;
      doConnect(server, token || '');
    }
  }, [searchParams, doConnect, doProcessOffer]);

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
        log('Status refreshed via ' + (isWebRTCActive() ? 'WebRTC' : 'HTTP'));
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

  const handleCopyAnswer = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(answerString);
      setCopied(true);
      log('Answer copied to clipboard');
      setTimeout(() => setCopied(false), 2000);
    } catch {
      log('Failed to copy — please select and copy manually');
    }
  }, [answerString, log]);

  const transportBadge = () => {
    if (phase === 'connected') {
      return isWebRTCActive()
        ? <Badge variant="default" className="bg-success text-success-foreground"><Wifi className="size-3" /> WebRTC</Badge>
        : <Badge variant="secondary"><Wifi className="size-3" /> HTTP</Badge>;
    }
    if (phase === 'connecting' || phase === 'processing-offer') {
      return <Badge variant="secondary"><Loader2 className="size-3 animate-spin" /> Connecting</Badge>;
    }
    if (phase === 'waiting-answer') {
      return <Badge variant="secondary"><Loader2 className="size-3 animate-spin" /> Waiting</Badge>;
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

        {/* Connect form — shown when no URL params */}
        {phase === 'form' && (
          <Card>
            <CardHeader>
              <CardTitle>Connect to Server</CardTitle>
              <CardDescription>Enter your Typhon server URL to connect via HTTP</CardDescription>
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
                For WebRTC connections, run <code className="bg-muted px-1 rounded">/typhon web</code> in Minecraft and click the generated link.
              </p>
            </CardContent>
          </Card>
        )}

        {/* Processing offer */}
        {phase === 'processing-offer' && (
          <Card>
            <CardHeader>
              <CardTitle>Processing Offer...</CardTitle>
              <CardDescription className="flex items-center gap-2">
                <Loader2 className="size-4 animate-spin" /> Creating WebRTC answer
              </CardDescription>
            </CardHeader>
          </Card>
        )}

        {/* Waiting for answer to be pasted back into MC */}
        {phase === 'waiting-answer' && (
          <Card>
            <CardHeader>
              <CardTitle>Copy Answer to Minecraft</CardTitle>
              <CardDescription>
                Copy the answer below and run the command in Minecraft to complete the connection
              </CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <code className="block rounded-md bg-muted px-3 py-2 text-xs font-mono text-info">
                /typhon web webrtc-answer &lt;paste-here&gt;
              </code>
              <Textarea
                readOnly
                value={answerString}
                className="font-mono text-xs"
                onClick={(e) => (e.target as HTMLTextAreaElement).select()}
              />
              <Button variant="secondary" onClick={handleCopyAnswer}>
                {copied ? <><Check className="size-4" /> Copied!</> : <><Copy className="size-4" /> Copy Answer</>}
              </Button>
              <p className="text-sm text-muted-foreground flex items-center gap-2">
                <Loader2 className="size-4 animate-spin" /> Waiting for server to accept the answer...
              </p>
            </CardContent>
          </Card>
        )}

        {/* Connecting state (HTTP flow) */}
        {phase === 'connecting' && (
          <Card>
            <CardHeader>
              <CardTitle>Connecting...</CardTitle>
              <CardDescription className="flex items-center gap-2">
                <Loader2 className="size-4 animate-spin" /> Establishing connection to {serverInput}
              </CardDescription>
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
