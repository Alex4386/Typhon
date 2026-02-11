import { useEffect, useRef, useState, useCallback } from 'react';
import { useSearchParams, useNavigate, Link } from 'react-router-dom';
import type { VersionData } from '../transport/types';
import { setAuthToken } from '../transport/auth';
import { getApi, setApiBase } from '../transport/api';

import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
} from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Loader2 } from 'lucide-react';
import { VolcanoIcon } from '@/components/icons/VolcanoIcon';

type Phase = 'form' | 'connecting' | 'connected' | 'error';
type AuthMode = 'token' | 'basic';

export default function Connect() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const [phase, setPhase] = useState<Phase>('form');
  const [logs, setLogs] = useState<string[]>([]);
  const [errorMsg, setErrorMsg] = useState('');
  const logRef = useRef<HTMLDivElement>(null);
  const attempted = useRef(false);

  const [serverInput, setServerInput] = useState(searchParams.get('server') || '');
  const [tokenInput, setTokenInput] = useState(searchParams.get('token') || '');
  const [authMode, setAuthMode] = useState<AuthMode>(searchParams.get('token') ? 'token' : 'token');
  const [usernameInput, setUsernameInput] = useState('');
  const [passwordInput, setPasswordInput] = useState('');

  const log = useCallback((msg: string) => {
    const ts = new Date().toLocaleTimeString();
    setLogs((prev) => [...prev, `[${ts}] ${msg}`]);
  }, []);

  useEffect(() => {
    if (logRef.current) {
      logRef.current.scrollTop = logRef.current.scrollHeight;
    }
  }, [logs]);

  const doConnect = useCallback(async (
    server: string,
    opts: { token?: string; username?: string; password?: string },
  ) => {
    setPhase('connecting');
    setErrorMsg('');

    try {
      if (opts.username && opts.password) {
        log('Setting basic credentials...');
        setAuthToken('Basic ' + btoa(opts.username + ':' + opts.password));
      } else if (opts.token) {
        log('Setting auth token...');
        setAuthToken('Bearer ' + opts.token);
      }

      setApiBase(server);
      log('Connecting to ' + server + '...');

      const api = getApi();

      log('Checking server health...');
      const health = await api.get<{ status: string }>('/health');
      if (health.status !== 200) {
        throw new Error('Health check failed: HTTP ' + health.status);
      }
      log('Server is healthy');

      const ver = await api.get<VersionData>('/version');
      if (ver.status === 200 && ver.data) {
        log('Connected: ' + ver.data.plugin + ' v' + ver.data.version);
      }

      // Check authentication
      log('Checking authentication...');
      const authRes = await api.get<{ authenticated: boolean; authConfigured: boolean }>('/auth');
      if (authRes.status === 200 && authRes.data) {
        if (authRes.data.authConfigured && !authRes.data.authenticated) {
          throw new Error('Authentication failed. Please check your credentials.');
        }
        log(authRes.data.authConfigured ? 'Authenticated' : 'No auth configured (open access)');
      }

      setPhase('connected');

      log('Redirecting to dashboard...');
      setTimeout(() => navigate('/'), 1000);
    } catch (e) {
      setPhase('error');
      setErrorMsg((e as Error).message);
      log('Connection failed: ' + (e as Error).message);
    }
  }, [log, navigate]);

  // Auto-connect from URL params
  useEffect(() => {
    if (attempted.current) return;

    const server = searchParams.get('server');
    const token = searchParams.get('token') || '';

    if (server) {
      attempted.current = true;
      doConnect(server, { token });
    }
  }, [searchParams, doConnect]);

  const handleSubmit = useCallback(() => {
    const server = serverInput.trim();
    if (!server) return;
    attempted.current = true;
    if (authMode === 'basic') {
      doConnect(server, { username: usernameInput.trim(), password: passwordInput });
    } else {
      doConnect(server, { token: tokenInput.trim() });
    }
  }, [serverInput, tokenInput, usernameInput, passwordInput, authMode, doConnect]);

  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-6 bg-muted p-6 md:p-10">
      <div className="flex w-full max-w-sm flex-col gap-6">
        {/* Brand */}
        <Link to="/" className="flex items-center gap-2 self-center hover:opacity-80 transition-opacity">
          <div className="flex size-8 items-center justify-center rounded-md bg-primary text-primary-foreground">
            <VolcanoIcon className="size-5" />
          </div>
          <span className="text-xl font-bold tracking-wide">Typhon</span>
        </Link>

        {/* Connect form */}
        <div className={cn("flex flex-col gap-6")}>
          <Card>
            <CardHeader className="text-center">
              <CardTitle className="text-xl">Connect to Server</CardTitle>
              <CardDescription>
                Enter your Typhon server details to continue
              </CardDescription>
            </CardHeader>
            <CardContent>
              {errorMsg && (
                <div className="mb-4 rounded-lg border border-destructive bg-destructive/10 p-3 text-sm text-destructive">
                  {errorMsg}
                </div>
              )}

              {phase === 'connecting' ? (
                <div className="flex flex-col items-center gap-3 py-6">
                  <Loader2 className="size-6 animate-spin text-muted-foreground" />
                  <p className="text-sm text-muted-foreground">Connecting to {serverInput}...</p>
                </div>
              ) : phase === 'connected' ? (
                <div className="flex flex-col items-center gap-3 py-6">
                  <div className="size-8 rounded-full bg-success/20 flex items-center justify-center">
                    <div className="size-3 rounded-full bg-success" />
                  </div>
                  <p className="text-sm text-muted-foreground">Connected. Redirecting...</p>
                </div>
              ) : (
                <form onSubmit={(e) => { e.preventDefault(); handleSubmit(); }}>
                  <FieldGroup>
                    <Field>
                      <FieldLabel htmlFor="server">Server URL</FieldLabel>
                      <Input
                        id="server"
                        placeholder="http://your-server:18080"
                        value={serverInput}
                        onChange={(e) => setServerInput(e.target.value)}
                        required
                      />
                    </Field>

                    <Tabs value={authMode} onValueChange={(v) => setAuthMode(v as AuthMode)}>
                      <TabsList className="w-full">
                        <TabsTrigger value="token" className="flex-1">Token</TabsTrigger>
                        <TabsTrigger value="basic" className="flex-1">Username &amp; Password</TabsTrigger>
                      </TabsList>
                    </Tabs>

                    {authMode === 'token' ? (
                      <Field>
                        <FieldLabel htmlFor="token">Auth Token</FieldLabel>
                        <Input
                          id="token"
                          type="password"
                          placeholder="Bearer token..."
                          value={tokenInput}
                          onChange={(e) => setTokenInput(e.target.value)}
                        />
                      </Field>
                    ) : (
                      <>
                        <Field>
                          <FieldLabel htmlFor="username">Username</FieldLabel>
                          <Input
                            id="username"
                            placeholder="Username"
                            value={usernameInput}
                            onChange={(e) => setUsernameInput(e.target.value)}
                          />
                        </Field>
                        <Field>
                          <FieldLabel htmlFor="password">Password</FieldLabel>
                          <Input
                            id="password"
                            type="password"
                            placeholder="Password"
                            value={passwordInput}
                            onChange={(e) => setPasswordInput(e.target.value)}
                          />
                        </Field>
                      </>
                    )}

                    <Field>
                      <Button type="submit" className="w-full">Connect</Button>
                      <FieldDescription className="text-center">
                        Run <code className="bg-muted px-1 rounded text-xs">/typhon web</code> in
                        Minecraft to get a connect link.
                      </FieldDescription>
                    </Field>
                  </FieldGroup>
                </form>
              )}
            </CardContent>
          </Card>

          {/* Connection log */}
          {logs.length > 0 && (
            <div
              ref={logRef}
              className="rounded-md bg-card border border-border p-3 font-mono text-xs max-h-32 overflow-y-auto text-muted-foreground break-all"
            >
              {logs.map((line, i) => (
                <div key={i}>{line}</div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
