import type { ConfigNode } from '@/transport/types';
import { Zap, ChevronsUpDown } from 'lucide-react';
import { Collapsible, CollapsibleTrigger, CollapsibleContent } from '@/components/ui/collapsible';
import { LiveVent, STYLE_PROFILES, VENT_STATUSES, ActivityCards, EjectaCards, CollapsibleConfigSection } from './shared';

export function EruptionTab({ vent, configNodes, ventGeometryNodes, onSetConfig, onSetStatus }: {
  vent: LiveVent;
  configNodes: ConfigNode[];
  ventGeometryNodes: ConfigNode[];
  onSetConfig: (key: string, value: string) => void;
  onSetStatus: (status: string) => void;
}) {
  const styleLower = (vent.style || '').toLowerCase();
  const profile = STYLE_PROFILES[styleLower];
  const styleNode = configNodes.find(n => n.key === 'erupt:style');
  const ventTypeNode = ventGeometryNodes.find(n => n.key === 'vent:type');
  const otherGeometryNodes = ventGeometryNodes.filter(n => n.key !== 'vent:type');

  return (
    <div className="space-y-4">
      {/* Eruption status header */}
      <div className="rounded-lg border border-border bg-card p-5">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className={`size-10 rounded-full flex items-center justify-center ${vent.isErupting ? 'bg-red-500/20' : 'bg-muted'}`}>
              <Zap className={`size-5 ${vent.isErupting ? 'text-red-400' : 'text-muted-foreground'}`} />
            </div>
            <div>
              <div className="font-semibold">{vent.isErupting ? 'Erupting' : 'Not Erupting'}</div>
              <div className="text-sm text-muted-foreground">
                {vent.style?.toLowerCase().replace(/_/g, ' ')} &middot; {vent.status.replace(/_/g, ' ')}
              </div>
              {profile && (
                <p className="text-xs text-muted-foreground/70 mt-0.5">{profile.description}</p>
              )}
            </div>
          </div>
          <select
            value={vent.status}
            onChange={e => onSetStatus(e.target.value)}
            className="text-xs rounded-md border border-border bg-background px-2 py-1"
          >
            {VENT_STATUSES.map(s => (
              <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>
            ))}
          </select>
        </div>
      </div>

      <ActivityCards vent={vent} />
      <EjectaCards vent={vent} />

      {/* Eruption Settings — style & type selectors */}
      <Collapsible>
        <div className="rounded-lg border border-border bg-card">
          <CollapsibleTrigger className="flex w-full items-center justify-between p-4 text-sm font-medium hover:bg-muted/50 transition-colors rounded-lg">
            Eruption Settings
            <ChevronsUpDown className="size-4 text-muted-foreground" />
          </CollapsibleTrigger>
          <CollapsibleContent>
            <div className="px-5 pb-5 space-y-3">
              {styleNode && (
                <div className="flex items-center justify-between gap-4">
                  <span className="text-sm text-muted-foreground">Eruption Style</span>
                  <select
                    value={String(styleNode.value)}
                    onChange={e => onSetConfig('erupt:style', e.target.value)}
                    className="text-sm rounded-md border border-border bg-background px-3 py-1.5 capitalize"
                  >
                    {styleNode.options?.map(opt => (
                      <option key={opt} value={opt}>{opt.toLowerCase().replace(/_/g, ' ')}</option>
                    ))}
                  </select>
                </div>
              )}
              {ventTypeNode && (
                <div className="flex items-center justify-between gap-4">
                  <span className="text-sm text-muted-foreground">Vent Type</span>
                  <select
                    value={String(ventTypeNode.value)}
                    onChange={e => onSetConfig('vent:type', e.target.value)}
                    className="text-sm rounded-md border border-border bg-background px-3 py-1.5 capitalize"
                  >
                    {ventTypeNode.options?.map(opt => (
                      <option key={opt} value={opt}>{opt.toLowerCase()}</option>
                    ))}
                  </select>
                </div>
              )}
            </div>
          </CollapsibleContent>
        </div>
      </Collapsible>

      {otherGeometryNodes.length > 0 && (
        <CollapsibleConfigSection title="Vent Geometry" nodes={otherGeometryNodes} onSetConfig={onSetConfig} />
      )}
    </div>
  );
}
