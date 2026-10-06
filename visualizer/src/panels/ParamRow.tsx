import { useEffect, useRef, useState } from 'react';
import { RotateCcw } from 'lucide-react';
import { Hint } from '@/components/fields';
import { Tip } from '@/components/tip';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { cn } from '@/lib/utils';
import { setParams } from '../net/connection';
import type { ParamSpec, ParamValue } from '../protocol/messages';
import { useStore } from '../store/store';
import { fieldError } from './inject';
import { ParamInput, formatParam } from './ParamInput';
import { atRest, editApplied, isComputed, overrideSeed, shownValue } from './paramState';

/** Live (hot) edits are sent this long after the last keystroke/slider move. */
const LIVE_DEBOUNCE_MS = 400;

/**
 * Edits of world/volcano settings: pending values per parameter id (null = back to the default, or
 * to the computed value of an auto parameter). Live ones are sent debounced; restart ones wait for
 * an explicit apply. Pending edits disappear once the server's schema reports them applied.
 */
export function useParamEdits() {
  const schema = useStore((s) => s.schema);
  const [pending, setPending] = useState<Record<string, ParamValue | null>>({});
  const liveQueue = useRef<Record<string, ParamValue | null>>({});
  const liveTimer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(liveTimer.current), []);
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
  const edit = (p: ParamSpec, v: ParamValue | null) => {
    setPending((x) => ({ ...x, [p.id]: v }));
    if (p.apply !== 'hot') return;
    if (v !== null && p.type === 'number' && fieldError(p, v) !== null) return;
    liveQueue.current[p.id] = v;
    window.clearTimeout(liveTimer.current);
    liveTimer.current = window.setTimeout(() => {
      const batch = liveQueue.current;
      liveQueue.current = {};
      setParams(batch);
    }, LIVE_DEBOUNCE_MS);
  };
  return { pending, setPending, edit };
}

/** "live" / "restart" marker of a setting. */
export function ApplyBadge({ apply }: { apply: 'hot' | 'restart' }) {
  return (
    <Badge variant={apply === 'hot' ? 'secondary' : 'outline'} className={apply === 'hot' ? 'text-emerald-400' : 'text-amber-400'}>
      {apply === 'hot' ? 'live' : 'restart'}
    </Badge>
  );
}

/** One setting: label, live/restart marker, reset, auto/override switch, input, help or error. */
export function ParamRow({ p, pending, onEdit, compact = false }: { p: ParamSpec; pending: ParamValue | null | undefined; onEdit: (p: ParamSpec, v: ParamValue | null) => void; compact?: boolean }) {
  const v = shownValue(p, pending);
  const computed = isComputed(p, pending);
  const err = computed ? null : fieldError(p, v);
  const changed = pending !== undefined;
  const atDefault = atRest(p, pending);
  const resetTip = p.auto ? `Back to the computed value${typeof p.computed === 'number' ? ` (${formatParam(p.computed, p)})` : ''}` : `Back to default (${formatParam(p.default, p)})`;
  if (p.type === 'boolean') {
    // an on/off setting is one line: a checkbox with its label
    return (
      <div className={cn('flex flex-col gap-1 py-2', compact ? 'px-0' : 'px-3', changed && 'bg-primary/5')}>
        <div className="flex items-center gap-2">
          <Checkbox id={`p-${p.id}`} checked={v === true} onCheckedChange={(on) => onEdit(p, on === true)} />
          <Tip content={`${p.help ?? ''}\n(${p.id})`.trim()} side="left">
            <Label htmlFor={`p-${p.id}`} className="flex-1 font-normal">
              {p.label}
            </Label>
          </Tip>
          {!compact && <ApplyBadge apply={p.apply === 'hot' ? 'hot' : 'restart'} />}
          <Tip content={resetTip}>
            <Button variant="ghost" size="icon-xs" disabled={atDefault} aria-label={`Reset ${p.label} to default`} onClick={() => onEdit(p, null)}>
              <RotateCcw />
            </Button>
          </Tip>
        </div>
        {p.help && !compact && <Hint className="pl-6">{p.help}</Hint>}
      </div>
    );
  }
  return (
    <div className={cn('flex flex-col gap-1.5 py-2', compact ? 'px-0' : 'px-3', changed && 'bg-primary/5')}>
      <div className="flex items-center gap-2">
        <Tip content={`${p.help ?? ''}\n(${p.id})`.trim()} side="left">
          <Label htmlFor={`p-${p.id}`} className="flex-1 font-normal">
            {p.label}
          </Label>
        </Tip>
        {!compact && <ApplyBadge apply={p.apply === 'hot' ? 'hot' : 'restart'} />}
        <Tip content={resetTip}>
          <Button variant="ghost" size="icon-xs" disabled={atDefault} aria-label={`Reset ${p.label} to ${p.auto ? 'computed' : 'default'}`} onClick={() => onEdit(p, null)}>
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
          <Switch id={`o-${p.id}`} size="sm" checked={!computed} onCheckedChange={(on) => onEdit(p, on ? overrideSeed(p) : null)} />
        </div>
      )}
      {!computed && <ParamInput spec={p} value={v} invalid={!!err} onChange={(x) => onEdit(p, x)} />}
      {err ? <p className="text-xs text-destructive">{err}</p> : p.help && !compact ? <Hint>{p.help}</Hint> : null}
    </div>
  );
}
