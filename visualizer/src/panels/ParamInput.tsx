import { useEffect, useState } from 'react';
import { SimpleSelect } from '@/components/fields';
import { Input } from '@/components/ui/input';
import { Slider } from '@/components/ui/slider';
import { Switch } from '@/components/ui/switch';
import { cn } from '@/lib/utils';
import type { ParamSpec, ParamValue } from '../protocol/messages';
import { fromSlider, toSlider } from './inject';

/** Number display that keeps significant digits without trailing noise. */
export function formatParam(v: ParamValue | null | undefined, spec?: ParamSpec): string {
  if (v === null || v === undefined) return '—';
  if (typeof v === 'boolean') return v ? 'on' : 'off';
  if (typeof v === 'string') return v;
  const a = Math.abs(v);
  const s = a !== 0 && (a >= 1e6 || a < 1e-3) ? v.toExponential(2) : String(Number(v.toPrecision(4)));
  return spec?.unit ? `${s} ${spec.unit}` : s;
}

/**
 * One parameter editor: a slider (when bounded) plus an exact number box, a switch or a choice.
 * `onChange` fires on every edit; the caller decides when to apply.
 */
export function ParamInput({ spec, value, onChange, invalid }: { spec: ParamSpec; value: ParamValue | null | undefined; onChange: (v: ParamValue) => void; invalid?: boolean }) {
  // null: an auto parameter the engine computes (or no value yet): show an empty box, not "null"
  const [text, setText] = useState(value === undefined || value === null ? '' : String(value));
  useEffect(() => {
    if (typeof value === 'number' && Number(text) !== value) setText(String(Number(value.toPrecision(6))));
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
          const v = Number(e.target.value.replace(/[\s,_]/g, ''));
          onChange(e.target.value.trim() === '' ? NaN : v);
        }}
      />
      {spec.unit && <span className="min-w-8 text-xs text-muted-foreground">{spec.unit}</span>}
    </span>
  );
}
