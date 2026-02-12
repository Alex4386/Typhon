import { Badge } from '@/components/ui/badge';

/* ── VEI Scale ───────────────────────────────────────────────────────────── */

// Real-world VEI thresholds based on ejected tephra volume (1 block = 1 m³).
export const VEI_THRESHOLDS = [
  { vei: 0, min: 0,                    label: 'Non-explosive',   color: 'bg-gray-400' },
  { vei: 1, min: 10_000,               label: 'Gentle',          color: 'bg-green-500' },
  { vei: 2, min: 1_000_000,            label: 'Explosive',       color: 'bg-yellow-500' },
  { vei: 3, min: 10_000_000,           label: 'Severe',          color: 'bg-orange-500' },
  { vei: 4, min: 100_000_000,          label: 'Cataclysmic',     color: 'bg-red-500' },
  { vei: 5, min: 1_000_000_000,        label: 'Paroxysmal',      color: 'bg-red-700' },
  { vei: 6, min: 10_000_000_000,       label: 'Colossal',        color: 'bg-purple-600' },
  { vei: 7, min: 100_000_000_000,      label: 'Super-colossal',  color: 'bg-purple-900' },
  { vei: 8, min: 1_000_000_000_000,    label: 'Mega-colossal',   color: 'bg-fuchsia-900' },
];

export function getVEI(volume: number) {
  for (let i = VEI_THRESHOLDS.length - 1; i >= 0; i--) {
    if (volume >= VEI_THRESHOLDS[i].min) return VEI_THRESHOLDS[i];
  }
  return VEI_THRESHOLDS[0];
}

/* ── Formatters ──────────────────────────────────────────────────────────── */

export function formatVolume(blocks: number): string {
  const km3 = blocks / 1e9;
  if (km3 >= 0.01) return `${km3.toFixed(2)} km³`;
  return `${blocks.toLocaleString()} m³`;
}

/* ── VEI Badge ───────────────────────────────────────────────────────────── */

export function VEIBadge({ volume }: { volume: number }) {
  const vei = getVEI(volume);
  return (
    <Badge variant="secondary" className="gap-1 font-mono text-xs">
      <span className={`inline-block size-2 rounded-full ${vei.color}`} />
      VEI {vei.vei}
    </Badge>
  );
}

/* ── Ejecta Card ─────────────────────────────────────────────────────────── */

export function EjectaCard({ label, volume, rate, subtitle }: {
  label: string;
  volume: number;
  rate?: number;
  subtitle?: string;
}) {
  const vei = getVEI(volume);

  return (
    <div className="rounded-lg border border-border bg-card p-4 space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-xs text-muted-foreground uppercase tracking-wider">{label}</span>
        <span className="text-xs text-muted-foreground">{formatVolume(volume)}</span>
      </div>
      <div className="flex items-baseline gap-2">
        <span className="text-2xl font-bold tabular-nums">{vei.vei}</span>
        <span className="text-sm font-medium text-muted-foreground">{vei.label}</span>
        {rate != null && (
          <span className="ml-auto text-xs tabular-nums text-muted-foreground">{rate.toLocaleString()} m³/s</span>
        )}
      </div>
      <div className="flex gap-0.5">
        {VEI_THRESHOLDS.map((t, i) => (
          <div
            key={i}
            className={`h-2.5 flex-1 rounded-sm transition-colors ${i <= vei.vei ? t.color : 'bg-muted'}`}
          />
        ))}
      </div>
      {subtitle && (
        <div className="text-[10px] text-muted-foreground">{subtitle}</div>
      )}
    </div>
  );
}
