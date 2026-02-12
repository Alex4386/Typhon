import { useEffect, useState } from 'react';
import type { BuilderData } from '@/transport/types';
import { HardHat, ChevronsUpDown, Check } from 'lucide-react';
import { Collapsible, CollapsibleTrigger, CollapsibleContent } from '@/components/ui/collapsible';
import { LiveVent, BUILDER_TYPES } from './shared';

export function BuilderTab({ vent, builder, onConfigure }: {
  vent: LiveVent;
  builder: BuilderData | null;
  onConfigure: (data: Partial<BuilderData & { args: Record<string, string> }>) => void;
}) {
  const isEnabled = builder?.enabled ?? false;
  const selectedType = builder?.type ?? '';

  const handleToggle = () => {
    onConfigure({ enabled: !isEnabled });
  };

  const handleTypeChange = (type: string) => {
    if (type === '') {
      onConfigure({ type: null, enabled: false });
    } else {
      onConfigure({ type, enabled: isEnabled });
    }
  };

  return (
    <div className="space-y-4">
      {/* Enable + type selection card */}
      <div className="rounded-lg border border-border bg-card p-5 space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className={`size-10 rounded-full flex items-center justify-center ${isEnabled ? 'bg-emerald-500/20' : 'bg-muted'}`}>
              <HardHat className={`size-5 ${isEnabled ? 'text-emerald-400' : 'text-muted-foreground'}`} />
            </div>
            <div>
              <div className="font-semibold">Builder</div>
              <div className="text-sm text-muted-foreground">
                Auto-stop eruption when a condition is met
              </div>
            </div>
          </div>
          <button
            onClick={handleToggle}
            disabled={!selectedType}
            className={`relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors ${isEnabled ? 'bg-emerald-500' : 'bg-muted'} ${!selectedType ? 'opacity-50 cursor-not-allowed' : ''}`}
          >
            <span className={`inline-block size-3.5 rounded-full bg-white transition-transform ${isEnabled ? 'translate-x-[18px]' : 'translate-x-0.5'}`} />
          </button>
        </div>

        {/* Type selector */}
        <div className="flex items-center justify-between gap-4">
          <span className="text-sm text-muted-foreground">Builder Type</span>
          <select
            value={selectedType}
            onChange={e => handleTypeChange(e.target.value)}
            className="text-sm rounded-md border border-border bg-background px-3 py-1.5"
          >
            <option value="">None</option>
            {BUILDER_TYPES.map(t => (
              <option key={t.value} value={t.value}>{t.label}</option>
            ))}
          </select>
        </div>
      </div>

      {/* Type-specific options */}
      {selectedType === 'y_threshold' && (
        <YThresholdOptions vent={vent} builder={builder} onConfigure={onConfigure} />
      )}

      {/* How it works */}
      <div className="rounded-lg border border-dashed border-border bg-muted/30 p-4 space-y-2">
        <span className="text-xs font-medium text-muted-foreground">How the Builder works</span>
        <ul className="text-xs text-muted-foreground/80 space-y-1 list-disc list-inside">
          <li>Select a builder type and configure its target</li>
          <li>Enable the builder — the eruption runs normally</li>
          <li>When the condition is met, the eruption automatically stops</li>
          <li>While active, auto-start and style changes are paused</li>
        </ul>
      </div>
    </div>
  );
}

function YThresholdOptions({ vent, builder, onConfigure }: {
  vent: LiveVent;
  builder: BuilderData | null;
  onConfigure: (data: Partial<BuilderData & { args: Record<string, string> }>) => void;
}) {
  const [targetY, setTargetY] = useState('');
  const currentY = vent.summitY ?? vent.baseY ?? 0;
  const base = vent.baseY ?? 0;
  const savedTarget = builder?.args?.y_threshold ?? '';

  useEffect(() => {
    if (savedTarget) setTargetY(savedTarget);
  }, [savedTarget]);

  const commit = () => {
    const y = Number(targetY);
    if (!isNaN(y)) {
      onConfigure({ args: { y_threshold: String(y) } });
    }
  };

  const target = Number(savedTarget || 0);
  const progressPct = target > base && currentY > base
    ? Math.min(((currentY - base) / (target - base)) * 100, 100)
    : 0;
  const dirty = targetY !== savedTarget;

  return (
    <>
      {/* Progress visualization */}
      {target > base && (
        <div className="rounded-lg border border-border bg-card p-5 space-y-3">
          <div className="flex items-center justify-between text-sm">
            <span className="text-muted-foreground">Summit Progress</span>
            <span className="font-bold tabular-nums">Y={currentY} / {target}</span>
          </div>

          <div className="relative">
            <div className="h-6 rounded-md bg-muted overflow-hidden">
              <div
                className={`h-full rounded-md transition-all ${progressPct >= 100 ? 'bg-emerald-500' : 'bg-emerald-500/70'}`}
                style={{ width: `${progressPct}%` }}
              />
            </div>
            <div className="absolute right-0 top-0 h-6 flex items-center pr-2">
              <span className="text-[10px] font-medium text-muted-foreground/70">
                {progressPct >= 100 ? 'Reached!' : `${progressPct.toFixed(0)}%`}
              </span>
            </div>
          </div>

          <div className="flex justify-between text-[10px] text-muted-foreground/60">
            <span>Base Y={base}</span>
            <span>Target Y={target}</span>
          </div>
        </div>
      )}

      {/* Options */}
      <Collapsible defaultOpen>
        <div className="rounded-lg border border-border bg-card">
          <CollapsibleTrigger className="flex w-full items-center justify-between p-4 text-sm font-medium hover:bg-muted/50 transition-colors rounded-lg">
            Y Threshold Options
            <ChevronsUpDown className="size-4 text-muted-foreground" />
          </CollapsibleTrigger>
          <CollapsibleContent>
            <div className="px-5 pb-5 space-y-3">
              <div className="flex items-center gap-2">
                <span className="text-sm text-muted-foreground shrink-0">Target Y</span>
                <input
                  type="number"
                  value={targetY}
                  onChange={e => setTargetY(e.target.value)}
                  onBlur={() => { if (dirty) commit(); }}
                  onKeyDown={e => { if (e.key === 'Enter') commit(); }}
                  placeholder={String(currentY + 50)}
                  className={`flex-1 text-sm rounded-md border bg-background px-2 py-1.5 text-right tabular-nums ${dirty ? 'border-primary' : 'border-border'}`}
                />
                {dirty && (
                  <button onClick={commit} className="size-7 flex items-center justify-center rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors shrink-0">
                    <Check className="size-3.5" />
                  </button>
                )}
              </div>
              <p className="text-xs text-muted-foreground/70">
                Current summit: Y={currentY} &middot; Eruption stops when summit &ge; target
              </p>
            </div>
          </CollapsibleContent>
        </div>
      </Collapsible>
    </>
  );
}
