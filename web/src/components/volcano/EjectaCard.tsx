const VEI_COLORS = [
  'bg-gray-500', 'bg-green-500', 'bg-lime-500', 'bg-yellow-500',
  'bg-amber-500', 'bg-orange-500', 'bg-red-500', 'bg-red-700', 'bg-red-900',
];

function getVEI(ejecta: number): number {
  if (ejecta < 1e4) return 0;
  if (ejecta <= 1e6) return 1;
  if (ejecta <= 1e7) return 2;
  if (ejecta <= 1e8) return 3;
  if (ejecta <= 1e9) return 4;
  if (ejecta <= 1e10) return 5;
  if (ejecta <= 1e11) return 6;
  if (ejecta <= 1e12) return 7;
  return 8;
}

export function formatVolume(blocks: number): string {
  const km3 = blocks / 1e9;
  if (km3 >= 0.01) return `${km3.toFixed(2)} km³`;
  return `${blocks.toLocaleString()} m³`;
}

export function EjectaCard({ label, ejecta, rate }: { label: string; ejecta: number; rate?: number }) {
  const vei = getVEI(ejecta);

  return (
    <div className="rounded-lg border border-border bg-card p-4 space-y-3">
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium">{label}</span>
        <span className="text-xs text-muted-foreground">{ejecta.toLocaleString()} blocks ({formatVolume(ejecta)})</span>
      </div>

      <div className="space-y-1">
        <div className="flex items-center justify-between">
          <span className="text-xs text-muted-foreground">VEI</span>
          <span className="text-lg font-bold tabular-nums">{vei}</span>
        </div>
        <div className="flex gap-0.5">
          {Array.from({ length: 9 }, (_, i) => (
            <div
              key={i}
              className={`h-2 flex-1 rounded-sm transition-colors ${i <= vei ? VEI_COLORS[i] : 'bg-muted'}`}
            />
          ))}
        </div>
        <div className="flex justify-between text-[10px] text-muted-foreground">
          <span>0</span>
          <span>8</span>
        </div>
      </div>

      {rate != null && (
        <div className="text-xs text-muted-foreground">
          {rate.toLocaleString()} m³/s
        </div>
      )}
    </div>
  );
}
