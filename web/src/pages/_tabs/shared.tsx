import { useEffect, useState } from 'react';
import type { VentDetail, VentMetrics, ConfigNode } from '@/transport/types';
import { Badge } from '@/components/ui/badge';
import { Collapsible, CollapsibleTrigger, CollapsibleContent } from '@/components/ui/collapsible';
import { Flame, Bomb, Wind, ChevronsUpDown, Check } from 'lucide-react';
import { EjectaCard } from '@/components/volcano/EjectaCard';

export type LiveVent = VentDetail & VentMetrics;

/* ── Constants ────────────────────────────────────────────────────────────── */

export const STATUS_COLORS: Record<string, string> = {
  ERUPTING: 'bg-red-500',
  ERUPTION_IMMINENT: 'bg-orange-500',
  MAJOR_ACTIVITY: 'bg-amber-500',
  MINOR_ACTIVITY: 'bg-yellow-500',
  DORMANT: 'bg-blue-400',
  EXTINCT: 'bg-gray-400',
};

export const STYLE_PROFILES: Record<string, { description: string; caldera: boolean }> = {
  hawaiian:    { description: 'Effusive lava fountains, low explosivity', caldera: false },
  strombolian: { description: 'Lava fountains with volcanic bombs', caldera: false },
  vulcanian:   { description: 'Moderate explosions with extended ash', caldera: false },
  pelean:      { description: 'Lava dome collapse, pyroclastic flows', caldera: false },
  plinian:     { description: 'Massive ash column, caldera collapse', caldera: true },
  lava_dome:   { description: 'Slow dome-building eruption', caldera: false },
};

export const SILICATE_ZONES = [
  { min: 0.30, max: 0.45, label: 'Ultramafic', short: 'UMF', color: '#1c1917', labelColor: '#a8a29e' },
  { min: 0.45, max: 0.53, label: 'Basalt',     short: 'BAS', color: '#44403c', labelColor: '#d6d3d1' },
  { min: 0.53, max: 0.63, label: 'Andesite',   short: 'AND', color: '#6b6560', labelColor: '#fafaf9' },
  { min: 0.63, max: 0.77, label: 'Dacite',     short: 'DAC', color: '#a8a29e', labelColor: '#1c1917' },
  { min: 0.77, max: 0.90, label: 'Rhyolite',   short: 'RHY', color: '#d6d3d1', labelColor: '#292524' },
];

export const GAS_ZONES = [
  { min: 0.0, max: 0.2, label: 'Effusive',      short: 'EFF', color: '#1e3a5f', labelColor: '#93c5fd' },
  { min: 0.2, max: 0.5, label: 'Mild',           short: 'MLD', color: '#3b4f6b', labelColor: '#bfdbfe' },
  { min: 0.5, max: 0.8, label: 'Explosive',      short: 'EXP', color: '#7c3a2e', labelColor: '#fecaca' },
  { min: 0.8, max: 1.0, label: 'Highly Explosive', short: 'H-EXP', color: '#991b1b', labelColor: '#fef2f2' },
];

export const VENT_STATUSES = ['EXTINCT', 'DORMANT', 'MINOR_ACTIVITY', 'MAJOR_ACTIVITY', 'ERUPTION_IMMINENT', 'ERUPTING'] as const;

export const BUILDER_TYPES = [
  { value: 'y_threshold', label: 'Y Threshold', description: 'Stop eruption when summit reaches a target Y height' },
] as const;

/* ── Utility functions ────────────────────────────────────────────────────── */

export function getSilicateClassification(value: number): string {
  for (const zone of SILICATE_ZONES) {
    if (value < zone.max) return zone.label;
  }
  return SILICATE_ZONES[SILICATE_ZONES.length - 1].label;
}

export function getGasClassification(value: number): string {
  for (const zone of GAS_ZONES) {
    if (value < zone.max) return zone.label;
  }
  return GAS_ZONES[GAS_ZONES.length - 1].label;
}

