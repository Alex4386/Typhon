import { useEffect, useState } from 'react';
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
 * One parameter editor: a slider (when bounded) plus an exact number box, a checkbox or a choice.
 * `onChange` fires on every edit; the caller decides when to apply.
 */
export function ParamInput({ spec, value, onChange, invalid }: { spec: ParamSpec; value: ParamValue | undefined; onChange: (v: ParamValue) => void; invalid?: boolean }) {
  const [text, setText] = useState(value === undefined ? '' : String(value));
  useEffect(() => {
    if (typeof value === 'number' && Number(text) !== value) setText(String(Number(value.toPrecision(6))));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value]);
  const id = `p-${spec.id}`;
  if (spec.type === 'boolean') {
    return <input id={id} type="checkbox" checked={value === true} onChange={(e) => onChange(e.target.checked)} />;
  }
  if (spec.type === 'choice') {
    return (
      <select id={id} value={String(value ?? '')} onChange={(e) => onChange(e.target.value)}>
        {(spec.choices ?? []).map((c) => (
          <option key={c} value={c}>
            {c}
          </option>
        ))}
      </select>
    );
  }
  const bounded = spec.min !== undefined && spec.max !== undefined;
  const num = typeof value === 'number' ? value : Number(value);
  return (
    <span className="param-input">
      {bounded && (
        <input
          type="range"
          min={0}
          max={1}
          step={0.001}
          aria-label={spec.label}
          value={Number.isFinite(num) ? Math.min(1, Math.max(0, toSlider(spec, num))) : 0}
          onChange={(e) => onChange(fromSlider(spec, Number(e.target.value)))}
        />
      )}
      <input
        id={id}
        type="text"
        inputMode="decimal"
        className={invalid ? 'invalid' : ''}
        aria-invalid={invalid}
        value={text}
        onChange={(e) => {
          setText(e.target.value);
          const v = Number(e.target.value.replace(/[\s,_]/g, ''));
          onChange(e.target.value.trim() === '' ? NaN : v);
        }}
      />
      {spec.unit && <span className="unit">{spec.unit}</span>}
    </span>
  );
}
