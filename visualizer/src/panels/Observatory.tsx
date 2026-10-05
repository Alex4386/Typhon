import { useEffect, useMemo, useRef, useState } from 'react';
import type uPlot from 'uplot';
import { SimpleSelect } from '@/components/fields';
import { Tip } from '@/components/tip';
import { Badge } from '@/components/ui/badge';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import type { SimEvent, VolcanoState, WorldInfo } from '../protocol/messages';
import { ALERT_LEVELS, useStore, type HistorySample } from '../store/store';
import { ALERT_COLORS } from '../util/color';
import { ALERT_LABEL, REGIME_LABEL, STYLE_LABEL } from './events';
import { Helicorder } from './Helicorder';
import { UChart } from './UChart';

type Tab = 'seismic' | 'magma' | 'deformation' | 'alerts';

const TABS: [Tab, string, string][] = [
  ['seismic', 'Earthquakes', 'Seismograph, shaking energy (RSAM) and quake counts'],
  ['magma', 'Magma', 'Pressure in the magma chamber, temperature and composition'],
  ['deformation', 'Ground motion', 'GPS stations: how much the ground swells or sinks'],
  ['alerts', 'Status history', 'How the alert level changed over time'],
];

export function Observatory({ world }: { world: WorldInfo }) {
  const [tab, setTab] = useState<Tab>('seismic');
  const selected = useStore((s) => s.selectedVolcano);
  const set = useStore((s) => s.set);
  const state = useStore((s) => s.state);
  const history = useStore((s) => (selected ? s.history[selected] : undefined)) ?? [];
  const vs = selected ? state?.volcanoes[selected] : undefined;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        {world.volcanoes.length > 1 && <SimpleSelect label="Volcano" value={selected ?? ''} onChange={(v) => set({ selectedVolcano: v })} options={world.volcanoes.map((v) => [v.id, v.name] as const)} />}
        {vs && <AlertBadge level={vs.alert.level} style={vs.alert.style ?? undefined} regime={vs.chamber.regime} />}
      </div>
      <Tabs value={tab} onValueChange={(v) => setTab(v as Tab)}>
        <TabsList className="w-full" aria-label="Instrument">
          {TABS.map(([t, label, title]) => (
            <Tip key={t} content={title}>
              <TabsTrigger value={t}>{label}</TabsTrigger>
            </Tip>
          ))}
        </TabsList>
      </Tabs>
      <div>
        {tab === 'seismic' && <SeismicTab volcanoId={selected} history={history} />}
        {tab === 'magma' && vs && <MagmaTab vs={vs} history={history} />}
        {tab === 'deformation' && <DeformationTab history={history} vs={vs} />}
        {tab === 'alerts' && <AlertsTab history={history} />}
      </div>
    </div>
  );
}

export function AlertBadge({ level, style, regime }: { level: string; style?: string; regime?: string }) {
  return (
    <Badge className="text-white" title={level === 'ERUPTING' && style ? STYLE_LABEL[style] : undefined} style={{ background: ALERT_COLORS[level] ?? '#444' }}>
      {ALERT_LABEL[level] ?? level}
      {level === 'ERUPTING' && style ? ` · ${STYLE_LABEL[style]?.split(' (')[0] ?? style}` : ''}
      {regime && regime !== 'NONE' ? ` · ${REGIME_LABEL[regime] ?? regime}` : ''}
    </Badge>
  );
}

function series(history: HistorySample[], ...fs: ((h: HistorySample) => number)[]): uPlot.AlignedData {
  return [history.map((h) => h.time), ...fs.map((f) => history.map(f))] as uPlot.AlignedData;
}