export function fmtBlock(loc: { x: number; y: number; z: number } | undefined | null): string {
  if (!loc) return '--';
  return `${loc.x}, ${loc.y}, ${loc.z}`;
}

export function nodeLabel(key: string): string {
  const withoutPrefix = key.replace(/^[^:]+:/, '');
  return withoutPrefix
    .replace(/:/g, ' ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .split(' ')
    .map(w => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

/* ── Shared Components ────────────────────────────────────────────────────── */

export function ActivityCards({ vent }: { vent: LiveVent }) {
  return (
    <div className="grid gap-3 sm:grid-cols-3">
      <ActivityCard
        icon={Flame} label="Lava" active={vent.isFlowingLava} color="text-orange-400" barColor="bg-orange-400"
        current={vent.currentNormalLavaFlowLength} max={vent.longestNormalLavaFlowLength} unit="m"
      />
      <ActivityCard
        icon={Bomb} label="Bombs" active={vent.isExploding} color="text-red-400" barColor="bg-red-400"
        current={vent.bombMaxDistance} max={vent.bombMaxDistance} unit="m"
      />
      <ActivityCard
        icon={Wind} label="Ash" active={(vent.currentAshFlowLength ?? 0) > 0} color="text-gray-400" barColor="bg-gray-400"
        current={vent.currentAshFlowLength} max={vent.longestAshFlowLength} unit="m"
      />
    </div>
  );
}

export function EjectaCards({ vent }: { vent: LiveVent }) {
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <EjectaCard label="Current Eruption" volume={vent.currentEjecta} rate={vent.ejectaPerSecond} />
      <EjectaCard label="Lifetime Total" volume={vent.totalEjecta} />
    </div>
  );
}

export function ActivityCard({
  icon: Icon, label, active, color, barColor, current, max, unit,
}: {
  icon: React.ElementType;
  label: string;
  active: boolean;
  color: string;
  barColor: string;
  current: number;
  max: number;
  unit: string;
}) {
  const pct = max > 0 ? Math.min((current / max) * 100, 100) : 0;

  return (
    <div className="rounded-lg border border-border bg-card p-4 space-y-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Icon className={`size-4 ${active ? color : 'text-muted-foreground'}`} />
          <span className="text-sm font-medium">{label}</span>
        </div>
        <Badge variant={active ? 'default' : 'outline'} className={active ? 'bg-green-600 text-white' : ''}>
          {active ? 'Active' : 'Idle'}
        </Badge>
      </div>
      <div className="space-y-1">
        <div className="flex justify-between text-xs text-muted-foreground">
          <span>{current.toFixed(1)}{unit}</span>
          <span>/ {max.toFixed(1)}{unit}</span>
        </div>
        <div className="h-1.5 rounded-full bg-muted overflow-hidden">
          <div
            className={`h-full rounded-full transition-all ${active ? barColor : 'bg-muted-foreground/30'}`}
            style={{ width: `${pct}%` }}
          />
        </div>
      </div>
    </div>
  );
}

export function StatCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-border bg-card p-3">
      <div className="text-xs text-muted-foreground uppercase tracking-wider mb-1">{label}</div>
      <div className="text-sm font-medium">{value}</div>
    </div>
  );
}

export function EditableStatCard({ label, node, onChange }: { label: string; node: ConfigNode; onChange: (v: string) => void }) {
  const [local, setLocal] = useState(String(node.value));
  useEffect(() => { setLocal(String(node.value)); }, [node.value]);
  const commit = () => { if (local !== String(node.value)) onChange(local); };

  return (
    <div className="rounded-lg border border-border bg-card p-3">
      <div className="text-xs text-muted-foreground uppercase tracking-wider mb-1">{label}</div>
      <input
        type="number"
        value={local}
        step={node.type === 'int' ? 1 : 0.01}
        min={node.min}
        max={node.max}
        onChange={e => setLocal(e.target.value)}
        onBlur={commit}
        onKeyDown={e => { if (e.key === 'Enter') commit(); }}
        className="w-full text-sm font-medium bg-transparent border-b border-border/50 focus:border-primary outline-none tabular-nums py-0.5"
      />
    </div>
  );
}

