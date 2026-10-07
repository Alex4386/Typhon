import { useEffect, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { command } from '../net/connection';
import type { EntityView } from '../store/entities';
import { MAGMA_FIELDS, magmaCommand } from './setMagma';

/**
 * Replaces the composition of the magma in the selected chamber (bulk temperature, silica, water, CO₂),
 * prefilled with the chamber's current values. The magma the deep supply delivers is set in Supply.
 */
export function SetMagmaForm({ e }: { e: EntityView }) {
  const initial = () => Object.fromEntries(MAGMA_FIELDS.map((f) => [f.id, fmt(Number(e.props[f.prop]))]));
  const [values, setValues] = useState<Record<string, string>>(initial);
  const [dirty, setDirty] = useState(false);
  // follow the chamber until the user starts typing, and start over for another chamber
  useEffect(() => setDirty(false), [e.id]);
  useEffect(() => {
    if (!dirty) setValues(initial());
  }, [e, dirty]); // eslint-disable-line react-hooks/exhaustive-deps

  const built = magmaCommand(e, values);
  return (
    <div className="flex flex-col gap-2 py-2">
      {MAGMA_FIELDS.map((f) => (
        <div key={f.id} className="flex items-center gap-2">
          <Label htmlFor={`set-magma-${f.id}`} className="flex-1 text-sm">{f.label}</Label>
          <Input
            id={`set-magma-${f.id}`}
            className="h-8 w-24 text-right"
            inputMode="decimal"
            value={values[f.id]}
            onChange={(ev) => {
              setDirty(true);
              setValues({ ...values, [f.id]: ev.target.value });
            }}
          />
          <span className="w-10 text-xs text-muted-foreground">{f.unit}</span>
        </div>
      ))}
      {built.error && <p className="text-xs text-destructive">{built.error}</p>}
      <div className="flex justify-end gap-2">
        {dirty && (
          <Button size="sm" variant="ghost" onClick={() => setDirty(false)}>
            Reset
          </Button>
        )}
        <Button
          size="sm"
          disabled={!built.command || !dirty}
          onClick={() => {
            if (!built.command) return;
            command(built.command);
            setDirty(false);
          }}
        >
          Apply to the chamber
        </Button>
      </div>
      <p className="text-xs text-muted-foreground">
        Replaces the magma in the chamber now: crystals, gas and viscosity follow. The magma arriving from depth is set in Supply.
      </p>
    </div>
  );
}

function fmt(v: number): string {
  return Number.isFinite(v) ? String(Math.round(v * 100) / 100) : '';
}
