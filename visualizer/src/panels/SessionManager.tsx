import { useEffect, useState } from 'react';
import { ChevronRight, Eye, Pause, Play, Power, Trash2 } from 'lucide-react';
import { Hint, SimpleSelect } from '@/components/fields';
import { PanelSection, Tip } from '@/components/tip';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { cn } from '@/lib/utils';
import { attachSession, controlSession, createSession, deleteWorld, refreshCatalog, send } from '../net/connection';
import type { SessionInfo } from '../protocol/messages';
import { useStore } from '../store/store';
import { ALERT_COLORS } from '../util/color';
import { formatFactor, formatSimTime } from '../util/world';
import { ALERT_LABEL } from './events';
import { sessionLabel } from './Header';

/** A confirmation step for something that cannot be undone. */
interface Confirm {
  title: string;
  body: string;
  action: string;
  run: () => void;
}

/** Worlds page: what the server runs, what it can open, and starting new worlds. */
export function SessionManager() {
  const sessions = useStore((s) => s.sessions);
  const sessionId = useStore((s) => s.sessionId);
  const catalog = useStore((s) => s.catalog);
  const server = useStore((s) => s.serverInfo);
  const [confirm, setConfirm] = useState<Confirm | null>(null);
  useEffect(() => refreshCatalog(), []);
  const full = server ? sessions.length >= server.maxSessions : false;
  const closed = (catalog?.worlds ?? []).filter((w) => !w.sessionId);
  return (
    <div className="flex flex-col gap-5">
      <PanelSection title="Running now">
        {sessions.length === 0 && <Hint>Nothing is running. Start a world below.</Hint>}
        <ul className="flex flex-col gap-2">
          {sessions.map((s) => (
            <SessionRow key={s.id} s={s} attached={s.id === sessionId} onConfirm={setConfirm} />
          ))}
        </ul>
        {server && (
          <Tip content="Every running world shares the server's processors and memory; paused worlds use memory but no processor time.">
            <Hint>
              {sessions.length} of {server.maxSessions} worlds · memory {server.heapUsedMB} / {server.heapMaxMB} MB · {server.cpus} processors
            </Hint>
          </Tip>
        )}
      </PanelSection>
      <NewWorld disabled={full} />
      <PanelSection title="Saved worlds">
        {!server?.worldsDir && <Hint>The server has no worlds folder (start it with --worlds-dir).</Hint>}
        {server?.worldsDir && closed.length === 0 && <Hint>No other saved worlds in {server.worldsDir}.</Hint>}
        <ul className="flex flex-col gap-2">
          {closed.map((w) => (
            <li key={w.name} className="flex items-center gap-2 rounded-lg border p-2.5">
              <div className="min-w-0 flex-1">
                <div className="truncate text-sm font-medium">{w.title || w.name}</div>
                <div className="text-xs text-muted-foreground">
                  {w.error ? <span className="text-destructive">{w.error}</span> : `${w.volcanoes} volcano${w.volcanoes === 1 ? '' : 'es'} · ${w.hasState ? 'saved progress' : 'not started yet'}`}
                  {w.timeCompression && ` · time ${formatFactor(w.timeCompression.dormant)}`}
                </div>
              </div>
              <Tip content={full ? 'The server runs as many worlds as it may; close one first' : 'Load this world and watch it'}>
                <Button size="sm" variant="secondary" disabled={full || !!w.error} onClick={() => createSession({ world: w.name })}>
                  Open
                </Button>
              </Tip>
              <Tip content="Delete this world's folder, including its saved progress and replay">
                <Button
                  size="icon-sm"
                  variant="ghost"
                  aria-label={`Delete ${w.name}`}
                  onClick={() =>
                    setConfirm({
                      title: `Delete “${w.name}”?`,
                      body: 'The world folder, its saved progress and its replay are deleted. This cannot be undone.',
                      action: 'Delete',
                      run: () => deleteWorld(w.name),
                    })
                  }
                >
                  <Trash2 />
                </Button>
              </Tip>
            </li>
          ))}
        </ul>
      </PanelSection>
      <Dialog open={confirm !== null} onOpenChange={(o) => !o && setConfirm(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{confirm?.title}</DialogTitle>
            <DialogDescription>{confirm?.body}</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <DialogClose render={<Button variant="outline" />}>Cancel</DialogClose>
            <Button
              variant="destructive"
              onClick={() => {
                confirm?.run();
                setConfirm(null);
              }}
            >
              {confirm?.action}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

const ORDER = ['EXTINCT', 'DORMANT', 'MINOR_ACTIVITY', 'MAJOR_ACTIVITY', 'ERUPTION_IMMINENT', 'ERUPTING'];

function SessionRow({ s, attached, onConfirm }: { s: SessionInfo; attached: boolean; onConfirm: (c: Confirm) => void }) {
  const paused = s.mode === 'PAUSED';
  const top = s.volcanoes?.reduce<string | null>((best, v) => (best === null || ORDER.indexOf(v.alert) > ORDER.indexOf(best) ? v.alert : best), null);
  const tc = s.volcanoes?.[0]?.timeCompression;
  const close = () => controlSession(s.id, 'close');
  return (
    <li className={cn('flex items-center gap-2 rounded-lg border p-2.5', attached && 'border-primary/60 bg-primary/5')}>
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-1.5">
          <span className="truncate text-sm font-medium" title={s.name}>
            {sessionLabel(s)}
          </span>
          {attached && <Badge variant="secondary">watching</Badge>}
          {top && (
            <Badge className="text-white" style={{ background: ALERT_COLORS[top] ?? '#444' }}>
              {ALERT_LABEL[top] ?? top}
            </Badge>
          )}
        </div>
        <div className="text-xs text-muted-foreground">
          {paused ? 'Paused' : s.mode === 'UNBOUNDED' ? 'Running flat out' : `Running ${s.speed ?? ''}×`} · sim time {formatSimTime(s.time)}
          {tc && ` · volcano time ${formatFactor(tc.current ?? tc.dormant)}`}
          {s.clients ? ` · ${s.clients} viewer${s.clients > 1 ? 's' : ''}` : ''}
          {!s.world && ' · not saved to disk'}
        </div>
      </div>
      {!attached && (
        <Button size="sm" onClick={() => attachSession(s.id)}>
          <Eye /> Watch
        </Button>
      )}
      <Tip content={paused ? 'Resume this world' : 'Pause this world (it keeps its memory but stops computing)'}>
        <Button
          size="icon-sm"
          variant="ghost"
          aria-label={paused ? 'Resume' : 'Pause'}
          onClick={() => (attached ? send(paused ? { type: 'transport', mode: 'REALTIME', speed: s.speed ?? 20 } : { type: 'transport', mode: 'PAUSED' }) : controlSession(s.id, paused ? 'resume' : 'pause'))}
        >
          {paused ? <Play /> : <Pause />}
        </Button>
      </Tip>
      <Tip content={s.world ? 'Save and unload this world (open it again from Saved worlds)' : 'Unload this world; it was never saved, so it is gone afterwards'}>
        <Button
          size="icon-sm"
          variant="ghost"
          aria-label="Close"
          onClick={() =>
            s.world
              ? close()
              : onConfirm({ title: `Close “${sessionLabel(s)}”?`, body: 'This world only lives in memory; closing it loses it.', action: 'Close and lose it', run: close })
          }
        >
          <Power />
        </Button>
      </Tip>
    </li>
  );
}

function NewWorld({ disabled }: { disabled: boolean }) {
  const catalog = useStore((s) => s.catalog);
  const presets = catalog?.presets ?? [];
  const [preset, setPreset] = useState('');
  const [name, setName] = useState('');
  const [dormant, setDormant] = useState('');
  const [eruptive, setEruptive] = useState('');
  const [paused, setPaused] = useState(false);
  const chosen = presets.find((p) => p.name === (preset || presets[0]?.name));
  const nameOk = name === '' || /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(name);
  const num = (t: string) => (t.trim() === '' ? undefined : Number(t));
  const factorsOk = [dormant, eruptive].every((t) => t.trim() === '' || Number(t) > 0);
  return (
    <PanelSection title="Start a new world">
      <form
        className="flex flex-col gap-3"
        onSubmit={(e) => {
          e.preventDefault();
          if (!chosen || !nameOk || !factorsOk) return;
          createSession({ preset: chosen.name, name: name || undefined, dormant: num(dormant), eruptive: num(eruptive), paused });
          setName('');
        }}
      >
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="nw-preset">Volcano</Label>
          <SimpleSelect
            id="nw-preset"
            label="Volcano"
            size="default"
            className="w-full"
            value={chosen?.name ?? ''}
            onChange={setPreset}
            options={presets.map((p) => [p.name, `${p.title || p.name}${p.realScale ? ' (real scale)' : ''}`] as const)}
          />
          {chosen?.description && <Hint>{chosen.description}</Hint>}
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="nw-name">Name</Label>
          <Input id="nw-name" placeholder={chosen ? `${chosen.name} (automatic)` : ''} value={name} aria-invalid={!nameOk} onChange={(e) => setName(e.target.value)} />
          {!nameOk && <p className="text-xs text-destructive">Letters, digits, dot, dash and underscore only.</p>}
        </div>
        <Collapsible>
          <CollapsibleTrigger className="group flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
            <ChevronRight className="size-4 transition-transform group-data-[panel-open]:rotate-90" /> Time scale and start options
          </CollapsibleTrigger>
          <CollapsibleContent className="mt-2 flex flex-col gap-3 rounded-lg border p-3">
            <Hint>Real volcanoes take years to recharge. Time compression makes volcano processes run faster than simulated time so you can watch them. Leave empty for the volcano's default.</Hint>
            <div className="grid grid-cols-2 gap-2">
              <Tip content="Physical seconds per simulated second while the volcano is quiet">
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="nw-dormant">Quiet periods ×</Label>
                  <Input id="nw-dormant" inputMode="decimal" placeholder="default" value={dormant} onChange={(e) => setDormant(e.target.value)} />
                </div>
              </Tip>
              <Tip content="Physical seconds per simulated second while it erupts (usually small so lava looks right)">
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="nw-eruptive">During eruptions ×</Label>
                  <Input id="nw-eruptive" inputMode="decimal" placeholder="default" value={eruptive} onChange={(e) => setEruptive(e.target.value)} />
                </div>
              </Tip>
            </div>
            {!factorsOk && <p className="text-xs text-destructive">Factors must be positive numbers.</p>}
            <Label className="flex items-center gap-2 font-normal">
              <Switch checked={paused} onCheckedChange={(v) => setPaused(v)} /> Start paused
            </Label>
          </CollapsibleContent>
        </Collapsible>
        <Tip content={disabled ? 'The server runs as many worlds as it may; close one first' : undefined}>
          <Button type="submit" disabled={disabled || !chosen || !nameOk || !factorsOk}>
            Start world
          </Button>
        </Tip>
      </form>
    </PanelSection>
  );
}