/* ── Config Components ────────────────────────────────────────────────────── */

export function ConfigSection({ title, nodes, onSetConfig }: {
  title: string;
  nodes: ConfigNode[];
  onSetConfig: (key: string, value: string) => void;
}) {
  return (
    <div className="rounded-lg border border-border bg-card p-5 space-y-3">
      <span className="text-sm font-medium">{title}</span>
      <div className="space-y-2">
        {nodes.map(node => (
          <ConfigNodeField key={node.key} node={node} onChange={v => onSetConfig(node.key, v)} />
        ))}
      </div>
    </div>
  );
}

export function CollapsibleConfigSection({ title, nodes, onSetConfig }: {
  title: string;
  nodes: ConfigNode[];
  onSetConfig: (key: string, value: string) => void;
}) {
  return (
    <Collapsible>
      <div className="rounded-lg border border-border bg-card">
        <CollapsibleTrigger className="flex w-full items-center justify-between p-4 text-sm font-medium hover:bg-muted/50 transition-colors rounded-lg">
          {title}
          <ChevronsUpDown className="size-4 text-muted-foreground" />
        </CollapsibleTrigger>
        <CollapsibleContent>
          <div className="px-5 pb-5 space-y-2">
            {nodes.map(node => (
              <ConfigNodeField key={node.key} node={node} onChange={v => onSetConfig(node.key, v)} />
            ))}
          </div>
        </CollapsibleContent>
      </div>
    </Collapsible>
  );
}

export function ConfigNodeField({ node, onChange }: { node: ConfigNode; onChange: (value: string) => void }) {
  const label = nodeLabel(node.key);

  if (node.type === 'enum') {
    return (
      <div className="flex items-center justify-between gap-4">
        <span className="text-sm text-muted-foreground">{label}</span>
        <select
          value={String(node.value)}
          onChange={e => onChange(e.target.value)}
          className="text-sm rounded-md border border-border bg-background px-3 py-1.5"
        >
          {node.options?.map(opt => (
            <option key={opt} value={opt}>{opt}</option>
          ))}
        </select>
      </div>
    );
  }

  if (node.type === 'boolean') {
    const checked = node.value === true || node.value === 'true';
    return (
      <div className="flex items-center justify-between gap-4">
        <span className="text-sm text-muted-foreground">{label}</span>
        <button
          onClick={() => onChange(String(!checked))}
          className={`relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors ${checked ? 'bg-primary' : 'bg-muted'}`}
        >
          <span className={`inline-block size-3.5 rounded-full bg-white transition-transform ${checked ? 'translate-x-[18px]' : 'translate-x-0.5'}`} />
        </button>
      </div>
    );
  }

  if (node.type === 'string') {
    return <StringNodeField node={node} label={label} onChange={onChange} />;
  }

  return <NumberNodeField node={node} label={label} onChange={onChange} />;
}

export function NumberNodeField({ node, label, onChange }: { node: ConfigNode; label: string; onChange: (v: string) => void }) {
  const [local, setLocal] = useState(String(node.value));

  useEffect(() => { setLocal(String(node.value)); }, [node.value]);

  const dirty = local !== String(node.value);
  const commit = () => { if (dirty) onChange(local); };

  return (
    <div className="flex items-center justify-between gap-4">
      <div className="flex items-center gap-2 min-w-0">
        <span className="text-sm text-muted-foreground truncate">{label}</span>
        {node.min != null && node.max != null && (
          <span className="text-[10px] text-muted-foreground/60 shrink-0">[{node.min}–{node.max}]</span>
        )}
      </div>
      <div className="flex items-center gap-1">
        <input
          type="number"
          value={local}
          step={node.type === 'int' ? 1 : 0.01}
          min={node.min}
          max={node.max}
          onChange={e => setLocal(e.target.value)}
          onBlur={commit}
          onKeyDown={e => { if (e.key === 'Enter') commit(); }}
          className={`w-24 text-sm rounded-md border bg-background px-2 py-1 text-right tabular-nums ${dirty ? 'border-primary' : 'border-border'}`}
        />
        {dirty && (
          <button onClick={commit} className="size-7 flex items-center justify-center rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors shrink-0">
            <Check className="size-3.5" />
          </button>
        )}
      </div>
    </div>
  );
}

