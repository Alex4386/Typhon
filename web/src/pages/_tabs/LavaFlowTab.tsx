import type { ConfigNode } from '@/transport/types';
import { Flame, GitBranch } from 'lucide-react';
import { LiveVent, StatCard, CollapsibleConfigSection, SilicateSlider, GasContentSlider } from './shared';

export function LavaFlowTab({ vent, configNodes, onSetConfig }: {
  vent: LiveVent;
  configNodes: ConfigNode[];
  onSetConfig: (key: string, value: string) => void;
}) {
  const normalFlowPct = vent.longestNormalLavaFlowLength > 0
    ? Math.min((vent.currentNormalLavaFlowLength / vent.longestNormalLavaFlowLength) * 100, 100)
    : 0;

  const totalFlowPct = vent.longestFlowLength > 0
    ? Math.min((vent.currentFlowLength / vent.longestFlowLength) * 100, 100)
    : 0;

  const frontPct = vent.activeLavaBlocks > 0
    ? Math.min((vent.terminalLavaBlocks / vent.activeLavaBlocks) * 100, 100)
    : 0;

  const totalEndpoints = vent.normalFlowEndBlocks + vent.pillowFlowEndBlocks;
  const endpointNormalPct = totalEndpoints > 0 ? (vent.normalFlowEndBlocks / totalEndpoints) * 100 : 50;
  const isPlumbing = vent.plumbedBlocksPerSecond > 0;
  const plumbSuccessPct = vent.plumbedBlocksPerSecond > 0
    ? Math.min((vent.successfulPlumbsPerSecond / vent.plumbedBlocksPerSecond) * 100, 100)
    : 0;

  const silicateNode = configNodes.find(n => n.key === 'lavaflow:silicateLevel');
  const gasNode = configNodes.find(n => n.key === 'lavaflow:gasContent');
  const otherNodes = configNodes.filter(n => n.key !== 'lavaflow:silicateLevel' && n.key !== 'lavaflow:gasContent');

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
      {/* Live rate card */}
      <div className="rounded-lg border border-border bg-card p-5 space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className={`size-10 rounded-full flex items-center justify-center ${vent.isFlowingLava ? 'bg-orange-500/20' : 'bg-muted'}`}>
              <Flame className={`size-5 ${vent.isFlowingLava ? 'text-orange-400' : 'text-muted-foreground'}`} />
            </div>
            <div>
              <div className="font-semibold">{vent.isFlowingLava ? 'Flowing' : 'Not Flowing'}</div>
              <div className="text-sm text-muted-foreground">{vent.lavaFlowsPerSecond} blocks/s from vent</div>
            </div>
          </div>
          <div className="text-right">
            <div className="text-2xl font-bold tabular-nums">{vent.lavaFlowsPerSecond}</div>
            <div className="text-xs text-muted-foreground">flows/s</div>
          </div>
        </div>

        {/* Normal lava flow (Material.LAVA) */}
        <div className="space-y-1">
          <div className="flex justify-between text-xs text-muted-foreground">
            <span>Current Flow: {vent.currentNormalLavaFlowLength.toFixed(1)}m</span>
            <span>Longest: {vent.longestNormalLavaFlowLength.toFixed(1)}m</span>
          </div>
          <div className="h-2 rounded-full bg-muted overflow-hidden">
            <div
              className={`h-full rounded-full transition-all ${vent.isFlowingLava ? 'bg-orange-500' : 'bg-muted-foreground/30'}`}
              style={{ width: `${normalFlowPct}%` }}
            />
          </div>
        </div>

        {/* Total flow including cooled (MAGMA_BLOCK) */}
        <div className="space-y-1">
          <div className="flex justify-between text-xs text-muted-foreground">
            <span>All Current Flows: {vent.currentFlowLength.toFixed(1)}m</span>
            <span>Longest: {vent.longestFlowLength.toFixed(1)}m</span>
          </div>
          <div className="h-2 rounded-full bg-muted overflow-hidden">
            <div
              className={`h-full rounded-full transition-all ${vent.isFlowingLava ? 'bg-red-700' : 'bg-muted-foreground/30'}`}
              style={{ width: `${totalFlowPct}%` }}
            />
          </div>
        </div>

        {/* Active lava — stacked bar: flow front vs total */}
        <div className="space-y-1">
          <div className="flex justify-between text-xs text-muted-foreground">
            <span>{vent.activeLavaBlocks.toLocaleString()} active blocks</span>
            <span>{vent.terminalLavaBlocks.toLocaleString()} flow front</span>
          </div>
          <div className="h-2 rounded-full bg-muted overflow-hidden flex">
            {vent.activeLavaBlocks > 0 && (<>
              <div
                className="h-full bg-orange-500/60 transition-all"
                style={{ width: `${100 - frontPct}%` }}
              />
              <div
                className="h-full bg-yellow-500 transition-all"
                style={{ width: `${frontPct}%` }}
              />
            </>)}
          </div>
          <div className="flex justify-between text-[10px] text-muted-foreground/60">
            <span>Cooling</span>
            <span>Flow front</span>
          </div>
        </div>
      </div>

      {/* Plumbing stats */}
      <div className="rounded-lg border border-border bg-card p-5 space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className={`size-10 rounded-full flex items-center justify-center ${isPlumbing ? 'bg-blue-500/20' : 'bg-muted'}`}>
              <GitBranch className={`size-5 ${isPlumbing ? 'text-blue-400' : 'text-muted-foreground'}`} />
            </div>
            <div>
              <div className="font-semibold">{isPlumbing ? 'Plumbing Active' : 'No Plumbing'}</div>
              <div className="text-sm text-muted-foreground">{totalEndpoints.toLocaleString()} endpoints</div>
            </div>
          </div>
          <div className="text-right">
            <div className="text-2xl font-bold tabular-nums">{vent.plumbedBlocksPerSecond}</div>
            <div className="text-xs text-muted-foreground">blk/s</div>
          </div>
        </div>

        {/* Plumbing success rate */}
        <div className="space-y-1">
          <div className="flex justify-between text-xs text-muted-foreground">
            <span>Flowing: {vent.successfulPlumbsPerSecond}/s</span>
            <span>{plumbSuccessPct.toFixed(0)}% success</span>
          </div>
          <div className="h-2 rounded-full bg-muted overflow-hidden flex">
            {isPlumbing && (<>
              <div
                className="h-full bg-green-500 transition-all"
                style={{ width: `${plumbSuccessPct}%` }}
              />
              <div
                className="h-full bg-red-500/40 transition-all"
                style={{ width: `${100 - plumbSuccessPct}%` }}
              />
            </>)}
          </div>
          <div className="flex justify-between text-[10px] text-muted-foreground/60">
            <span>Flowed</span>
            <span>Dead end</span>
          </div>
        </div>

        {/* Normal vs Pillow endpoints */}
        <div className="space-y-1">
          <div className="flex justify-between text-xs text-muted-foreground">
            <span>Normal: {vent.normalFlowEndBlocks.toLocaleString()}</span>
            <span>Pillow: {vent.pillowFlowEndBlocks.toLocaleString()}</span>
          </div>
          <div className="h-2 rounded-full bg-muted overflow-hidden flex">
            {totalEndpoints > 0 && (<>
              <div
                className="h-full bg-orange-500 transition-all"
                style={{ width: `${endpointNormalPct}%` }}
              />
              <div
                className="h-full bg-blue-500 transition-all"
                style={{ width: `${100 - endpointNormalPct}%` }}
              />
            </>)}
          </div>
          <div className="flex justify-between text-[10px] text-muted-foreground/60">
            <span>Normal</span>
            <span>Pillow</span>
          </div>
        </div>

        {/* Underfill targets */}
        {vent.underfillTargets > 0 && (
          <div className="flex items-center justify-between text-sm">
            <span className="text-muted-foreground">Underfill Targets</span>
            <span className="font-bold tabular-nums">{vent.underfillTargets.toLocaleString()}</span>
          </div>
        )}
      </div>
      </div>

      {/* Silicate & Gas sliders */}
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        {silicateNode && (
          <SilicateSlider node={silicateNode} onChange={v => onSetConfig('lavaflow:silicateLevel', v)} />
        )}
        {gasNode && (
          <GasContentSlider node={gasNode} onChange={v => onSetConfig('lavaflow:gasContent', v)} />
        )}
      </div>

      <div className="grid grid-cols-2 gap-3">
        <StatCard label="Lava Flow" value={`${vent.currentNormalLavaFlowLength.toFixed(1)}m`} />
        <StatCard label="Longest Lava" value={`${vent.longestNormalLavaFlowLength.toFixed(1)}m`} />
        <StatCard label="Total Flow" value={`${vent.currentFlowLength.toFixed(1)}m`} />
        <StatCard label="Longest Total" value={`${vent.longestFlowLength.toFixed(1)}m`} />
      </div>

      {otherNodes.length > 0 && (
        <CollapsibleConfigSection title="Lava Flow Settings" nodes={otherNodes} onSetConfig={onSetConfig} />
      )}
    </div>
  );
}
