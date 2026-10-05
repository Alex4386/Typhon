import { useEffect, useMemo, useRef, useState } from 'react';
import type uPlot from 'uplot';
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
    <div className="observatory">
      <div className="obs-head">
        {world.volcanoes.length > 1 && (
        <select aria-label="Volcano" value={selected ?? ''} onChange={(e) => set({ selectedVolcano: e.target.value })}>
          {world.volcanoes.map((v) => (
            <option key={v.id} value={v.id}>
              {v.name}
            </option>
          ))}
        </select>
        )}
        {vs && <AlertBadge level={vs.alert.level} style={vs.alert.style} regime={vs.chamber.regime} />}
      </div>
      <div className="seg" role="tablist" aria-label="Instrument">
        {TABS.map(([t, label, title]) => (
          <button key={t} role="tab" aria-selected={tab === t} className={tab === t ? 'on' : ''} title={title} onClick={() => setTab(t)}>
            {label}
          </button>
        ))}
      </div>
      <div className="obs-body">
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
    <span className="alert-badge" title={level === 'ERUPTING' && style ? STYLE_LABEL[style] : undefined} style={{ background: ALERT_COLORS[level] ?? '#444' }}>
      {ALERT_LABEL[level] ?? level}
      {level === 'ERUPTING' && style ? ` · ${STYLE_LABEL[style]?.split(' (')[0] ?? style}` : ''}
      {regime && regime !== 'NONE' ? ` · ${REGIME_LABEL[regime] ?? regime}` : ''}
    </span>
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
    <div className="grid2">
      <div className="span2">
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
    <div className="gauge">
      <div className="gauge-label">
        <span>{label}</span>
        <span>
          {value.toFixed(digits)} {unit}
        </span>
      </div>
      <div className="gauge-bar">
        <div style={{ width: `${f * 100}%`, background: color }} />
        {marker !== undefined && <i style={{ left: `${Math.min(1, marker / max) * 100}%` }} />}
      </div>
    </div>
  );
}

function MagmaTab({ vs, history }: { vs: VolcanoState; history: HistorySample[] }) {
  const p = useMemo(() => series(history, (h) => h.overpressure, (h) => h.strength, (h) => h.eruptionRate), [history]);
  const comp = useMemo(() => series(history, (h) => h.silica, (h) => h.water, (h) => h.crystals * 100), [history]);
  const c = vs.chamber;
  return (
    <div className="grid2">
      <div className="gauges">
        <Gauge label="Overpressure" value={c.overpressureMPa} max={c.tensileStrengthMPa * 1.2} unit="MPa" color="#e76f51" marker={c.tensileStrengthMPa} />
        <Gauge label="Temperature" value={c.temperatureC} max={1300} unit="°C" color="#f4a261" digits={0} />
        <Gauge label="SiO₂" value={c.silicaWt} max={80} unit="wt%" color="#a8dadc" />
        <Gauge label="H₂O" value={c.waterWt} max={8} unit="wt%" color="#457b9d" digits={2} />
        <Gauge label="Crystals" value={c.crystalFraction * 100} max={100} unit="%" color="#cdb4db" digits={0} />
        <Gauge label="Eruption rate" value={c.eruptionRate} max={Math.max(100, c.eruptionRate * 1.2)} unit="m³/s" color="#ff5a1f" digits={1} />
        {vs.plume && <Gauge label="Plume top" value={vs.plume.topZ} max={30000} unit="m" color="#adb5bd" digits={0} />}
      </div>
      <UChart title="Chamber pressure & eruption rate" series={[{ label: 'ΔP MPa', color: '#e76f51' }, { label: 'strength', color: '#6c757d' }, { label: 'rate m³/s', color: '#ff5a1f', scale: 'r' }]} rightScale="r" data={p} />
      <div className="span2">
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
    <div className="grid2">
      <UChart title="GNSS vertical (mm)" series={ids.map((id, k) => ({ label: id, color: STATION_COLORS[k % STATION_COLORS.length] }))} data={up} />
      <UChart title="GNSS horizontal (mm)" series={ids.map((id, k) => ({ label: id, color: STATION_COLORS[k % STATION_COLORS.length] }))} data={horiz} />
      {vs && (
        <table className="stations span2">
          <thead>
            <tr>
              <th>station</th>
              <th>E mm</th>
              <th>N mm</th>
              <th>U mm</th>
              <th>tilt X µrad</th>
              <th>tilt Y µrad</th>
            </tr>
          </thead>
          <tbody>
            {vs.deformation.stations.map((s) => (
              <tr key={s.id}>
                <td>{s.id}</td>
                <td>{(s.east * 1000).toFixed(1)}</td>
                <td>{(s.north * 1000).toFixed(1)}</td>
                <td>{(s.up * 1000).toFixed(1)}</td>
                <td>{s.tiltX.toFixed(2)}</td>
                <td>{s.tiltY.toFixed(2)}</td>
              </tr>
            ))}
          </tbody>
        </table>
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
      <canvas ref={ref} className="alert-timeline" />
      <div className="legend">
        {ALERT_LEVELS.map((l) => (
          <span key={l} className="legend-item">
            <i style={{ background: ALERT_COLORS[l] }} />
            {l}
          </span>
        ))}
      </div>
    </div>
  );
}