export function StringNodeField({ node, label, onChange }: { node: ConfigNode; label: string; onChange: (v: string) => void }) {
  const [local, setLocal] = useState(String(node.value));

  useEffect(() => { setLocal(String(node.value)); }, [node.value]);

  const dirty = local !== String(node.value);
  const commit = () => { if (dirty) onChange(local); };

  return (
    <div className="flex items-center justify-between gap-4">
      <span className="text-sm text-muted-foreground truncate">{label}</span>
      <div className="flex items-center gap-1">
        <input
          type="text"
          value={local}
          onChange={e => setLocal(e.target.value)}
          onBlur={commit}
          onKeyDown={e => { if (e.key === 'Enter') commit(); }}
          className={`w-32 text-sm rounded-md border bg-background px-2 py-1 text-right ${dirty ? 'border-primary' : 'border-border'}`}
        />
        {dirty && (
          <button onClick={commit} className="size-7 flex items-center justify-center rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors shrink-0">
            <Check className="size-3.5" />
          </button>
        )}
      </div>
    </div>
  );
}

/* ── Sliders ──────────────────────────────────────────────────────────────── */

export function SilicateSlider({ node, onChange }: { node: ConfigNode; onChange: (v: string) => void }) {
  const min = node.min ?? 0.3;
  const max = node.max ?? 0.9;
  const [local, setLocal] = useState(Number(node.value));

  useEffect(() => { setLocal(Number(node.value)); }, [node.value]);

  const commit = () => onChange(String(local));
  const classification = getSilicateClassification(local);
  const pct = max > min ? ((local - min) / (max - min)) * 100 : 0;

  return (
    <div className="rounded-lg border border-border bg-card p-4 space-y-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium">Silicate Level</span>
          <span className="text-[10px] text-muted-foreground">(SiO₂)</span>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs font-medium px-2 py-0.5 rounded-full border border-border bg-muted">
            {classification}
          </span>
          <span className="text-sm font-bold tabular-nums">{(local * 100).toFixed(0)}%</span>
        </div>
      </div>

      <div className="relative">
        <div className="h-7 rounded-md overflow-hidden flex">
          {SILICATE_ZONES.map(zone => {
            const zMin = Math.max(zone.min, min);
            const zMax = Math.min(zone.max, max);
            if (zMax <= zMin) return null;
            const width = ((zMax - zMin) / (max - min)) * 100;
            return (
              <div
                key={zone.label}
                className="flex items-center justify-center relative"
                style={{ width: `${width}%`, background: zone.color }}
              >
                <span
                  className="text-[9px] font-semibold tracking-wide truncate px-0.5 select-none"
                  style={{ color: zone.labelColor }}
                >
                  {width > 18 ? zone.label : zone.short}
                </span>
              </div>
            );
          })}
        </div>

        <div
          className="absolute top-0 h-7 pointer-events-none"
          style={{ left: `calc(${pct}% - 1px)` }}
        >
          <div className="w-0.5 h-full bg-white shadow-[0_0_4px_rgba(255,255,255,0.6)]" />
        </div>
      </div>

      <input
        type="range"
        min={min}
        max={max}
        step={0.01}
        value={local}
        onChange={e => setLocal(Number(e.target.value))}
        onMouseUp={commit}
        onTouchEnd={commit}
        className="w-full h-2 rounded-full appearance-none cursor-pointer accent-primary bg-muted"
      />

      <div className="flex justify-between text-[10px] text-muted-foreground/60">
        <span>{(min * 100).toFixed(0)}%</span>
        <span>{(max * 100).toFixed(0)}%</span>
      </div>
    </div>
  );
}

