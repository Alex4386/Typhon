import type { ConfigNode } from '@/transport/types';
import { Bomb } from 'lucide-react';
import { LiveVent, StatCard, CollapsibleConfigSection } from './shared';

export function BombsTab({ vent, configNodes, onSetConfig }: {
  vent: LiveVent;
  configNodes: ConfigNode[];
  onSetConfig: (key: string, value: string) => void;
}) {
  const bombNodes = configNodes.filter(n => n.key.startsWith('bombs:'));
  const explosionNodes = configNodes.filter(n => n.key.startsWith('explosion:'));

  const bombPct = vent.maxActiveBombs > 0 ? Math.min((vent.activeBombs / vent.maxActiveBombs) * 100, 100) : 0;

  return (
    <div className="space-y-4">
      {/* Live rate card + in-flight progress */}
      <div className="rounded-lg border border-border bg-card p-5 space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className={`size-10 rounded-full flex items-center justify-center ${vent.isExploding ? 'bg-red-500/20' : 'bg-muted'}`}>
              <Bomb className={`size-5 ${vent.isExploding ? 'text-red-400' : 'text-muted-foreground'}`} />
            </div>
            <div>
              <div className="font-semibold">{vent.isExploding ? 'Exploding' : 'Not Exploding'}</div>
              <div className="text-sm text-muted-foreground">{vent.bombsPerSecond} bombs/s</div>
            </div>
          </div>
          <div className="text-right">
            <div className="text-2xl font-bold tabular-nums">{vent.bombsPerSecond}</div>
            <div className="text-xs text-muted-foreground">bombs/s</div>
          </div>
        </div>

        {/* In-flight progress */}
        <div className="space-y-1">
          <div className="flex justify-between text-xs text-muted-foreground">
            <span>In flight: {vent.activeBombs.toLocaleString()}</span>
            <span>Max: {vent.maxActiveBombs.toLocaleString()}</span>
          </div>
          <div className="h-2 rounded-full bg-muted overflow-hidden">
            <div
              className={`h-full rounded-full transition-all ${
                bombPct > 80 ? 'bg-red-500' : bombPct > 50 ? 'bg-amber-500' : 'bg-red-400'
              }`}
              style={{ width: `${bombPct}%` }}
            />
          </div>
          {bombPct > 80 && (
            <div className="text-[10px] text-red-400">Near capacity — bombs landing instantly</div>
          )}
        </div>
      </div>

      {/* Key values at a glance */}
      {configNodes.length > 0 && (() => {
        const val = (key: string) => {
          const node = configNodes.find(n => n.key === key);
          return node ? String(node.value) : '--';
        };

        return (<>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <StatCard label="Power" value={`${val('bombs:explosionPower:min')} – ${val('bombs:explosionPower:max')}`} />
            <StatCard label="Radius" value={`${val('bombs:radius:min')} – ${val('bombs:radius:max')}`} />
            <StatCard label="Delay" value={`${val('bombs:delay')} ticks`} />
            <StatCard label="Max Distance" value={`${vent.bombMaxDistance.toFixed(1)}m`} />
          </div>

          {bombNodes.length > 0 && (
            <CollapsibleConfigSection title="Bomb Settings" nodes={bombNodes} onSetConfig={onSetConfig} />
          )}

          {explosionNodes.length > 0 && (
            <CollapsibleConfigSection title="Explosion Settings" nodes={explosionNodes} onSetConfig={onSetConfig} />
          )}
        </>);
      })()}
    </div>
  );
}
