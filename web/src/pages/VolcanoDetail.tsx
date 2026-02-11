import { useEffect, useMemo, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import type { VolcanoDetail as VolcanoDetailType, VentDetail, VentSummary } from '@/transport/types';
import { getApi } from '@/transport/api';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { TabsContent } from '@/components/ui/tabs';
import { WrappedTabsList } from '@/components/wrapped-tab-list';
import {
  Table, TableHeader, TableBody, TableRow, TableHead, TableCell,
} from '@/components/ui/table';
import SmartTabs from '@/components/smart-tabs';
import IconTabsTrigger from '@/components/icon-tabs-trigger';
import { ArrowLeft, Info } from 'lucide-react';
import { VolcanoIcon } from '@/components/icons/VolcanoIcon';
import VolcanoCrossSection from '@/components/volcano/VolcanoCrossSection';
import { LocationCard, buildBlueMapUrl } from '@/components/volcano/LocationCard';
import { useSettings } from '@/hooks/useSettings';

const STATUS_COLORS: Record<string, string> = {
  ERUPTING: 'bg-red-500',
  ERUPTION_IMMINENT: 'bg-orange-500',
  MAJOR_ACTIVITY: 'bg-amber-500',
  MINOR_ACTIVITY: 'bg-yellow-500',
  DORMANT: 'bg-blue-400',
  EXTINCT: 'bg-gray-400',
};

// Higher = more active
const STATUS_SEVERITY: Record<string, number> = {
  EXTINCT: 0,
  DORMANT: 1,
  MINOR_ACTIVITY: 2,
  MAJOR_ACTIVITY: 3,
  ERUPTION_IMMINENT: 4,
  ERUPTING: 5,
};

function getHighestStatusVent(vents: VentDetail[]): VentDetail | undefined {
  return vents.reduce<VentDetail | undefined>((best, v) => {
    if (!best) return v;
    return (STATUS_SEVERITY[v.status] ?? 0) > (STATUS_SEVERITY[best.status] ?? 0) ? v : best;
  }, undefined);
}

function getTallestVent(vents: VentDetail[]): VentDetail | undefined {
  const withSummit = vents.filter((v) => v.summitBlock != null);
  if (withSummit.length === 0) return undefined;
  return withSummit.reduce((best, v) => (v.summitY > best.summitY ? v : best));
}

function getMainVent(vents: VentDetail[]): VentDetail | undefined {
  return vents.find((v) => v.name === 'main') ?? vents[0];
}

export default function VolcanoDetail() {
  const { name } = useParams<{ name: string }>();
  const [volcano, setVolcano] = useState<VolcanoDetailType | null>(null);
  const [vents, setVents] = useState<VentDetail[]>([]);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!name) return;
    const api = getApi();

    api
      .get<VolcanoDetailType>(`/volcanoes/${encodeURIComponent(name)}`)
      .then((res) => {
        if (res.status === 200 && res.data) setVolcano(res.data);
        else setError('Volcano not found');
      })
      .catch((e) => setError((e as Error).message));

    api
      .get<VentDetail[]>(`/volcanoes/${encodeURIComponent(name)}/vents`)
      .then((res) => {
        if (res.status === 200 && res.data) setVents(res.data);
      })
      .catch(() => {});
  }, [name]);

  // Cross-section: tallest vent, fallback to main
  const crossSectionVent = useMemo(() => {
    const detailed = vents.filter((v) => v.summitBlock != null);
    return getTallestVent(detailed) ?? getMainVent(detailed) ?? getMainVent(vents);
  }, [vents]);

  // Aggregated stats across all detailed vents
  const aggregated = useMemo(() => {
    const detailed = vents.filter((v): v is VentDetail => v.summitBlock != null);
    if (detailed.length === 0) return null;

    const highestStatus = getHighestStatusVent(detailed);
    const tallest = getTallestVent(detailed);
    const maxFlow = detailed.reduce((m, v) => Math.max(m, v.longestFlowLength ?? 0), 0);
    const maxBomb = detailed.reduce((m, v) => Math.max(m, v.bombMaxDistance ?? 0), 0);
    const activeVents = detailed.filter((v) => (STATUS_SEVERITY[v.status] ?? 0) >= 2).length;

    return {
      highestSummit: tallest?.summitBlock?.y ?? tallest?.summitY,
      lowestBase: detailed.reduce((m, v) => Math.min(m, v.baseY), Infinity),
      mostActiveStatus: highestStatus?.status ?? 'DORMANT',
      longestFlow: maxFlow,
      maxBombRange: maxBomb,
      activeVents,
      totalVents: detailed.length,
    };
  }, [vents]);

  const settings = useSettings();
  const blueMapUrl = useMemo(() => {
    if (!settings?.blueMap?.publicUrl || !volcano?.location) return null;
    return buildBlueMapUrl(settings.blueMap.publicUrl, volcano.location);
  }, [settings, volcano]);

  return (
    <div className="p-6 space-y-6">
      <div>
        <Link to="/volcanoes">
          <Button variant="ghost" size="sm" className="gap-1 -ml-2 mb-2 text-muted-foreground">
            <ArrowLeft className="size-4" /> Volcanoes
          </Button>
        </Link>

        {error ? (
          <div className="rounded-lg border border-destructive bg-destructive/10 p-4 text-sm text-destructive">
            {error}
          </div>
        ) : !volcano ? (
          <p className="text-muted-foreground">Loading...</p>
        ) : (
          <>
            <div className="flex items-center gap-3 mb-1">
              <h1 className="text-2xl font-bold">{volcano.name}</h1>
              <Badge variant="secondary" className="gap-1">
                <span className={`inline-block size-2 rounded-full ${STATUS_COLORS[volcano.status] || 'bg-gray-400'}`} />
                {volcano.status.replace(/_/g, ' ')}
              </Badge>
            </div>
            <p className="text-sm text-muted-foreground">{volcano.style}</p>
          </>
        )}
      </div>

      {volcano && (
        <>
          {/* Cross-section of tallest vent */}
          {crossSectionVent && crossSectionVent.summitBlock != null && (
            <VolcanoCrossSection vent={crossSectionVent} />
          )}

          <SmartTabs defaultValue="overview">
            <WrappedTabsList>
              <IconTabsTrigger value="overview" icon={Info}>Overview</IconTabsTrigger>
              <IconTabsTrigger value="vents" icon={VolcanoIcon}>Vents</IconTabsTrigger>
            </WrappedTabsList>

            <TabsContent value="overview">
              <div className="space-y-4">
                {/* Aggregated stats */}
                {aggregated && (
                  <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
                    <PropCard label="Status" value={aggregated.mostActiveStatus.replace(/_/g, ' ')} />
                    <PropCard label="Active Vents" value={`${aggregated.activeVents} / ${aggregated.totalVents}`} />
                    <PropCard label="Highest Summit" value={aggregated.highestSummit != null ? `Y=${aggregated.highestSummit}` : '--'} />
                    <PropCard label="Base" value={aggregated.lowestBase < Infinity ? `Y=${aggregated.lowestBase}` : '--'} />
                    <PropCard label="Longest Flow" value={aggregated.longestFlow > 0 ? `${aggregated.longestFlow.toFixed(1)}m` : '--'} />
                    <PropCard label="Max Bomb Range" value={aggregated.maxBombRange > 0 ? `${aggregated.maxBombRange.toFixed(1)}m` : '--'} />
                  </div>
                )}

                {/* Location */}
                {volcano.location && (
                  <LocationCard label="Location" location={volcano.location} blueMapUrl={blueMapUrl} />
                )}
              </div>
            </TabsContent>

            <TabsContent value="vents">
              <VentsTab vents={vents} fallbackVents={volcano.vents} volcanoName={name!} />
            </TabsContent>
          </SmartTabs>
        </>
      )}
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  return (
    <Badge variant="secondary" className="gap-1">
      <span className={`inline-block size-2 rounded-full ${STATUS_COLORS[status] || 'bg-gray-400'}`} />
      {status.replace(/_/g, ' ')}
    </Badge>
  );
}