function SeismicTab({ volcanoId, history }: { volcanoId: string | null; history: HistorySample[] }) {
  const events = useStore((s) => s.events);
  const gr = useMemo(() => {
    const mags = events.filter((e): e is Extract<SimEvent, { kind: 'seismic' }> => e.kind === 'seismic' && e.volcanoId === volcanoId && (e.type === 'VT' || e.type === 'EXPLOSION')).map((e) => e.magnitude);
    const xs: number[] = [];
    const ys: number[] = [];
    for (let m = 0; m <= 5; m += 0.2) {
      const n = mags.filter((x) => x >= m).length;
      if (n > 0) {
        xs.push(Number(m.toFixed(1)));
        ys.push(n);
      }
    }
    return [xs, ys] as uPlot.AlignedData;
  }, [events, volcanoId]);
  const rsam = useMemo(() => series(history, (h) => h.rsam, (h) => h.vt, (h) => h.lp), [history]);
  return (
    <div className="grid grid-cols-1 gap-3 @lg:grid-cols-2">
      <div className="@lg:col-span-2">
        <Helicorder volcanoId={volcanoId} />
      </div>
      <UChart title="RSAM / event rates" series={[{ label: 'RSAM', color: '#f4a261' }, { label: 'VT/min', color: '#ffd166', scale: 'r' }, { label: 'LP/min', color: '#06d6a0', scale: 'r' }]} rightScale="r" data={rsam} />
      <UChart title="Gutenberg–Richter N(≥M)" series={[{ label: 'N(≥M)', color: '#8ecae6' }]} data={gr} timeAxis={false} logY xLabel="magnitude" />
    </div>
  );
}

function Gauge({ label, value, max, unit, color, digits = 1, marker }: { label: string; value: number; max: number; unit: string; color: string; digits?: number; marker?: number }) {
  const f = Math.max(0, Math.min(1, value / max));
  return (
    <div className="flex flex-col gap-1">
      <div className="flex justify-between text-xs">
        <span className="text-muted-foreground">{label}</span>
        <span className="tabular-nums">
          {value.toFixed(digits)} {unit}
        </span>
      </div>
      <div className="relative h-1.5 rounded-full bg-muted">
        <div className="h-full rounded-full" style={{ width: `${f * 100}%`, background: color }} />
        {marker !== undefined && <i className="absolute -top-1 h-3.5 w-0.5 bg-foreground" style={{ left: `${Math.min(1, marker / max) * 100}%` }} />}
      </div>
    </div>
  );
}

function MagmaTab({ vs, history }: { vs: VolcanoState; history: HistorySample[] }) {
  const p = useMemo(() => series(history, (h) => h.overpressure, (h) => h.strength, (h) => h.eruptionRate), [history]);
  const comp = useMemo(() => series(history, (h) => h.silica, (h) => h.water, (h) => h.crystals * 100), [history]);
  const c = vs.chamber;
  return (
    <div className="grid grid-cols-1 gap-3 @lg:grid-cols-2">
      <div className="flex flex-col gap-2.5">
        <Gauge label="Overpressure" value={c.overpressureMPa} max={c.tensileStrengthMPa * 1.2} unit="MPa" color="#e76f51" marker={c.tensileStrengthMPa} />
        <Gauge label="Temperature" value={c.temperatureC} max={1300} unit="°C" color="#f4a261" digits={0} />
        <Gauge label="SiO₂" value={c.silicaWt} max={80} unit="wt%" color="#a8dadc" />
        <Gauge label="H₂O" value={c.waterWt} max={8} unit="wt%" color="#457b9d" digits={2} />
        <Gauge label="Crystals" value={c.crystalFraction * 100} max={100} unit="%" color="#cdb4db" digits={0} />
        <Gauge label="Eruption rate" value={c.eruptionRate} max={Math.max(100, c.eruptionRate * 1.2)} unit="m³/s" color="#ff5a1f" digits={1} />
        {vs.plume && <Gauge label="Plume top" value={vs.plume.topZ} max={30000} unit="m" color="#adb5bd" digits={0} />}
      </div>
      <UChart title="Chamber pressure & eruption rate" series={[{ label: 'ΔP MPa', color: '#e76f51' }, { label: 'strength', color: '#6c757d' }, { label: 'rate m³/s', color: '#ff5a1f', scale: 'r' }]} rightScale="r" data={p} />
      <div className="@lg:col-span-2">
        <UChart title="Melt composition" series={[{ label: 'SiO₂ wt%', color: '#a8dadc' }, { label: 'H₂O wt%', color: '#457b9d', scale: 'r' }, { label: 'crystals %', color: '#cdb4db', scale: 'r' }]} rightScale="r" data={comp} height={110} />
      </div>
    </div>
  );
}

