import { useEffect, useMemo, useRef, useState } from 'react';
import { ChevronRight, RotateCcw } from 'lucide-react';
import { Hint, SliderRow } from '@/components/fields';
import { PanelSection, Tip } from '@/components/tip';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import { command, setParams } from '../net/connection';
import type { ParamSpec, ParamValue } from '../protocol/messages';
import { useStore } from '../store/store';
import { fieldError } from './inject';
import { ParamInput, formatParam } from './ParamInput';
import { atRest, editApplied, isComputed, overrideSeed, shownValue } from './paramState';
import { Switch } from '@/components/ui/switch';

/** Live (hot) edits are sent this long after the last keystroke/slider move. */
const LIVE_DEBOUNCE_MS = 400;

/** Parameters page: weather now, then every tunable value of the world grouped by subject. */
export function Params() {
  const schema = useStore((s) => s.schema);
  const [filter, setFilter] = useState('');
  const [pending, setPending] = useState<Record<string, ParamValue | null>>({});
  const [confirming, setConfirming] = useState(false);
  const liveQueue = useRef<Record<string, ParamValue | null>>({});
  const liveTimer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(liveTimer.current), []);
  // drop pending edits the server now reports as applied
  useEffect(() => {
    if (!schema) return;
    setPending((p) => {
      const next = { ...p };
      for (const spec of schema.params) {
        const v = next[spec.id];
        if (v === undefined) continue;
        if (editApplied(spec, v)) delete next[spec.id];
      }
      return next;
    });
  }, [schema]);

  const specs = schema?.params ?? [];
  const byId = useMemo(() => new Map(specs.map((p) => [p.id, p])), [specs]);
  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase();
    return q ? specs.filter((p) => `${p.label} ${p.group} ${p.id} ${p.help ?? ''}`.toLowerCase().includes(q)) : specs;
  }, [specs, filter]);
  const groups = useMemo(() => [...new Set(shown.map((p) => p.group))], [shown]);

  const valueOf = (p: ParamSpec): ParamValue | undefined => shownValue(p, pending[p.id]);
  const edit = (p: ParamSpec, v: ParamValue | null) => {
    setPending((x) => ({ ...x, [p.id]: v }));
    if (p.apply !== 'hot') return;
    // null: back to the default, or to the engine's own value for an auto parameter
    if (v !== null && p.type === 'number' && fieldError(p, v) !== null) return;
    liveQueue.current[p.id] = v;
    window.clearTimeout(liveTimer.current);
    liveTimer.current = window.setTimeout(() => {
      const batch = liveQueue.current;
      liveQueue.current = {};
      setParams(batch);
    }, LIVE_DEBOUNCE_MS);
  };
  const restartEdits = Object.entries(pending).filter(([id]) => byId.get(id)?.apply === 'restart');
  const restartInvalid = restartEdits.some(([id, v]) => {
    const p = byId.get(id)!;
    return v !== null && fieldError(p, v) !== null;
  });
  const describe = (p: ParamSpec, v: ParamValue | null | undefined) => (v === null && p.auto ? 'auto' : formatParam(v === null ? p.default : v, p));
  const restartWho = [...new Set(restartEdits.map(([id]) => byId.get(id)?.volcanoId ?? 'world'))];

  return (
    <div className="flex flex-col gap-5">
      <Weather />
      {!schema && <Hint>This server does not publish tunable parameters.</Hint>}
      {schema && !schema.tunable && (
        <Alert>
          <AlertDescription>{schema.reason ?? 'This world cannot be changed.'}</AlertDescription>
        </Alert>
      )}
      {schema && specs.length > 0 && (
        <>
          <Input type="search" placeholder="Find a setting (e.g. temperature, rain, silica)" aria-label="Find a setting" value={filter} onChange={(e) => setFilter(e.target.value)} />
          <Hint>
            <ApplyBadge apply="hot" /> changes apply at once. <ApplyBadge apply="restart" /> changes rebuild the volcano from its settings (its magma and surroundings start over; the landscape is kept).
          </Hint>
          {groups.map((g) => (
            <Collapsible key={`${g}:${filter !== ''}`} defaultOpen={groups.length <= 3 || filter !== '' || /Weather|Magma supply/.test(g)} className="rounded-lg border">
              <CollapsibleTrigger className="group flex w-full items-center gap-2 px-3 py-2 text-left text-sm font-medium hover:bg-muted/50">
                <ChevronRight className="size-4 transition-transform group-data-[panel-open]:rotate-90" />
                {g}
              </CollapsibleTrigger>
              <CollapsibleContent className="flex flex-col divide-y border-t">
                {shown
                  .filter((p) => p.group === g)
                  .map((p) => {
                    const v = valueOf(p);
                    const computed = isComputed(p, pending[p.id]);
                    const err = computed ? null : fieldError(p, v);
                    const changed = pending[p.id] !== undefined;
                    const atDefault = atRest(p, pending[p.id]);
                    const resetTip = p.auto ? `Back to the computed value${typeof p.computed === 'number' ? ` (${formatParam(p.computed, p)})` : ''}` : `Back to default (${formatParam(p.default, p)})`;
                    return (
                      <div key={p.id} className={cn('flex flex-col gap-1.5 px-3 py-2', changed && 'bg-primary/5')}>
                        <div className="flex items-center gap-2">
                          <Tip content={`${p.help ?? ''}\n(${p.id})`.trim()} side="left">
                            <Label htmlFor={`p-${p.id}`} className="flex-1 font-normal">
                              {p.label}
                            </Label>
                          </Tip>
                          <ApplyBadge apply={p.apply === 'hot' ? 'hot' : 'restart'} />
                          <Tip content={resetTip}>
                            <Button variant="ghost" size="icon-xs" disabled={atDefault} aria-label={`Reset ${p.label} to ${p.auto ? 'computed' : 'default'}`} onClick={() => edit(p, null)}>
                              <RotateCcw />
                            </Button>
                          </Tip>
                        </div>
                        {p.auto && (
                          <div className="flex items-center gap-2 text-xs">
                            <span className={cn('flex-1', computed ? 'text-foreground' : 'text-muted-foreground')}>
                              Auto{typeof p.computed === 'number' ? ` (computed ${formatParam(p.computed, p)})` : ' (computed by the model)'}
                            </span>
                            <Label htmlFor={`o-${p.id}`} className="font-normal text-muted-foreground">
                              Override
                            </Label>
                            <Switch id={`o-${p.id}`} size="sm" checked={!computed} onCheckedChange={(on) => edit(p, on ? overrideSeed(p) : null)} />
                          </div>
                        )}
                        {!computed && <ParamInput spec={p} value={v} invalid={!!err} onChange={(x) => edit(p, x)} />}
                        {err ? <p className="text-xs text-destructive">{err}</p> : p.help ? <Hint>{p.help}</Hint> : null}
                      </div>
                    );
                  })}
              </CollapsibleContent>
            </Collapsible>
          ))}
          {restartEdits.length > 0 && (
            <div className="sticky bottom-0 flex items-center gap-2 rounded-lg border bg-card p-3 text-sm shadow-lg" role="region" aria-label="Changes waiting for a restart">
              <span className="flex-1">
                {restartEdits.length} change{restartEdits.length > 1 ? 's' : ''} need{restartEdits.length > 1 ? '' : 's'} a restart of {restartWho.join(', ')}.
              </span>
              <Button variant="ghost" size="sm" onClick={() => setPending(Object.fromEntries(Object.entries(pending).filter(([id]) => byId.get(id)?.apply !== 'restart')))}>
                Discard
              </Button>
              <Button size="sm" disabled={restartInvalid} onClick={() => setConfirming(true)}>
                Apply and restart…
              </Button>
            </div>
          )}
          <Dialog open={confirming} onOpenChange={setConfirming}>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>Restart {restartWho.join(', ')}?</DialogTitle>
                <DialogDescription>
                  {restartWho.length > 1 ? 'Their magma systems start' : 'Its magma system starts'} over from the new settings: chamber pressure, recharge and alert history reset. The terrain, lava already on the
                  ground and the replay up to now are kept.
                </DialogDescription>
              </DialogHeader>
              <ul className="list-disc pl-5 text-sm">
                {restartEdits.map(([id, v]) => {
                  const p = byId.get(id)!;
                  return (
                    <li key={id}>
                      {p.label}: {describe(p, p.value ?? (p.auto ? null : undefined))} → {describe(p, v)}
                    </li>
                  );
                })}
              </ul>
              <DialogFooter>
                <DialogClose render={<Button variant="outline" />}>Cancel</DialogClose>
                <Button
                  variant="destructive"
                  onClick={() => {
                    setParams(Object.fromEntries(restartEdits), true);
                    setConfirming(false);
                  }}
                >
                  Apply and restart
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
          <Audit />
        </>
      )}
    </div>
  );
}

