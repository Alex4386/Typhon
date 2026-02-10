import { useEffect, useRef, useState, useCallback } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { StatusData } from '../transport/types';
import { setAuthToken } from '../transport/auth';
import { connectWebRTC, isWebRTCActive, getApi, resetWebRTCState, setHttpApiBase, createManualOffer, acceptManualAnswer } from '../transport/api';
import type { WebRTCTransport } from '../transport/webrtc-transport';

import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle, CardDescription, CardAction } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { Badge } from '@/components/ui/badge';
import { Copy, Check, ArrowLeft, Loader2, RefreshCw, Wifi, WifiOff } from 'lucide-react';

export default function Connect() {
  const [searchParams] = useSearchParams();
  const [phase, setPhase] = useState<'form' | 'connecting' | 'connected' | 'error'>('form');
  const [status, setStatus] = useState<StatusData | null>(null);
  const [logs, setLogs] = useState<string[]>([]);
  const [errorMsg, setErrorMsg] = useState('');
  const logRef = useRef<HTMLDivElement>(null);
  const attempted = useRef(false);

  const [serverInput, setServerInput] = useState(searchParams.get('server') || '');
  const [tokenInput, setTokenInput] = useState(searchParams.get('token') || '');

  // Manual WebRTC state
  const [manualMode, setManualMode] = useState(searchParams.get('manual') === 'true');
  const [manualStep, setManualStep] = useState<'generate' | 'offer-ready' | 'paste-answer' | 'connecting'>('generate');
  const [offerString, setOfferString] = useState('');
  const [answerInput, setAnswerInput] = useState('');
  const [copied, setCopied] = useState(false);
  const manualTransportRef = useRef<WebRTCTransport | null>(null);

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

  useEffect(() => {
    if (attempted.current) return;
    const server = searchParams.get('server');
    const token = searchParams.get('token');
    if (server) {
      attempted.current = true;
      doConnect(server, token || '');
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

  // Manual WebRTC handlers
  const handleGenerateOffer = useCallback(async () => {
    setErrorMsg('');
    setManualStep('generate');
    log('Generating WebRTC offer...');

    try {
      const { transport, offer } = await createManualOffer();
      manualTransportRef.current = transport;
      setOfferString(offer);
      setManualStep('offer-ready');
      log('Offer generated (' + offer.length + ' chars). Copy it and run: /typhon web offer <code>');
    } catch (e) {
      setErrorMsg((e as Error).message);
      log('Failed to generate offer: ' + (e as Error).message);
    }
  }, [log]);

  const handleCopyOffer = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(offerString);
      setCopied(true);
      log('Offer copied to clipboard');
      setTimeout(() => setCopied(false), 2000);
    } catch {
      log('Failed to copy — please select and copy manually');
    }
  }, [offerString, log]);

  const handleAcceptAnswer = useCallback(async () => {
    const answer = answerInput.trim();
    if (!answer || !manualTransportRef.current) return;

    setManualStep('connecting');
    setErrorMsg('');
    log('Accepting server answer...');

    try {
      await acceptManualAnswer(manualTransportRef.current, answer);
      log('WebRTC DataChannel connected!');
      setPhase('connected');
      setManualMode(false);

      log('Fetching server status...');
      const api = getApi();
      const res = await api.get<StatusData>('/api/status');
      if (res.status === 200 && res.data) {
        setStatus(res.data);
        log('Server: ' + res.data.plugin + ' v' + res.data.version + ' (WebRTC)');
      }
    } catch (e) {
      setManualStep('paste-answer');
      setErrorMsg((e as Error).message);
      log('Failed to accept answer: ' + (e as Error).message);
    }
  }, [answerInput, log]);

  const transportBadge = () => {
    if (phase === 'connected') {
      return isWebRTCActive()
        ? <Badge variant="default" className="bg-success text-success-foreground"><Wifi className="size-3" /> WebRTC</Badge>
        : <Badge variant="secondary"><Wifi className="size-3" /> HTTP</Badge>;
    }
    if (phase === 'connecting') return <Badge variant="secondary"><Loader2 className="size-3 animate-spin" /> Connecting</Badge>;
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
        {phase === 'form' && !manualMode && (
          <Card>
            <CardHeader>
              <CardTitle>Connect to Server</CardTitle>
              <CardDescription>Enter your Typhon server URL to connect</CardDescription>
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
              <div className="flex gap-2">
                <Button onClick={handleSubmit}>Connect</Button>
                <Button variant="secondary" onClick={() => setManualMode(true)}>Manual WebRTC</Button>
              </div>
            </CardContent>
          </Card>
        )}

        {/* Manual WebRTC wizard */}
        {phase === 'form' && manualMode && (
          <Card>
            <CardHeader>
              <CardTitle>Manual WebRTC</CardTitle>
              <CardAction>
                <Button variant="ghost" size="sm" onClick={() => setManualMode(false)}>
                  <ArrowLeft className="size-4" /> Back
                </Button>
              </CardAction>
              <CardDescription>Connect via copy-paste signaling when the server isn't directly reachable</CardDescription>
            </CardHeader>
            <CardContent className="space-y-6">
              {manualStep === 'generate' && (
                <div className="space-y-3">
                  <div className="text-xs font-semibold uppercase tracking-wider text-primary">Step 1</div>
                  <p className="text-sm text-muted-foreground">Generate a WebRTC offer to copy into Minecraft.</p>
                  <Button onClick={handleGenerateOffer}>Generate Offer</Button>
                </div>
              )}

              {manualStep === 'offer-ready' && (
                <div className="space-y-5">
                  <div className="space-y-3">
                    <div className="text-xs font-semibold uppercase tracking-wider text-primary">Step 1 — Offer Ready</div>
                    <p className="text-sm text-muted-foreground">Copy this offer and run in Minecraft:</p>
                    <code className="block rounded-md bg-muted px-3 py-2 text-xs font-mono text-info">
                      /typhon web offer &lt;paste-here&gt;
                    </code>
                    <Textarea
                      readOnly
                      value={offerString}
                      className="font-mono text-xs"
                      onClick={(e) => (e.target as HTMLTextAreaElement).select()}
                    />
                    <Button variant="secondary" onClick={handleCopyOffer}>
                      {copied ? <><Check className="size-4" /> Copied!</> : <><Copy className="size-4" /> Copy Offer</>}
                    </Button>
                  </div>
                  <div className="space-y-3">
                    <div className="text-xs font-semibold uppercase tracking-wider text-primary">Step 2 — Paste Answer</div>
                    <p className="text-sm text-muted-foreground">After running the command, click the answer in chat to copy it, then paste below:</p>
                    <Textarea
                      placeholder="Paste the server's answer here..."
                      value={answerInput}
                      onChange={(e) => setAnswerInput(e.target.value)}
                      className="font-mono text-xs"
                    />
                    <Button onClick={handleAcceptAnswer} disabled={!answerInput.trim()}>Connect</Button>
                  </div>
                </div>
              )}

              {manualStep === 'paste-answer' && (
                <div className="space-y-3">
                  <div className="text-xs font-semibold uppercase tracking-wider text-primary">Step 2 — Paste Answer</div>
                  <p className="text-sm text-muted-foreground">Paste the server's compressed answer below:</p>
                  <Textarea
                    placeholder="Paste the server's answer here..."
                    value={answerInput}
                    onChange={(e) => setAnswerInput(e.target.value)}
                    className="font-mono text-xs"
                  />
                  <Button onClick={handleAcceptAnswer} disabled={!answerInput.trim()}>Connect</Button>
                </div>
              )}

              {manualStep === 'connecting' && (
                <div className="space-y-3">
                  <div className="text-xs font-semibold uppercase tracking-wider text-primary">Step 3</div>
                  <p className="text-sm text-muted-foreground flex items-center gap-2">
                    <Loader2 className="size-4 animate-spin" /> Establishing WebRTC connection...
                  </p>
                </div>
              )}
            </CardContent>
          </Card>
        )}

        {/* Connecting state */}
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
