import type { ReactNode } from 'react';
import { cn } from '@/lib/utils';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Slider } from '@/components/ui/slider';
import { Switch } from '@/components/ui/switch';
import { Tip } from './tip';

/** A shadcn Select from a list of [value, label] pairs. */
export function SimpleSelect<T extends string>({
  value,
  onChange,
  options,
  label,
  id,
  size = 'sm',
  className,
  disabled,
}: {
  value: T;
  onChange: (v: T) => void;
  options: readonly (readonly [T, ReactNode])[];
  label: string;
  id?: string;
  size?: 'sm' | 'default';
  className?: string;
  disabled?: boolean;
}) {
  return (
    <Select value={value} onValueChange={(v) => v !== null && onChange(v as T)} items={options.map(([value, label]) => ({ value, label }))} disabled={disabled}>
      <SelectTrigger id={id} size={size} aria-label={label} className={className}>
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {options.map(([v, l]) => (
          <SelectItem key={v} value={v}>
            {l}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

/** A labelled on/off switch row. */
export function SwitchRow({ checked, onChange, label, help, id }: { checked: boolean; onChange: (v: boolean) => void; label: ReactNode; help?: string; id: string }) {
  return (
    <Tip content={help} side="left">
      <div className="flex items-center justify-between gap-3 py-0.5">
        <Label htmlFor={id} className="font-normal">
          {label}
        </Label>
        <Switch id={id} checked={checked} onCheckedChange={(v) => onChange(v)} />
      </div>
    </Tip>
  );
}

/** A labelled single-value slider with its current value shown on the right. */
export function SliderRow({
  label,
  value,
  display,
  min,
  max,
  step,
  onChange,
  onCommit,
  help,
  className,
}: {
  label: ReactNode;
  value: number;
  display: ReactNode;
  min: number;
  max: number;
  step: number;
  onChange: (v: number) => void;
  onCommit?: (v: number) => void;
  help?: string;
  className?: string;
}) {
  const one = (v: number | readonly number[]) => (Array.isArray(v) ? (v as number[])[0] : (v as number));
  return (
    <div className={cn('flex flex-col gap-1.5', className)}>
      <div className="flex items-baseline justify-between text-sm">
        <Tip content={help} side="left">
          <span>{label}</span>
        </Tip>
        <span className="text-muted-foreground tabular-nums">{display}</span>
      </div>
      <Slider
        aria-label={typeof label === 'string' ? label : undefined}
        value={[value]}
        min={min}
        max={max}
        step={step}
        onValueChange={(v) => onChange(one(v))}
        onValueCommitted={onCommit ? (v) => onCommit(one(v)) : undefined}
      />
    </div>
  );
}

/** Muted helper text. */
export function Hint({ children, className }: { children: ReactNode; className?: string }) {
  return <p className={cn('text-xs text-muted-foreground', className)}>{children}</p>;
}
