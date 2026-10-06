import { useEffect, useRef, useState } from 'react';
import { RotateCcw } from 'lucide-react';
import { Hint } from '@/components/fields';
import { Tip } from '@/components/tip';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { Switch } from '@/components/ui/switch';
import { cn } from '@/lib/utils';
import { setConfig } from '../net/connection';
import type { ApplyKind, ConfigResult, Impact, ParamSpec, ParamValue } from '../protocol/messages';
import { useStore } from '../store/store';
import { fieldError } from './inject';
import { ParamInput, formatParam } from './ParamInput';
import { atRest, editApplied, isComputed, overrideSeed, shownValue } from './paramState';

/** Edits are sent this long after the last keystroke/slider move. */
const SEND_DEBOUNCE_MS = 200;

/**
 * Edits of world/volcano settings: pending values per parameter id (null = back to the default, or
 * to the computed value of an auto parameter), sent to the server as they are made (debounced). The
 * server decides how each is applied and may ask for confirmation first; pending edits disappear once
 * the schema reports them applied, or when they are refused or the user cancels.
 */
export function useParamEdits() {
  const schema = useStore((s) => s.schema);
  const [pending, setPending] = useState<Record<string, ParamValue | null>>({});
  const queue = useRef<Record<string, ParamValue | null>>({});
  const timer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(timer.current), []);
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
  const drop = (ids: string[]) =>
    setPending((p) => {
      const next = { ...p };
      for (const id of ids) delete next[id];
      return next;
    });
  const edit = (p: ParamSpec, v: ParamValue | null) => {
    setPending((x) => ({ ...x, [p.id]: v }));
    if (v !== null && p.type === 'number' && fieldError(p, v) !== null) return;
    queue.current[p.id] = v;
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => {
      const batch = queue.current;
      queue.current = {};
      void setConfig(batch).then((r) => showConfigResult(r, batch, drop));
    }, SEND_DEBOUNCE_MS);
  };
  return { pending, setPending, edit };
}

/**
 * Shows what the server did with a change, in its own words; when it wants confirmation (a reset), asks
 * the user with the server's description of the consequences.
 */
export function showConfigResult(r: ConfigResult, batch: Record<string, ParamValue | null>, drop: (ids: string[]) => void) {
  const s = useStore.getState();
  if (r.needsConfirmation && r.token) {
    const token = r.token;
    s.set({
      configPrompt: {
        result: r,
        confirm: () => void setConfig(batch, { confirm: token }).then((x) => showConfigResult(x, batch, drop)),
        cancel: () => drop(Object.keys(batch)),
      },
    });
    return;
  }
  if (!r.ok) {
    for (const e of r.errors ?? []) s.toast(`${e.path}: ${e.message}`, 'alert');
    drop(Object.keys(batch));
    return;
  }
  if (r.applied && r.applied !== 'live') {
    const c = r.consequences?.[0];
    if (c) s.toast(c.message, r.applied === 'reinit' ? 'warn' : 'info');
  }
  if (r.note) s.toast(r.note, 'warn');
  for (const w of r.warnings ?? []) s.toast(w, 'warn');
}

/** The server's confirmation request for a change that resets something, with its own description. */
export function ConfigConfirm() {
  const prompt = useStore((s) => s.configPrompt);
  const close = () => useStore.getState().set({ configPrompt: null });
  if (!prompt) return null;
  const changes = prompt.result.changes ?? [];
  return (
    <Dialog
      open
      onOpenChange={(o) => {
        if (!o) {
          prompt.cancel();
          close();
        }
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Apply {changes.length === 1 ? 'this change' : `these ${changes.length} changes`}?</DialogTitle>
          <DialogDescription>The server needs to do the following to apply it.</DialogDescription>
        </DialogHeader>
        <ul className="list-disc pl-5 text-sm">
          {(prompt.result.consequences ?? []).map((c) => (
            <li key={c.message}>{c.message}</li>
          ))}
        </ul>
        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => {
              prompt.cancel();
              close();
            }}
          >
            Cancel
          </Button>
          <Button
            variant="destructive"
            onClick={() => {
              prompt.confirm();
              close();
            }}
          >
            Apply
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** The server's prediction of what changing a setting does (shown only when it is more than "live"). */
export function ApplyBadge({ impact, kind }: { impact?: Impact; kind: ApplyKind | 'hot' | 'restart' }) {
  if (kind === 'live' || kind === 'hot') return null;
  return (
    <Tip content={impact?.message ?? kind}>
      <Badge variant="outline" className={kind === 'reload' ? 'text-sky-400' : 'text-amber-400'}>
        {kind}
      </Badge>
    </Tip>
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
          {changed && <span className="text-xs text-muted-foreground" role="status">applying…</span>}
          {!compact && <ApplyBadge impact={p.impact} kind={p.apply} />}
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
        {changed && <span className="text-xs text-muted-foreground" role="status">applying…</span>}
        {!compact && <ApplyBadge impact={p.impact} kind={p.apply} />}
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
