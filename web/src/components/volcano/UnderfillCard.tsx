import { Layers } from 'lucide-react';

export function UnderfillCard({ underfillTargets, underfillLavaBlocks, activeLavaBlocks, successfulPlumbsPerSecond, plumbedBlocksPerSecond }: {
  underfillTargets: number;
  underfillLavaBlocks: number;
  activeLavaBlocks: number;
  successfulPlumbsPerSecond: number;
  plumbedBlocksPerSecond: number;
}) {
  const isActive = underfillTargets > 0 || underfillLavaBlocks > 0;
  const surfaceBlocks = activeLavaBlocks - underfillLavaBlocks;

  // What fraction of active lava is underfill
  const underfillPct = activeLavaBlocks > 0
    ? Math.min((underfillLavaBlocks / activeLavaBlocks) * 100, 100)
    : 0;

  // Plumbing: flowing vs dead-ended
  const deadEndPerSecond = plumbedBlocksPerSecond - successfulPlumbsPerSecond;
  const flowingPct = plumbedBlocksPerSecond > 0
    ? Math.min((successfulPlumbsPerSecond / plumbedBlocksPerSecond) * 100, 100)
    : 0;

  return (
    <div className="rounded-lg border border-border bg-card p-5 space-y-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className={`size-10 rounded-full flex items-center justify-center ${isActive ? 'bg-amber-500/20' : 'bg-muted'}`}>
            <Layers className={`size-5 ${isActive ? 'text-amber-400' : 'text-muted-foreground'}`} />
          </div>
          <div>
            <div className="font-semibold">{isActive ? 'Underfill Active' : 'No Underfill'}</div>
            <div className="text-sm text-muted-foreground">{underfillTargets.toLocaleString()} pending targets</div>
          </div>
        </div>
        <div className="text-right">
          <div className="text-2xl font-bold tabular-nums">{underfillLavaBlocks.toLocaleString()}</div>
          <div className="text-xs text-muted-foreground">active blocks</div>
        </div>
      </div>

      {/* Underfill vs Surface lava blocks */}
      <div className="space-y-1">
        <div className="flex justify-between text-xs text-muted-foreground">
          <span>Underfill: {underfillLavaBlocks.toLocaleString()}</span>
          <span>Surface: {surfaceBlocks.toLocaleString()}</span>
        </div>
        <div className="h-2 rounded-full bg-muted overflow-hidden flex">
          {activeLavaBlocks > 0 && (<>
            <div
              className="h-full bg-amber-500 transition-all"
              style={{ width: `${underfillPct}%` }}
            />
            <div
              className="h-full bg-orange-500/40 transition-all"
              style={{ width: `${100 - underfillPct}%` }}
            />
          </>)}
        </div>
        <div className="flex justify-between text-[10px] text-muted-foreground/60">
          <span>Underground</span>
          <span>Surface</span>
        </div>
      </div>

      {/* Plumbing: flowing vs dead-ended */}
      <div className="space-y-1">
        <div className="flex justify-between text-xs text-muted-foreground">
          <span>Flowing: {successfulPlumbsPerSecond}/s</span>
          <span>Dead end: {deadEndPerSecond}/s</span>
        </div>
        <div className="h-2 rounded-full bg-muted overflow-hidden flex">
          {plumbedBlocksPerSecond > 0 && (<>
            <div
              className="h-full bg-green-500 transition-all"
              style={{ width: `${flowingPct}%` }}
            />
            <div
              className="h-full bg-red-500/40 transition-all"
              style={{ width: `${100 - flowingPct}%` }}
            />
          </>)}
        </div>
        <div className="flex justify-between text-[10px] text-muted-foreground/60">
          <span>Flowed</span>
          <span>Dead end</span>
        </div>
      </div>
    </div>
  );
}
