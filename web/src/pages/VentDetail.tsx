import { useEffect, useMemo, useState, useCallback, useRef } from 'react';
import { useParams, Link } from 'react-router-dom';
import type { VentDetail as VentDetailType, VentMetrics, LocationData, ConfigNode, BuilderData } from '@/transport/types';
import { getApi } from '@/transport/api';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { TabsContent } from '@/components/ui/tabs';
import { WrappedTabsList } from '@/components/wrapped-tab-list';
import SmartTabs from '@/components/smart-tabs';
import IconTabsTrigger from '@/components/icon-tabs-trigger';
import { ArrowLeft, Flame, Bomb, Wind, Info, Settings, Zap, ChevronsUpDown, Mountain, Play, Square, Check, HardHat } from 'lucide-react';
import { VolcanoCrossSectionRaw } from '@/components/volcano/VolcanoCrossSection';
import { Collapsible, CollapsibleTrigger, CollapsibleContent } from '@/components/ui/collapsible';
import { useSettings } from '@/hooks/useSettings';
import { VolcanoIcon } from '@/components/icons/VolcanoIcon';
import { LocationCard, buildBlueMapUrl } from '@/components/volcano/LocationCard';
import { EjectaCard } from '@/components/volcano/EjectaCard';

const STATUS_COLORS: Record<string, string> = {
  ERUPTING: 'bg-red-500',
  ERUPTION_IMMINENT: 'bg-orange-500',
  MAJOR_ACTIVITY: 'bg-amber-500',
  MINOR_ACTIVITY: 'bg-yellow-500',
  DORMANT: 'bg-blue-400',
  EXTINCT: 'bg-gray-400',
};


const STYLE_PROFILES: Record<string, { description: string; caldera: boolean }> = {
  hawaiian:    { description: 'Effusive lava fountains, low explosivity', caldera: false },
  strombolian: { description: 'Lava fountains with volcanic bombs', caldera: false },
  vulcanian:   { description: 'Moderate explosions with extended ash', caldera: false },
  pelean:      { description: 'Lava dome collapse, pyroclastic flows', caldera: false },
  plinian:     { description: 'Massive ash column, caldera collapse', caldera: true },
  lava_dome:   { description: 'Slow dome-building eruption', caldera: false },
};

// Rock classification zones based on VolcanoComposition.java silicate boundaries
const SILICATE_ZONES = [
  { min: 0.30, max: 0.45, label: 'Ultramafic', short: 'UMF', color: '#1c1917', labelColor: '#a8a29e' },
  { min: 0.45, max: 0.53, label: 'Basalt',     short: 'BAS', color: '#44403c', labelColor: '#d6d3d1' },
  { min: 0.53, max: 0.63, label: 'Andesite',   short: 'AND', color: '#6b6560', labelColor: '#fafaf9' },
  { min: 0.63, max: 0.77, label: 'Dacite',     short: 'DAC', color: '#a8a29e', labelColor: '#1c1917' },
  { min: 0.77, max: 0.90, label: 'Rhyolite',   short: 'RHY', color: '#d6d3d1', labelColor: '#292524' },
];

function getSilicateClassification(value: number): string {
  for (const zone of SILICATE_ZONES) {
    if (value < zone.max) return zone.label;
  }
  return SILICATE_ZONES[SILICATE_ZONES.length - 1].label;
}

// Gas content zones — affects bomb count multiplier (VolcanoExplosion.java)
const GAS_ZONES = [
  { min: 0.0, max: 0.2, label: 'Effusive',      short: 'EFF', color: '#1e3a5f', labelColor: '#93c5fd' },
  { min: 0.2, max: 0.5, label: 'Mild',           short: 'MLD', color: '#3b4f6b', labelColor: '#bfdbfe' },
  { min: 0.5, max: 0.8, label: 'Explosive',      short: 'EXP', color: '#7c3a2e', labelColor: '#fecaca' },
  { min: 0.8, max: 1.0, label: 'Highly Explosive', short: 'H-EXP', color: '#991b1b', labelColor: '#fef2f2' },
];