export function GasContentSlider({ node, onChange }: { node: ConfigNode; onChange: (v: string) => void }) {
  const min = node.min ?? 0;
  const max = node.max ?? 1;
  const [local, setLocal] = useState(Number(node.value));

  useEffect(() => { setLocal(Number(node.value)); }, [node.value]);

  const commit = () => onChange(String(local));
  const classification = getGasClassification(local);
  const pct = max > min ? ((local - min) / (max - min)) * 100 : 0;

  return (
    <div className="rounded-lg border border-border bg-card p-4 space-y-3">
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium">Gas Content</span>
        <div className="flex items-center gap-2">
          <span className="text-xs font-medium px-2 py-0.5 rounded-full border border-border bg-muted">
            {classification}
          </span>
          <span className="text-sm font-bold tabular-nums">{(local * 100).toFixed(0)}%</span>
        </div>
      </div>

      <div className="relative">
        <div className="h-7 rounded-md overflow-hidden flex">
          {GAS_ZONES.map(zone => {
            const zMin = Math.max(zone.min, min);
            const zMax = Math.min(zone.max, max);
            if (zMax <= zMin) return null;
            const width = ((zMax - zMin) / (max - min)) * 100;
            return (
              <div
                key={zone.label}
                className="flex items-center justify-center relative"
                style={{ width: `${width}%`, background: zone.color }}
              >
                <span
                  className="text-[9px] font-semibold tracking-wide truncate px-0.5 select-none"
                  style={{ color: zone.labelColor }}
                >
                  {width > 18 ? zone.label : zone.short}
                </span>
              </div>
            );
          })}
        </div>

        <div
          className="absolute top-0 h-7 pointer-events-none"
          style={{ left: `calc(${pct}% - 1px)` }}
        >
          <div className="w-0.5 h-full bg-white shadow-[0_0_4px_rgba(255,255,255,0.6)]" />
        </div>
      </div>

      <input
        type="range"
        min={min}
        max={max}
        step={0.01}
        value={local}
        onChange={e => setLocal(Number(e.target.value))}
        onMouseUp={commit}
        onTouchEnd={commit}
        className="w-full h-2 rounded-full appearance-none cursor-pointer accent-primary bg-muted"
      />

      <div className="flex justify-between text-[10px] text-muted-foreground/60">
        <span>{(min * 100).toFixed(0)}%</span>
        <span>{(max * 100).toFixed(0)}%</span>
      </div>
    </div>
  );
}

export function SliderCard({ label, node, displayValue, onChange, colorFrom, colorTo }: {
  label: string;
  node: ConfigNode;
  displayValue: string;
  onChange: (value: string) => void;
  colorFrom: string;
  colorTo: string;
}) {
  const min = node.min ?? 0;
  const max = node.max ?? 1;
  const step = node.type === 'int' ? 1 : 0.01;
  const [local, setLocal] = useState(Number(node.value));

  useEffect(() => { setLocal(Number(node.value)); }, [node.value]);

  const commit = () => onChange(String(local));

  return (
    <div className="rounded-lg border border-border bg-card p-4 space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium">{label}</span>
        <span className="text-sm font-bold tabular-nums">{node.type === 'int' ? local : `${(local * 100).toFixed(0)}%`}</span>
      </div>
      <div className="space-y-1.5">
        <input
          type="range"
          min={min}
          max={max}
          step={step}
          value={local}
          onChange={e => setLocal(Number(e.target.value))}
          onMouseUp={commit}
          onTouchEnd={commit}
          className="w-full h-2 rounded-full appearance-none cursor-pointer accent-primary bg-muted"
        />
        <div className="flex justify-between text-[10px] text-muted-foreground/60">
          <span>{min}</span>
          <span>{max}</span>
        </div>
      </div>
    </div>
  );
}
