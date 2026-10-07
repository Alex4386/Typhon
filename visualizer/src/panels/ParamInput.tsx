import { useEffect, useState } from 'react';
import { SimpleSelect } from '@/components/fields';
import { Input } from '@/components/ui/input';
import { Slider } from '@/components/ui/slider';
import { Switch } from '@/components/ui/switch';
import { cn } from '@/lib/utils';
import type { ParamSpec, ParamValue } from '../protocol/messages';
import { displayUnit, formatQuantity, type DisplayUnit } from '../util/quantity';
import { fromSlider, toSlider } from './inject';

/** Number display that keeps significant digits without trailing noise. */
/** The unit a setting is edited in: chosen from its range and default, so it stays put while editing. */
export function editUnit(spec: ParamSpec): DisplayUnit {
  // the default's magnitude (else the range's top): typical values read naturally in it
  const ref = typeof spec.default === 'number' && spec.default !== 0 ? spec.default : Math.abs(spec.max ?? 0);
  return displayUnit(spec.unit, ref);
}

export function formatParam(v: ParamValue | null | undefined, spec?: ParamSpec): string {
  if (v === null || v === undefined) return '—';
  if (typeof v === 'boolean') return v ? 'on' : 'off';
  if (typeof v === 'string') return v;
  // a whole part (an added chamber or pathway in the change history)
  if (typeof v !== 'number') return typeof v === 'object' && v && 'id' in v ? String((v as { id: unknown }).id) : 'set';
  return formatQuantity(v, spec?.unit);
}

/**
 * One parameter editor: a slider (when bounded) plus an exact number box, a switch or a choice.
 * `onChange` fires on every edit; the caller decides when to apply.
 */
export function ParamInput({ spec, value, onChange, invalid }: { spec: ParamSpec; value: ParamValue | null | undefined; onChange: (v: ParamValue) => void; invalid?: boolean }) {
  // null: an auto parameter the engine computes (or no value yet): show an empty box, not "null"
  // the box edits in a unit fit for the range (km³, %, MW), fixed per setting so it does not jump
  const shown = editUnit(spec);
  const toText = (v: number) => String(Number((v * shown.factor).toPrecision(6)));
  const [text, setText] = useState(typeof value === 'number' ? toText(value) : '');
  useEffect(() => {
    if (typeof value === 'number' && Number(text) / shown.factor !== value) setText(toText(value));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);
  const id = `p-${spec.id}`;
  if (spec.type === 'boolean') {
    return <Switch id={id} checked={value === true} onCheckedChange={(v) => onChange(v)} />;
  }
  if (spec.type === 'choice') {
    return <SimpleSelect id={id} label={spec.label} value={String(value ?? '')} onChange={onChange} options={(spec.choices ?? []).map((c) => [c, c] as const)} />;
  }
  const bounded = spec.min !== undefined && spec.max !== undefined;
  const num = typeof value === 'number' ? value : Number(value);
  return (
    <span className="flex w-full items-center gap-2">
      {bounded && (
        <Slider
          className="min-w-20 flex-1"
          min={0}
          max={1}
          step={0.001}
          aria-label={spec.label}
          value={[Number.isFinite(num) ? Math.min(1, Math.max(0, toSlider(spec, num))) : 0]}
          onValueChange={(v) => onChange(fromSlider(spec, Array.isArray(v) ? (v as number[])[0] : (v as number)))}
        />
      )}
      <Input
        id={id}
        type="text"
        inputMode="decimal"
        className={cn('h-7 w-24 text-right tabular-nums', !bounded && 'flex-1')}
        aria-invalid={invalid}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          const v = Number(e.target.value.replace(/[\s,_]/g, '')) / shown.factor;
          onChange(e.target.value.trim() === '' ? NaN : v);
        }}
      />
      {shown.unit && <span className="min-w-8 text-xs text-muted-foreground">{shown.unit}</span>}
    </span>
  );
}