function getGasClassification(value: number): string {
  for (const zone of GAS_ZONES) {
    if (value < zone.max) return zone.label;
  }
  return GAS_ZONES[GAS_ZONES.length - 1].label;
}


function fmtBlock(loc: LocationData | undefined | null): string {
  if (!loc) return '--';
  return `${loc.x}, ${loc.y}, ${loc.z}`;
}

function nodeLabel(key: string): string {
  const withoutPrefix = key.replace(/^[^:]+:/, '');
  return withoutPrefix
    .replace(/:/g, ' ')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .split(' ')
    .map(w => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

const METRICS_POLL_INTERVAL = 2000;

export default function VentDetail() {
  const { name, ventName } = useParams<{ name: string; ventName: string }>();
  const [vent, setVent] = useState<VentDetailType | null>(null);
  const [metrics, setMetrics] = useState<VentMetrics | null>(null);
  const [config, setConfig] = useState<ConfigNode[]>([]);
  const [builder, setBuilder] = useState<BuilderData | null>(null);
  const [error, setError] = useState('');
  const settings = useSettings();
  const metricsTimer = useRef<ReturnType<typeof setInterval>>(undefined);

  const fetchVent = useCallback(() => {
    if (!name || !ventName) return;
    getApi().get<VentDetailType>(
      `/volcanoes/${encodeURIComponent(name)}/vents/${encodeURIComponent(ventName)}`
    ).then(res => {
      if (res.status === 200 && res.data) setVent(res.data);
      else setError('Vent not found');
    }).catch(e => setError((e as Error).message));
  }, [name, ventName]);

  const fetchConfig = useCallback(() => {
    if (!name || !ventName) return;
    getApi().get<ConfigNode[]>(
      `/volcanoes/${encodeURIComponent(name)}/vents/${encodeURIComponent(ventName)}/config`
    ).then(res => {
      if (res.status === 200 && res.data) setConfig(res.data);
    }).catch(() => {});
  }, [name, ventName]);

  const fetchMetrics = useCallback(() => {
    if (!name || !ventName) return;
    getApi().get<VentMetrics>(
      `/volcanoes/${encodeURIComponent(name)}/vents/${encodeURIComponent(ventName)}/metrics`
    ).then(res => {
      if (res.status === 200 && res.data) setMetrics(res.data);
    }).catch(() => {});
  }, [name, ventName]);

  const fetchBuilder = useCallback(() => {
    if (!name || !ventName) return;
    getApi().get<BuilderData>(
      `/volcanoes/${encodeURIComponent(name)}/vents/${encodeURIComponent(ventName)}/builder`
    ).then(res => {
      if (res.status === 200 && res.data) setBuilder(res.data);
    }).catch(() => {});
  }, [name, ventName]);

  const configureBuilder = useCallback(async (data: Partial<BuilderData & { args: Record<string, string> }>) => {
    if (!name || !ventName) return;
    await getApi().post(
      `/volcanoes/${encodeURIComponent(name)}/vents/${encodeURIComponent(ventName)}/builder`,
      data
    );
    fetchBuilder();
  }, [name, ventName, fetchBuilder]);

  const ventAction = useCallback(async (action: 'start' | 'stop') => {
    if (!name || !ventName) return;
    await getApi().post(
      `/volcanoes/${encodeURIComponent(name)}/vents/${encodeURIComponent(ventName)}/${action}`
    );
    fetchVent();
    fetchMetrics();
  }, [name, ventName, fetchVent, fetchMetrics]);

  // Initial load
  useEffect(() => { fetchVent(); fetchConfig(); fetchMetrics(); fetchBuilder(); }, [fetchVent, fetchConfig, fetchMetrics, fetchBuilder]);

  // Poll metrics + vent data (geometry changes during eruptions)
  useEffect(() => {
    metricsTimer.current = setInterval(() => { fetchMetrics(); fetchVent(); }, METRICS_POLL_INTERVAL);
    return () => clearInterval(metricsTimer.current);
  }, [fetchMetrics, fetchVent]);

  // Merge static vent data with live metrics
  const live = useMemo((): (VentDetailType & VentMetrics) | null => {
    if (!vent) return null;
    return {
      ...vent,
      ...(metrics ?? {}),
      // metrics-only fields default to 0 before first poll
      lavaFlowsPerSecond: metrics?.lavaFlowsPerSecond ?? 0,
      activeLavaBlocks: metrics?.activeLavaBlocks ?? 0,
      terminalLavaBlocks: metrics?.terminalLavaBlocks ?? 0,
      bombsPerSecond: metrics?.bombsPerSecond ?? 0,
      activeBombs: metrics?.activeBombs ?? 0,
      maxActiveBombs: metrics?.maxActiveBombs ?? 0,
    } as VentDetailType & VentMetrics;
  }, [vent, metrics]);

  const setVentStatus = useCallback(async (status: string) => {
    if (!name || !ventName) return;
    await getApi().post(
      `/volcanoes/${encodeURIComponent(name)}/vents/${encodeURIComponent(ventName)}/status`,
      { status }
    );
    fetchVent();
    fetchMetrics();
  }, [name, ventName, fetchVent, fetchMetrics]);

  const setConfigNode = useCallback(async (key: string, value: string) => {
    if (!name || !ventName) return;
    const res = await getApi().patch<ConfigNode[]>(
      `/volcanoes/${encodeURIComponent(name)}/vents/${encodeURIComponent(ventName)}/config`,
      { [key]: value }
    );
    if (res.status === 200 && res.data) {
      setConfig(res.data);
      if (key === 'erupt:style') fetchVent();
    }
  }, [name, ventName, fetchVent]);

  const configGroups = useMemo(() => {
    const groups: Record<string, ConfigNode[]> = {};
    for (const node of config) {
      const prefix = node.key.split(':')[0];
      if (!groups[prefix]) groups[prefix] = [];
      groups[prefix].push(node);
    }
    return groups;
  }, [config]);

  const blueMapBaseUrl = settings?.blueMap?.publicUrl ?? null;

  return (
    <div className="p-6 space-y-6">
      <div>
        <Link to={`/volcanoes/${encodeURIComponent(name!)}`}>
          <Button variant="ghost" size="sm" className="gap-1 -ml-2 mb-2 text-muted-foreground">
            <ArrowLeft className="size-4" /> {name}
          </Button>
        </Link>

        {error ? (
          <div className="rounded-lg border border-destructive bg-destructive/10 p-4 text-sm text-destructive">
            {error}
          </div>
        ) : !vent ? (
          <p className="text-muted-foreground">Loading...</p>
        ) : (
          <>
            <div className="flex items-center gap-3 mb-1">
              <h1 className="text-2xl font-bold">{vent.name}</h1>
              <Badge variant="secondary" className="gap-1">
                <span className={`inline-block size-2 rounded-full ${STATUS_COLORS[vent.status] || 'bg-gray-400'}`} />
                {vent.status.replace(/_/g, ' ')}
              </Badge>
              {!vent.enabled && <Badge variant="outline">Disabled</Badge>}
              <div className="flex items-center gap-1 ml-auto">
                {live?.isErupting ? (
                  <Button variant="destructive" size="sm" className="gap-1.5" onClick={() => ventAction('stop')}>
                    <Square className="size-3.5" /> Stop
                  </Button>
                ) : (
                  <Button variant="default" size="sm" className="gap-1.5" onClick={() => ventAction('start')}>
                    <Play className="size-3.5" /> Erupt
                  </Button>
                )}
              </div>
            </div>
            <p className="text-sm text-muted-foreground">{vent.type} &middot; {vent.style}</p>
          </>
        )}
      </div>

      {live && (
        <>
          {live.summitBlock != null && (
            <Collapsible defaultOpen>
              <div className="rounded-lg border border-border bg-card">
                <CollapsibleTrigger className="flex w-full items-center justify-between p-4 text-sm font-medium hover:bg-muted/50 transition-colors rounded-lg">
                  <div className="flex items-center gap-2">
                    <VolcanoIcon className="size-4" />
                    Cross Section — {live.name}
                  </div>
                  <ChevronsUpDown className="size-4 text-muted-foreground" />
                </CollapsibleTrigger>
                <CollapsibleContent>
                  <div className="px-4 pb-4">
                    <VolcanoCrossSectionRaw vent={live} />
                  </div>
                </CollapsibleContent>
              </div>
            </Collapsible>
          )}

          <SmartTabs defaultValue="overview">
            <WrappedTabsList>
              <IconTabsTrigger value="overview" icon={Info}>Overview</IconTabsTrigger>
              <IconTabsTrigger value="eruption" icon={Zap}>Eruption</IconTabsTrigger>
              <IconTabsTrigger value="lavaflow" icon={Flame}>Lava Flow</IconTabsTrigger>
              <IconTabsTrigger value="bombs" icon={Bomb}>Bombs</IconTabsTrigger>
              <IconTabsTrigger value="builder" icon={HardHat}>Builder</IconTabsTrigger>
              <IconTabsTrigger value="config" icon={Settings}>Config</IconTabsTrigger>
            </WrappedTabsList>

            <TabsContent value="overview">
              <OverviewTab vent={live} blueMapBaseUrl={blueMapBaseUrl} />
            </TabsContent>

            <TabsContent value="eruption">
              <EruptionTab vent={live} configNodes={configGroups['erupt'] || []} ventGeometryNodes={configGroups['vent'] || []} onSetConfig={setConfigNode} onSetStatus={setVentStatus} />
            </TabsContent>

            <TabsContent value="lavaflow">
              <LavaFlowTab vent={live} configNodes={configGroups['lavaflow'] || []} onSetConfig={setConfigNode} />
            </TabsContent>

            <TabsContent value="bombs">
              <BombsTab vent={live} configNodes={[...(configGroups['bombs'] || []), ...(configGroups['explosion'] || [])]} onSetConfig={setConfigNode} />
            </TabsContent>

            <TabsContent value="builder">
              <BuilderTab vent={live} builder={builder} onConfigure={configureBuilder} />
            </TabsContent>

            <TabsContent value="config">
              <OtherConfigTab
                groups={configGroups}
                onSetConfig={setConfigNode}
              />
            </TabsContent>
          </SmartTabs>
        </>
      )}
    </div>
  );
}

type LiveVent = VentDetailType & VentMetrics;

/* ── Overview Tab ─────────────────────────────────────────────────────────── */

function OverviewTab({ vent, blueMapBaseUrl }: {
  vent: LiveVent;
  blueMapBaseUrl: string | null;
}) {
  return (
    <div className="space-y-4">
      <ActivityCards vent={vent} />
      <EjectaCards vent={vent} />

      <div className="grid grid-cols-2 gap-3">
        {vent.location && (
          <LocationCard label="Location" location={vent.location} blueMapUrl={blueMapBaseUrl ? buildBlueMapUrl(blueMapBaseUrl, vent.location) : null} />
        )}
        {vent.summitBlock && (
          <LocationCard label="Summit" icon={Mountain} location={vent.summitBlock} blueMapUrl={blueMapBaseUrl ? buildBlueMapUrl(blueMapBaseUrl, vent.summitBlock) : null} />
        )}
      </div>
      <div className="grid grid-cols-2 gap-3">
        <StatCard label="Crater Radius" value={`${vent.craterRadius}`} />
        <StatCard label="Caldera" value={vent.isCaldera ? `r=${vent.calderaRadius}` : 'None'} />
      </div>
    </div>
  );
}

/* ── Shared Activity & Ejecta sections ────────────────────────────────────── */

function ActivityCards({ vent }: { vent: LiveVent }) {
  return (
    <div className="grid gap-3 sm:grid-cols-3">
      <ActivityCard
        icon={Flame} label="Lava" active={vent.isFlowingLava} color="text-orange-400"
        current={vent.currentNormalLavaFlowLength} max={vent.longestFlowLength} unit="m"
      />
      <ActivityCard
        icon={Bomb} label="Bombs" active={vent.isExploding} color="text-red-400"
        current={vent.bombMaxDistance} max={vent.bombMaxDistance} unit="m"
      />
      <ActivityCard
        icon={Wind} label="Ash" active={(vent.currentAshFlowLength ?? 0) > 0} color="text-gray-400"
        current={vent.currentAshFlowLength} max={vent.longestAshFlowLength} unit="m"
      />
    </div>
  );
}

function EjectaCards({ vent }: { vent: LiveVent }) {
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <EjectaCard label="Current Eruption" ejecta={vent.currentEjecta} rate={vent.ejectaPerSecond} />
      <EjectaCard label="Lifetime Total" ejecta={vent.totalEjecta} />
    </div>
  );
}

/* ── Eruption Tab ─────────────────────────────────────────────────────────── */

const VENT_STATUSES = ['EXTINCT', 'DORMANT', 'MINOR_ACTIVITY', 'MAJOR_ACTIVITY', 'ERUPTION_IMMINENT', 'ERUPTING'] as const;

function EruptionTab({ vent, configNodes, ventGeometryNodes, onSetConfig, onSetStatus }: {
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

/* ── Builder Tab ──────────────────────────────────────────────────────────── */

const BUILDER_TYPES = [
  { value: 'y_threshold', label: 'Y Threshold', description: 'Stop eruption when summit reaches a target Y height' },
] as const;

function BuilderTab({ vent, builder, onConfigure }: {
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

/* ── Y Threshold Builder Options ─────────────────────────────────────────── */

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

/* ── Lava Flow Tab ───────────────────────────────────────────────────────── */

function LavaFlowTab({ vent, configNodes, onSetConfig }: {
  vent: LiveVent;
  configNodes: ConfigNode[];
  onSetConfig: (key: string, value: string) => void;
}) {
  const flowPct = vent.longestFlowLength > 0
    ? Math.min((vent.currentNormalLavaFlowLength / vent.longestFlowLength) * 100, 100)
    : 0;

  const frontPct = vent.activeLavaBlocks > 0
    ? (vent.terminalLavaBlocks / vent.activeLavaBlocks) * 100
    : 0;

  const silicateNode = configNodes.find(n => n.key === 'lavaflow:silicateLevel');
  const gasNode = configNodes.find(n => n.key === 'lavaflow:gasContent');
  const otherNodes = configNodes.filter(n => n.key !== 'lavaflow:silicateLevel' && n.key !== 'lavaflow:gasContent');

  const silicate = silicateNode ? Number(silicateNode.value) : (vent.silicateLevel ?? 0);
  const gas = gasNode ? Number(gasNode.value) : 0;

  return (
    <div className="space-y-4">
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

        {/* Flow length progress */}
        <div className="space-y-1">
          <div className="flex justify-between text-xs text-muted-foreground">
            <span>Current: {vent.currentNormalLavaFlowLength.toFixed(1)}m</span>
            <span>Longest: {vent.longestFlowLength.toFixed(1)}m</span>
          </div>
          <div className="h-2 rounded-full bg-muted overflow-hidden">
            <div
              className={`h-full rounded-full transition-all ${vent.isFlowingLava ? 'bg-orange-500' : 'bg-muted-foreground/30'}`}
              style={{ width: `${flowPct}%` }}
            />
          </div>
        </div>

        {/* Active lava — stacked bar: flow front vs total */}
        <div className="space-y-1">
          <div className="flex justify-between text-xs text-muted-foreground">
            <span>{vent.activeLavaBlocks.toLocaleString()} active blocks</span>
            <span>{vent.terminalLavaBlocks.toLocaleString()} flow front ({frontPct.toFixed(0)}%)</span>
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
        <StatCard label="Current Flow" value={`${vent.currentNormalLavaFlowLength.toFixed(1)}m`} />
        <StatCard label="Longest Flow" value={`${vent.longestFlowLength.toFixed(1)}m`} />
      </div>

      {otherNodes.length > 0 && (
        <CollapsibleConfigSection title="Lava Flow Settings" nodes={otherNodes} onSetConfig={onSetConfig} />
      )}
    </div>
  );
}

/* ── Bombs Tab ───────────────────────────────────────────────────────────── */

function BombsTab({ vent, configNodes, onSetConfig }: {
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

/* ── Other Config Tab (vent, succession, ash) ────────────────────────────── */

function OtherConfigTab({ groups, onSetConfig }: {
  groups: Record<string, ConfigNode[]>;
  onSetConfig: (key: string, value: string) => void;
}) {
  const sections = [
    { key: 'succession', title: 'Succession' },
    { key: 'ash', title: 'Ash / Pyroclastic' },
  ];

  return (
    <div className="space-y-4">
      {sections.map(({ key, title }) =>
        groups[key] && groups[key].length > 0 ? (
          <ConfigSection key={key} title={title} nodes={groups[key]} onSetConfig={onSetConfig} />
        ) : null
      )}
    </div>
  );
}

/* ── Config Section ──────────────────────────────────────────────────────── */

function ConfigSection({ title, nodes, onSetConfig }: {
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

/* ── Collapsible Config Section ──────────────────────────────────────────── */

function CollapsibleConfigSection({ title, nodes, onSetConfig }: {
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

/* ── Config Node Field ───────────────────────────────────────────────────── */

function ConfigNodeField({ node, onChange }: { node: ConfigNode; onChange: (value: string) => void }) {
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

  // Number types: int, float, double
  return <NumberNodeField node={node} label={label} onChange={onChange} />;
}

function NumberNodeField({ node, label, onChange }: { node: ConfigNode; label: string; onChange: (v: string) => void }) {
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

function StringNodeField({ node, label, onChange }: { node: ConfigNode; label: string; onChange: (v: string) => void }) {
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

/* ── Activity Card ────────────────────────────────────────────────────────── */

function ActivityCard({
  icon: Icon, label, active, color, current, max, unit,
}: {
  icon: React.ElementType;
  label: string;
  active: boolean;
  color: string;
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
            className={`h-full rounded-full transition-all ${active ? color.replace('text-', 'bg-') : 'bg-muted-foreground/30'}`}
            style={{ width: `${pct}%` }}
          />
        </div>
      </div>
    </div>
  );
}


/* ── Silicate Slider with rock classification ────────────────────────────── */

function SilicateSlider({ node, onChange }: { node: ConfigNode; onChange: (v: string) => void }) {
  const min = node.min ?? 0.3;
  const max = node.max ?? 0.9;
  const [local, setLocal] = useState(Number(node.value));

  useEffect(() => { setLocal(Number(node.value)); }, [node.value]);

  const commit = () => onChange(String(local));
  const classification = getSilicateClassification(local);
  const pct = max > min ? ((local - min) / (max - min)) * 100 : 0;

  return (
    <div className="rounded-lg border border-border bg-card p-4 space-y-3">
      {/* Header */}
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

      {/* Classification bar with zones */}
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

        {/* Current position indicator */}
        <div
          className="absolute top-0 h-7 pointer-events-none"
          style={{ left: `calc(${pct}% - 1px)` }}
        >
          <div className="w-0.5 h-full bg-white shadow-[0_0_4px_rgba(255,255,255,0.6)]" />
        </div>
      </div>

      {/* Range slider */}
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

      {/* Scale */}
      <div className="flex justify-between text-[10px] text-muted-foreground/60">
        <span>{(min * 100).toFixed(0)}%</span>
        <span>{(max * 100).toFixed(0)}%</span>
      </div>
    </div>
  );
}

/* ── Gas Content Slider with explosivity classification ───────────────────── */

function GasContentSlider({ node, onChange }: { node: ConfigNode; onChange: (v: string) => void }) {
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

/* ── Slider Card ──────────────────────────────────────────────────────────── */

function SliderCard({ label, node, displayValue, onChange, colorFrom, colorTo }: {
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

/* ── Stat Card ────────────────────────────────────────────────────────────── */

function StatCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-border bg-card p-3">
      <div className="text-xs text-muted-foreground uppercase tracking-wider mb-1">{label}</div>
      <div className="text-sm font-medium">{value}</div>
    </div>
  );
}

function EditableStatCard({ label, node, onChange }: { label: string; node: ConfigNode; onChange: (v: string) => void }) {
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