/** "live" / "restart" marker of a setting. */
function ApplyBadge({ apply }: { apply: 'hot' | 'restart' }) {
  return (
    <Badge variant={apply === 'hot' ? 'secondary' : 'outline'} className={apply === 'hot' ? 'text-emerald-400' : 'text-amber-400'}>
      {apply === 'hot' ? 'live' : 'restart'}
    </Badge>
  );
}

function Weather() {
  const world = useStore((s) => s.state?.world);
  const [rain, setRain] = useState<number | null>(null);
  const rainNow = world?.rainMmPerHour ?? 0;
  return (
    <PanelSection title="Weather now">
      <SliderRow
        label="Rain"
        help="Rain over the whole map; it soaks into the ground, fills lakes and can start mudflows on ash"
        value={rain ?? rainNow}
        display={`${(rain ?? rainNow).toFixed(0)} mm/h`}
        min={0}
        max={100}
        step={1}
        onChange={setRain}
        onCommit={(v) => {
          command({ kind: 'rain', mmPerHour: v });
          setRain(null);
        }}
      />
      {world?.wind && (
        <Hint>
          Wind {world.wind.speed.toFixed(0)} m/s towards {world.wind.bearingDeg.toFixed(0)}°
        </Hint>
      )}
    </PanelSection>
  );
}

function Audit() {
  const audit = useStore((s) => s.schema?.audit ?? []);
  if (audit.length === 0) return null;
  return (
    <Collapsible className="rounded-lg border">
      <CollapsibleTrigger className="group flex w-full items-center gap-2 px-3 py-2 text-left text-sm font-medium hover:bg-muted/50">
        <ChevronRight className="size-4 transition-transform group-data-[panel-open]:rotate-90" />
        Change history ({audit.length})
      </CollapsibleTrigger>
      <CollapsibleContent>
        <ul className="flex flex-col gap-1 border-t px-3 py-2 text-xs">
          {[...audit].reverse().map((a, k) => (
            <li key={k}>
              <time className="text-muted-foreground tabular-nums">{new Date(a.at).toLocaleTimeString()}</time> {a.label}: {formatParam(a.from)} → {a.to === null ? 'default' : formatParam(a.to)}
              {a.apply === 'restart' && (
                <>
                  {' '}
                  <ApplyBadge apply="restart" />
                </>
              )}
            </li>
          ))}
        </ul>
      </CollapsibleContent>
    </Collapsible>
  );
}
