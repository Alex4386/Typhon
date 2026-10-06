import { useState } from 'react';
import { Flame } from 'lucide-react';
import { Hint } from '@/components/fields';
import { Button } from '@/components/ui/button';
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { placeChamber } from '../net/connection';
import type { ConfigResult, ParamSpec, ParamValue } from '../protocol/messages';
import { formatPlace } from '../store/entities';
import { useStore } from '../store/store';
import { fieldError } from './inject';
import { ParamInput } from './ParamInput';

/**
 * Shows what the server did, in its words; when it asks to confirm (a reset), shows its description
 * and re-sends with the token through {@code retry}.
 */
export function showServerResult(r: ConfigResult, retry: (token: string) => Promise<ConfigResult>, done?: (r: ConfigResult) => void) {
  const s = useStore.getState();
  if (r.needsConfirmation && r.token) {
    const token = r.token;
    s.set({
      configPrompt: {
        result: r,
        confirm: () => void retry(token).then((x) => showServerResult(x, retry, done)),
        cancel: () => {},
      },
    });
    return;
  }
  if (!r.ok) {
    for (const e of r.errors ?? []) s.toast(`${e.path}: ${e.message}`, 'alert');
    return;
  }
  const c = r.consequences?.[0];
  if (c) s.toast(c.message, r.applied === 'reinit' ? 'warn' : 'info');
  if (r.note) s.toast(r.note, 'warn');
  for (const w of r.warnings ?? []) s.toast(w, 'warn');
  done?.(r);
}

/** Depth and magma of a new chamber at the clicked point (fields and defaults from the server schema). */
export function PlaceChamberDialog() {
  const at = useStore((s) => s.placeAt);
  const fields: ParamSpec[] | undefined = useStore((s) => s.schema?.commands.placeChamber);
  const [values, setValues] = useState<Record<string, ParamValue>>({});
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const close = () => {
    useStore.getState().set({ placeAt: null });
    setValues({});
    setName('');
  };
  if (!at) return null;
  const specs = fields ?? [];
  const errors = specs.map((p) => (values[p.id] !== undefined ? fieldError(p, values[p.id]) : null));
  const invalid = errors.some((e) => e !== null);
  const groups = [...new Set(specs.map((p) => p.group))];
  const place = () => {
    const sent: Record<string, number> = {};
    for (const [k, v] of Object.entries(values)) if (typeof v === 'number') sent[k] = v; // the rest: server defaults
    setBusy(true);
    void placeChamber(at, sent, name.trim() || undefined).then((r) => {
      setBusy(false);
      showServerResult(r, () => placeChamber(at, sent, name.trim() || undefined), (ok) => {
        if (ok.volcanoId) useStore.getState().select({ type: 'entity', id: `chamber:${ok.volcanoId}` });
      });
      if (r.ok || r.needsConfirmation) close();
    });
  };
  return (
    <Dialog open onOpenChange={(o) => !o && close()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Flame className="size-4" /> Place a magma chamber
          </DialogTitle>
          <DialogDescription>
            Below {formatPlace(at)}. Nothing is built: the vent forms where magma first reaches the surface, and the eruptions build whatever
            grows there. Leave a field as it is to use the server's default.
          </DialogDescription>
        </DialogHeader>
        {!fields && <Hint>This server does not offer chamber placement.</Hint>}
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="pc-name">Name</Label>
          <Input id="pc-name" placeholder="automatic" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        {groups.map((g) => (
          <section key={g} className="flex flex-col gap-2">
            <span className="text-xs font-medium text-muted-foreground">{g}</span>
            {specs
              .filter((p) => p.group === g)
              .map((p) => {
                const k = specs.indexOf(p);
                return (
                  <div key={p.id} className="flex flex-col gap-1">
                    <Label htmlFor={`p-${p.id}`} className="font-normal" title={p.help}>
                      {p.label}
                      {p.unit ? ` (${p.unit})` : ''}
                    </Label>
                    <ParamInput spec={p} value={values[p.id] ?? p.default} invalid={errors[k] !== null} onChange={(v) => setValues((x) => ({ ...x, [p.id]: v }))} />
                    {errors[k] ? <p className="text-xs text-destructive">{errors[k]}</p> : p.help ? <Hint>{p.help}</Hint> : null}
                  </div>
                );
              })}
          </section>
        ))}
        <DialogFooter>
          <DialogClose render={<Button variant="outline" />}>Cancel</DialogClose>
          <Button disabled={!fields || invalid || busy} onClick={place}>
            Place chamber
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
