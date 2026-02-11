import { useState, useEffect, useCallback } from 'react';
import { getAuthToken, setAuthToken, clearAuthToken } from '@/transport/auth';
import { useConnectionStatus } from '@/hooks/useConnectionStatus';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { KeyRound, Wifi, WifiOff } from 'lucide-react';

export default function Settings() {
  const { online, version, refresh } = useConnectionStatus();
  const [tokenInput, setTokenInput] = useState('');

  useEffect(() => {
    const saved = getAuthToken();
    if (saved) setTokenInput(saved);
  }, []);

  const applyToken = useCallback(() => {
    const token = tokenInput.trim();
    if (token) {
      setAuthToken(token);
      refresh();
    }
  }, [tokenInput, refresh]);

  const handleClear = useCallback(() => {
    clearAuthToken();
    setTokenInput('');
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
        <p className="text-sm text-muted-foreground">
          Set a bearer token or basic credentials to authenticate with the server.
        </p>
        <div className="flex gap-2">
          <Input
            type="password"
            placeholder="Bearer token or Basic credentials..."
            value={tokenInput}
            onChange={(e) => setTokenInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && applyToken()}
            className="flex-1"
          />
          <Button size="sm" onClick={applyToken}>Set</Button>
          <Button variant="secondary" size="sm" onClick={handleClear}>Clear</Button>
        </div>
      </div>
    </div>
  );
}
