import { useState, useEffect, useCallback } from 'react';
import { getAuthToken, setAuthToken, clearAuthToken } from '@/transport/auth';
import { useConnectionStatus } from '@/hooks/useConnectionStatus';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { KeyRound, Wifi, WifiOff } from 'lucide-react';

type AuthMode = 'token' | 'basic';

/** Try to decode the stored auth header back into mode + fields. */
function parseStoredAuth(raw: string): { mode: AuthMode; token: string; username: string; password: string } {
  if (raw.startsWith('Basic ')) {
    try {
      const decoded = atob(raw.slice(6));
      const idx = decoded.indexOf(':');
      if (idx !== -1) {
        return { mode: 'basic', token: '', username: decoded.slice(0, idx), password: decoded.slice(idx + 1) };
      }
    } catch { /* fall through */ }
  }
  // Strip "Bearer " prefix if present for display
  const token = raw.startsWith('Bearer ') ? raw.slice(7) : raw;
  return { mode: 'token', token, username: '', password: '' };
}

export default function Settings() {
  const { online, version, refresh } = useConnectionStatus();
  const [authMode, setAuthMode] = useState<AuthMode>('token');
  const [tokenInput, setTokenInput] = useState('');
  const [usernameInput, setUsernameInput] = useState('');
  const [passwordInput, setPasswordInput] = useState('');

  useEffect(() => {
    const saved = getAuthToken();
    if (saved) {
      const parsed = parseStoredAuth(saved);
      setAuthMode(parsed.mode);
      setTokenInput(parsed.token);
      setUsernameInput(parsed.username);
      setPasswordInput(parsed.password);
    }
  }, []);

  const applyAuth = useCallback(() => {
    if (authMode === 'basic') {
      const u = usernameInput.trim();
      const p = passwordInput;
      if (u && p) {
        setAuthToken('Basic ' + btoa(u + ':' + p));
        refresh();
      }
    } else {
      const t = tokenInput.trim();
      if (t) {
        setAuthToken('Bearer ' + t);
        refresh();
      }
    }
  }, [authMode, tokenInput, usernameInput, passwordInput, refresh]);

  const handleClear = useCallback(() => {
    clearAuthToken();
    setTokenInput('');
    setUsernameInput('');
    setPasswordInput('');
  }, []);

  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Settings</h1>
        <p className="text-sm text-muted-foreground">Server connection and authentication</p>
      </div>

      {/* Connection status */}
      <div className="rounded-lg border border-border bg-card p-4 space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="font-semibold">Connection</h2>
          {online ? (
            <Badge variant="secondary" className="gap-1"><Wifi className="size-3" /> Online</Badge>
          ) : (
            <Badge variant="outline" className="gap-1"><WifiOff className="size-3" /> Offline</Badge>
          )}
        </div>
        {version && (
          <div className="text-sm text-muted-foreground">
            {version.plugin} v{version.version}
          </div>
        )}
      </div>

      {/* Auth */}
      <div className="rounded-lg border border-border bg-card p-4 space-y-3">
        <div className="flex items-center gap-2">
          <KeyRound className="size-4 text-muted-foreground" />
          <h2 className="font-semibold">Authentication</h2>
        </div>

        <Tabs value={authMode} onValueChange={(v) => setAuthMode(v as AuthMode)}>
          <TabsList className="w-full">
            <TabsTrigger value="token" className="flex-1">Token</TabsTrigger>
            <TabsTrigger value="basic" className="flex-1">Username &amp; Password</TabsTrigger>
          </TabsList>
        </Tabs>

        {authMode === 'token' ? (
          <div className="flex gap-2">
            <Input
              type="password"
              placeholder="Bearer token..."
              value={tokenInput}
              onChange={(e) => setTokenInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && applyAuth()}
              className="flex-1"
            />
            <Button size="sm" onClick={applyAuth}>Set</Button>
            <Button variant="secondary" size="sm" onClick={handleClear}>Clear</Button>
          </div>
        ) : (
          <div className="space-y-2">
            <Input
              placeholder="Username"
              value={usernameInput}
              onChange={(e) => setUsernameInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && applyAuth()}
            />
            <div className="flex gap-2">
              <Input
                type="password"
                placeholder="Password"
                value={passwordInput}
                onChange={(e) => setPasswordInput(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && applyAuth()}
                className="flex-1"
              />
              <Button size="sm" onClick={applyAuth}>Set</Button>
              <Button variant="secondary" size="sm" onClick={handleClear}>Clear</Button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