function VentsTab({
  vents,
  fallbackVents,
  volcanoName,
}: {
  vents: VentDetail[];
  fallbackVents: VentSummary[];
  volcanoName: string;
}) {
  const allVents = vents.length > 0 ? vents : fallbackVents;
  const mainVent = allVents.find((v) => v.name === 'main');
  const subVents = allVents.filter((v) => v.name !== 'main');

  if (allVents.length === 0) {
    return <p className="text-sm text-muted-foreground">No vents.</p>;
  }

  return (
    <div className="space-y-4">
      {/* Main vent card */}
      {mainVent && (
        <Link
          to={`/volcanoes/${encodeURIComponent(volcanoName)}/vents/${encodeURIComponent(mainVent.name)}`}
          className="block"
        >
          <div className="rounded-lg border border-border bg-card p-4 space-y-2 transition-colors hover:bg-accent/50">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <VolcanoIcon className="size-4 text-muted-foreground" />
                <span className="font-medium">Main Vent</span>
                <span className="text-xs text-muted-foreground">{mainVent.type}</span>
              </div>
              <div className="flex items-center gap-2">
                {!mainVent.enabled && <Badge variant="outline">Disabled</Badge>}
                <StatusBadge status={mainVent.status} />
              </div>
            </div>
            {'craterRadius' in mainVent && (
              <div className="grid grid-cols-2 gap-x-6 gap-y-1 text-xs text-muted-foreground sm:grid-cols-4">
                <div>Crater: {(mainVent as VentDetail).craterRadius}</div>
                <div>Flow: {(mainVent as VentDetail).longestFlowLength}</div>
                <div>Style: {mainVent.style}</div>
              </div>
            )}
          </div>
        </Link>
      )}

      {/* Sub-vents table */}
      {subVents.length > 0 && (
        <div className="rounded-lg border border-border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Style</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="text-right">Enabled</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {subVents.map((vent) => (
                <TableRow key={vent.name} className="cursor-pointer">
                  <TableCell>
                    <Link
                      to={`/volcanoes/${encodeURIComponent(volcanoName)}/vents/${encodeURIComponent(vent.name)}`}
                      className="font-medium hover:underline"
                    >
                      {vent.name}
                    </Link>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{vent.type}</TableCell>
                  <TableCell className="text-muted-foreground">{vent.style}</TableCell>
                  <TableCell><StatusBadge status={vent.status} /></TableCell>
                  <TableCell className="text-right">{vent.enabled ? 'Yes' : 'No'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  );
}


function PropCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-border bg-card p-3">
      <div className="text-xs text-muted-foreground uppercase tracking-wider mb-1">{label}</div>
      <div className="text-sm font-medium truncate">{value}</div>
    </div>
  );
}