const STATION_COLORS = ['#8ecae6', '#ffb703', '#fb8500', '#90be6d', '#f28482', '#cdb4db'];

function DeformationTab({ history, vs }: { history: HistorySample[]; vs?: VolcanoState }) {
  const ids = vs?.deformation.stations.map((s) => s.id) ?? [];
  const up = useMemo(() => series(history, ...ids.map((id) => (h: HistorySample) => (h.stations[id]?.up ?? NaN) * 1000)), [history, ids.join()]);
  const horiz = useMemo(() => series(history, ...ids.map((id) => (h: HistorySample) => Math.hypot(h.stations[id]?.east ?? NaN, h.stations[id]?.north ?? NaN) * 1000)), [history, ids.join()]);
  return (
    <div className="grid grid-cols-1 gap-3 @lg:grid-cols-2">
      <UChart title="GNSS vertical (mm)" series={ids.map((id, k) => ({ label: id, color: STATION_COLORS[k % STATION_COLORS.length] }))} data={up} />
      <UChart title="GNSS horizontal (mm)" series={ids.map((id, k) => ({ label: id, color: STATION_COLORS[k % STATION_COLORS.length] }))} data={horiz} />
      {vs && (
        <Table className="text-xs tabular-nums @lg:col-span-2">
          <TableHeader>
            <TableRow>
              <TableHead>Station</TableHead>
              <TableHead className="text-right">E mm</TableHead>
              <TableHead className="text-right">N mm</TableHead>
              <TableHead className="text-right">U mm</TableHead>
              <TableHead className="text-right">tilt X µrad</TableHead>
              <TableHead className="text-right">tilt Y µrad</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {vs.deformation.stations.map((s) => (
              <TableRow key={s.id}>
                <TableCell>{s.id}</TableCell>
                <TableCell className="text-right">{(s.east * 1000).toFixed(1)}</TableCell>
                <TableCell className="text-right">{(s.north * 1000).toFixed(1)}</TableCell>
                <TableCell className="text-right">{(s.up * 1000).toFixed(1)}</TableCell>
                <TableCell className="text-right">{s.tiltX.toFixed(2)}</TableCell>
                <TableCell className="text-right">{s.tiltY.toFixed(2)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  );
}

function AlertsTab({ history }: { history: HistorySample[] }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const cv = ref.current;
    if (!cv || history.length < 2) return;
    const dpr = window.devicePixelRatio || 1;
    cv.width = cv.clientWidth * dpr;
    cv.height = cv.clientHeight * dpr;
    const g = cv.getContext('2d')!;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    const W = cv.clientWidth;
    const H = cv.clientHeight;
    const t0 = history[0].time;
    const t1 = history[history.length - 1].time;
    for (let k = 0; k + 1 < history.length; k++) {
      const x0 = ((history[k].time - t0) / (t1 - t0 || 1)) * W;
      const x1 = ((history[k + 1].time - t0) / (t1 - t0 || 1)) * W;
      const lvl = ALERT_LEVELS[history[k].alertIndex] ?? 'DORMANT';
      g.fillStyle = ALERT_COLORS[lvl];
      const hgt = ((history[k].alertIndex + 1) / ALERT_LEVELS.length) * H;
      g.fillRect(x0, H - hgt, Math.max(1, x1 - x0 + 0.5), hgt);
    }
  }, [history]);
  return (
    <div>
      <canvas ref={ref} className="h-28 w-full rounded-md bg-muted/40" />
      <div className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground">
        {ALERT_LEVELS.map((l) => (
          <span key={l} className="flex items-center gap-1">
            <i className="size-2.5 rounded-[2px]" style={{ background: ALERT_COLORS[l] }} />
            {ALERT_LABEL[l] ?? l}
          </span>
        ))}
      </div>
    </div>
  );
}
