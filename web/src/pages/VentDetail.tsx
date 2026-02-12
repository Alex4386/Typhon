import { useEffect, useMemo, useState, useCallback, useRef } from 'react';
import { useParams, Link } from 'react-router-dom';
import type { VentDetail as VentDetailType, VentMetrics, ConfigNode, BuilderData, VentRecordData } from '@/transport/types';
import { getApi } from '@/transport/api';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { TabsContent } from '@/components/ui/tabs';
import { WrappedTabsList } from '@/components/wrapped-tab-list';
import SmartTabs from '@/components/smart-tabs';
import IconTabsTrigger from '@/components/icon-tabs-trigger';
import { ArrowLeft, Flame, Bomb, Info, Settings, Zap, ChevronsUpDown, Play, Square, HardHat, ClipboardList } from 'lucide-react';
import { VolcanoCrossSectionRaw } from '@/components/volcano/VolcanoCrossSection';
import { Collapsible, CollapsibleTrigger, CollapsibleContent } from '@/components/ui/collapsible';
import { useSettings } from '@/hooks/useSettings';
import { VolcanoIcon } from '@/components/icons/VolcanoIcon';
import { OverviewTab, EruptionTab, LavaFlowTab, BombsTab, BuilderTab, OtherConfigTab, RecordTab, STATUS_COLORS } from './_tabs';

const METRICS_POLL_INTERVAL = 2000;

export default function VentDetail() {
  const { name, ventName } = useParams<{ name: string; ventName: string }>();
  const [vent, setVent] = useState<VentDetailType | null>(null);
  const [metrics, setMetrics] = useState<VentMetrics | null>(null);
  const [config, setConfig] = useState<ConfigNode[]>([]);
  const [builder, setBuilder] = useState<BuilderData | null>(null);
  const [record, setRecord] = useState<VentRecordData | null>(null);
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

  const fetchRecord = useCallback(() => {
    if (!name || !ventName) return;
    getApi().get<VentRecordData>(
      `/volcanoes/${encodeURIComponent(name)}/vents/${encodeURIComponent(ventName)}/record`
    ).then(res => {
      if (res.status === 200 && res.data) setRecord(res.data);
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
  useEffect(() => { fetchVent(); fetchConfig(); fetchMetrics(); fetchBuilder(); fetchRecord(); }, [fetchVent, fetchConfig, fetchMetrics, fetchBuilder, fetchRecord]);

  // Poll metrics + vent data (geometry changes during eruptions)
  useEffect(() => {
    metricsTimer.current = setInterval(() => { fetchMetrics(); fetchVent(); fetchRecord(); }, METRICS_POLL_INTERVAL);
    return () => clearInterval(metricsTimer.current);
  }, [fetchMetrics, fetchVent, fetchRecord]);

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
              <IconTabsTrigger value="record" icon={ClipboardList}>Record</IconTabsTrigger>
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

            <TabsContent value="record">
              <RecordTab record={record} />
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
