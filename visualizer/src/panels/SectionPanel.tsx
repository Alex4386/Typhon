import { useEffect, useMemo, useRef, useState } from 'react';
import { SectionFlag, type SectionFrame } from '../protocol/frames';
import type { SectionDatum, WorldInfo } from '../protocol/messages';
import { requestSection } from '../net/connection';
import { useStore } from '../store/store';
import { THERMAL, hexToRgb, ramp, rgbCss, shadeFor, type RGB } from '../util/color';

type SectionMode = 'strata' | 'material' | 'temperature' | 'water';

const MODES: [SectionMode, string][] = [
  ['strata', 'Stratigraphy'],
  ['material', 'Material'],
  ['temperature', 'Temperature'],
  ['water', 'Water / steam'],
];

const PAD = { l: 52, r: 10, t: 10, b: 26 };
/** Height share of the shallow inset under an absolute section. */
const INSET_SHARE = 0.34;
/** Depth of the shallow inset (m below ground). */
const INSET_DEPTH = 30;

/** View choices: absolute depth below the lowest ground (km scale) or a ground-relative window. */
type View = { datum: SectionDatum; depth: number; label: string };
const VIEWS: View[] = [
  { datum: 'surface', depth: 5, label: 'top 5 m' },
  { datum: 'surface', depth: 20, label: 'top 20 m' },
  { datum: 'surface', depth: 100, label: 'top 100 m' },
  { datum: 'absolute', depth: 1000, label: '1 km' },
  { datum: 'absolute', depth: 3000, label: '3 km' },
  { datum: 'absolute', depth: 6000, label: '6 km' },
  { datum: 'absolute', depth: 10000, label: '10 km' },
];

/** Plot rectangle inside the canvas (CSS px). */
type Box = { x: number; y: number; w: number; h: number };

export function SectionPanel({ world }: { world: WorldInfo }) {
  const section = useStore((s) => s.section);
  const shallow = useStore((s) => s.sectionShallow);
  const pending = useStore((s) => s.sectionPending);
  const polyline = useStore((s) => s.sectionPolyline);
  const tool = useStore((s) => s.tool);
  const set = useStore((s) => s.set);
  const [mode, setMode] = useState<SectionMode>('strata');
  const [viewIndex, setViewIndex] = useState(5);
  const [inset, setInset] = useState(true);
  const view = VIEWS[viewIndex];
  const depth = view.depth;
  const [hover, setHover] = useState<string>('');
  const canvasRef = useRef<HTMLCanvasElement>(null);

  const relative = view.datum === 'surface';
  const zMax = relative ? Math.max(1, depth * 0.15) : world.elevationRange[1] + 300;
  const zMin = relative ? -depth : Math.min(world.seaLevel, world.elevationRange[0]) - depth;
  const withInset = !relative && inset;

  const cut = () => {
    if (polyline.length < 2) return;
    const w = canvasRef.current?.clientWidth ?? 600;
    const nu = Math.min(1024, Math.max(64, Math.round(w - PAD.l - PAD.r)));
    requestSection(polyline, zMin, zMax, nu, 260, view.datum);
    if (withInset) requestSection(polyline, -INSET_DEPTH, INSET_DEPTH * 0.12, nu, 120, 'surface');
  };

  // auto-refresh the section while it is shown, to follow the simulation
  useEffect(() => {
    if (polyline.length < 2 || !section) return;
    const id = window.setInterval(cut, 3000);
    return () => window.clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [polyline, section !== null, viewIndex, inset]);

  useEffect(() => {
    const cv = canvasRef.current;
    if (!cv) return;
    const dpr = window.devicePixelRatio || 1;
    cv.width = Math.round(cv.clientWidth * dpr);
    cv.height = Math.round(cv.clientHeight * dpr);
    const g = cv.getContext('2d')!;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, cv.clientWidth, cv.clientHeight);
    const W = cv.clientWidth;
    const H = cv.clientHeight;
    // Only draw a reply that matches the selected datum (a stale one arrives after a view change).
    const main = section && (section.meta.datum ?? 'absolute') === view.datum ? section : null;
    if (main) {
      const mainH = withInset && shallow ? (H - PAD.t - PAD.b) * (1 - INSET_SHARE) - 18 : H - PAD.t - PAD.b;
      drawSection(g, { x: PAD.l, y: PAD.t, w: W - PAD.l - PAD.r, h: mainH }, H, main, world, mode, true);
      if (withInset && shallow) {
        const top = PAD.t + mainH + 18;
        drawSection(g, { x: PAD.l, y: top, w: W - PAD.l - PAD.r, h: H - PAD.b - top }, H, shallow, world, mode, false);
        g.fillStyle = '#c9d1d9';
        g.font = '11px system-ui';
        g.fillText(`top ${INSET_DEPTH} m below ground, true thickness`, PAD.l + 4, top - 5);
      }
    }
    else {
      g.fillStyle = '#8b949e';
      g.font = '13px system-ui';
      g.fillText(polyline.length < 2 ? 'Pick the "Section" tool and click two or more points on the map, then "Cut".' : 'Press "Cut" to sample the section.', 16, 28);
    }
  }, [section, shallow, mode, world, polyline.length, viewIndex, inset]);

  const onMove = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!section) return;
    const cv = e.currentTarget;
    const r = cv.getBoundingClientRect();
    const w = r.width - PAD.l - PAD.r;
    const h = (r.height - PAD.t - PAD.b) * (withInset && shallow ? 1 - INSET_SHARE : 1) - (withInset && shallow ? 18 : 0);
    const fx = (e.clientX - r.left - PAD.l) / w;
    const fy = 1 - (e.clientY - r.top - PAD.t) / h;
    if (fx < 0 || fx > 1 || fy < 0 || fy > 1) return setHover('');
    const i = Math.min(section.nu - 1, Math.floor(fx * section.nu));
    const k = Math.min(section.nz - 1, Math.floor(fy * section.nz));
    const p = k * section.nu + i;
    const z = section.meta.zMin + fy * (section.meta.zMax - section.meta.zMin);
    const zLabel = relative ? `${z >= 0 ? '+' : ''}${z.toFixed(1)} m rel. ground` : `z ${z.toFixed(0)} m`;
    const mat = world.materials.find((m) => m.id === section.material[p]);
    const unit = section.meta.units.find((u) => u.id === section.unit[p]);
    const f = section.flags[p];
    setHover(
      `${((fx * section.meta.length) / 1000).toFixed(2)} km, ${zLabel} · ${f & SectionFlag.Air ? 'air' : f & SectionFlag.Void ? 'void' : mat?.name ?? '?'}` +
        (unit && !(f & SectionFlag.Air) ? ` · ${unit.label}` : '') +
        ` · ${section.temperatureC[p].toFixed(0)} °C · sat ${(section.saturation[p] * 100).toFixed(0)}% · steam ${(section.steam[p] * 100).toFixed(0)}%`,
    );
  };

  return (
    <div className="panel section-panel">
      <div className="panel-head">
        <strong>Cross-section</strong>
        <button className={tool === 'section' ? 'on' : ''} onClick={() => set({ tool: tool === 'section' ? 'orbit' : 'section' })}>
          ✎ Draw line
        </button>
        <button disabled={polyline.length < 2} onClick={cut}>
          ✂ Cut{pending !== null ? ' …' : ''}
        </button>
        <button onClick={() => set({ sectionPolyline: [], section: null })}>Clear</button>
        <select value={mode} onChange={(e) => setMode(e.target.value as SectionMode)}>
          {MODES.map(([m, label]) => (
            <option key={m} value={m}>
              {label}
            </option>
          ))}
        </select>
        <label title="km views show absolute elevation; 'top N m' follows the ground so thin deposits keep their true thickness">
          view
          <select value={viewIndex} onChange={(e) => setViewIndex(Number(e.target.value))}>
            {VIEWS.map((v, i) => (
              <option key={v.label} value={i}>
                {v.label}
              </option>
            ))}
          </select>
        </label>
        {!relative && (
          <label title={`Adds a strip with the top ${INSET_DEPTH} m below ground at true thickness`}>
            <input type="checkbox" checked={inset} onChange={(e) => setInset(e.target.checked)} /> shallow inset
          </label>
        )}
        <span className="muted">{polyline.length} pts</span>
      </div>
      <canvas ref={canvasRef} className="section-canvas" onMouseMove={onMove} onMouseLeave={() => setHover('')} />
      <div className="section-foot">
        <span className="muted">{hover || (section ? `t = ${section.time.toFixed(0)} s` : '')}</span>
        {section && <UnitLegend section={section} world={world} mode={mode} />}
      </div>
    </div>
  );
}

const ISOTHERMS: [number, string][] = [
  [100, '#ffd166'],
  [300, '#f78c6b'],
  [700, '#ef476f'],
];

function UnitLegend({ section, world, mode }: { section: SectionFrame; world: WorldInfo; mode: SectionMode }) {
  // youngest units first (they are the interesting ones), then the background geology
  const items = useMemo(() => [...section.meta.units].sort((a, b) => (b.time ?? -1) - (a.time ?? -1)).slice(0, 8), [section]);
  const hasTable = useMemo(() => section.waterTableZ.some((z) => !Number.isNaN(z)), [section]);
  const hasSteam = useMemo(() => section.steam.some((s) => s > 0.2), [section]);
  return (
    <span className="legend">
      {mode === 'strata' &&
        items.map((u) => (
          <span key={u.id} className="legend-item" title={u.time != null ? `emplaced at t = ${u.time.toFixed(0)} s` : 'pre-existing geology'}>
            <i style={{ background: rgbCss(unitColour(world, u.id, u.depositType, u.time != null)) }} />
            {u.label}
          </span>
        ))}
      {mode !== 'temperature' &&
        ISOTHERMS.map(([t, col]) => (
          <span key={t} className="legend-item" title={`${t} °C isotherm`}>
            <i className="dots" style={{ color: col }} />
            {t} °C
          </span>
        ))}
      {hasTable && (
        <span className="legend-item" title="groundwater table">
          <i className="dash" style={{ borderColor: '#4cc9f0' }} />
          water table
        </span>
      )}
      {hasSteam && mode === 'strata' && (
        <span className="legend-item" title="steam fraction > 20 %">
          <i className="hatch" />
          steam
        </span>
      )}
    </span>
  );
}

function unitColour(world: WorldInfo, id: number, depositType: number, recent: boolean): RGB {
  const base = hexToRgb(world.depositTypes.find((d) => d.id === depositType)?.color ?? '#777777');
  return recent ? shadeFor(base, id) : base;
}

function drawSection(g: CanvasRenderingContext2D, box: Box, H: number, s: SectionFrame, world: WorldInfo, mode: SectionMode, distanceAxis: boolean) {
  const { w, h } = box;
  const relative = (s.meta.datum ?? 'absolute') === 'surface';
  const img = new ImageData(s.nu, s.nz);
  const matRgb = new Map(world.materials.map((m) => [m.id, hexToRgb(m.color)]));
  const unitRgb = new Map(s.meta.units.map((u) => [u.id, unitColour(world, u.id, u.depositType, u.time != null)]));
  const c: RGB = [0, 0, 0];
  for (let k = 0; k < s.nz; k++) {
    for (let i = 0; i < s.nu; i++) {
      const p = k * s.nu + i;
      const o = ((s.nz - 1 - k) * s.nu + i) * 4;
      const f = s.flags[p];
      let a = 255;
      if (f & SectionFlag.Air) {
        c[0] = 0.05;
        c[1] = 0.07;
        c[2] = 0.09;
      } else if (f & SectionFlag.WaterBody) {
        c[0] = 0.15;
        c[1] = 0.38;
        c[2] = 0.65;
      } else if (f & SectionFlag.Magma) {
        ramp(THERMAL, s.temperatureC[p], c);
        c[0] = Math.max(c[0], 0.95);
      } else if (f & SectionFlag.Void) {
        c[0] = c[1] = c[2] = 0;
      } else {
        switch (mode) {
          case 'strata': {
            const u = unitRgb.get(s.unit[p]) ?? matRgb.get(s.material[p]) ?? [0.5, 0.5, 0.5];
            c[0] = u[0];
            c[1] = u[1];
            c[2] = u[2];
            break;
          }
          case 'material': {
            const m = matRgb.get(s.material[p]) ?? [0.5, 0.5, 0.5];
            c[0] = m[0];
            c[1] = m[1];
            c[2] = m[2];
            break;
          }
          case 'temperature':
            ramp(THERMAL, s.temperatureC[p], c);
            break;
          case 'water': {
            const sat = s.saturation[p];
            c[0] = 0.35 - sat * 0.25;
            c[1] = 0.33 - sat * 0.05;
            c[2] = 0.3 + sat * 0.45;
            const st = s.steam[p];
            c[0] += (1 - c[0]) * st;
            c[1] += (1 - c[1]) * st;
            c[2] += (1 - c[2]) * st;
            break;
          }
        }
        // steam hatching in strata mode
        if (mode === 'strata' && s.steam[p] > 0.2 && (i + k) % 4 === 0) {
          c[0] = c[1] = c[2] = 0.92;
        }
      }
      img.data[o] = c[0] * 255;
      img.data[o + 1] = c[1] * 255;
      img.data[o + 2] = c[2] * 255;
      img.data[o + 3] = a;
      a = 255;
    }
  }
  const tmp = document.createElement('canvas');
  tmp.width = s.nu;
  tmp.height = s.nz;
  tmp.getContext('2d')!.putImageData(img, 0, 0);
  g.imageSmoothingEnabled = false;
  g.drawImage(tmp, box.x, box.y, w, h);

  const X = (u: number) => box.x + (u / s.meta.length) * w;
  const Y = (z: number) => box.y + (1 - (z - s.meta.zMin) / (s.meta.zMax - s.meta.zMin)) * h;
  const colX = (i: number) => box.x + ((i + 0.5) / s.nu) * w;
  g.save();
  g.beginPath();
  g.rect(box.x, box.y, w, h);
  g.clip();

  // isotherms (100, 300, 700 °C): mark vertical crossings
  if (mode !== 'temperature') {
    for (const [iso, col] of ISOTHERMS) {
      g.fillStyle = col;
      for (let i = 0; i < s.nu; i += 1) {
        for (let k = 1; k < s.nz; k++) {
          const a = s.temperatureC[(k - 1) * s.nu + i];
          const b = s.temperatureC[k * s.nu + i];
          if ((a - iso) * (b - iso) < 0 && !(s.flags[k * s.nu + i] & SectionFlag.Air)) {
            const z = s.meta.zMin + (k / s.nz) * (s.meta.zMax - s.meta.zMin);
            g.fillRect(colX(i) - 0.75, Y(z) - 0.75, 1.5, 1.5);
          }
        }
      }
    }
  }

  // ground surface and water table
  g.lineWidth = 1.5;
  g.strokeStyle = '#e6edf3';
  g.beginPath();
  for (let i = 0; i < s.nu; i++) (i === 0 ? g.moveTo : g.lineTo).call(g, colX(i), Y(s.surfaceZ[i]));
  g.stroke();
  g.setLineDash([5, 4]);
  g.strokeStyle = '#4cc9f0';
  g.beginPath();
  let pen = false;
  for (let i = 0; i < s.nu; i++) {
    const z = s.waterTableZ[i];
    if (Number.isNaN(z)) {
      pen = false;
      continue;
    }
    if (pen) g.lineTo(colX(i), Y(z));
    else g.moveTo(colX(i), Y(z));
    pen = true;
  }
  g.stroke();
  g.setLineDash([]);

  // overlays
  for (const o of s.meta.overlays) {
    if (o.kind === 'chamber') {
      g.strokeStyle = '#ff7b2e';
      g.lineWidth = 2;
      g.beginPath();
      g.ellipse(X(o.u), Y(o.z), Math.abs(X(o.u + o.rx) - X(o.u)), Math.abs(Y(o.z - o.rz) - Y(o.z)), 0, 0, Math.PI * 2);
      g.stroke();
      g.fillStyle = '#ffb38a';
      g.font = '11px system-ui';
      g.fillText(`chamber ${o.temperatureC.toFixed(0)} °C`, X(o.u) + 6, Y(o.z));
    } else if (o.kind === 'conduit') {
      g.strokeStyle = o.active ? '#ff5a1f' : '#8a4a3a';
      g.lineWidth = Math.max(2, Math.abs(X(o.u + o.width) - X(o.u)));
      g.beginPath();
      g.moveTo(X(o.u), Y(o.zBottom));
      g.lineTo(X(o.u), Y(o.zTop));
      g.stroke();
    } else if (o.kind === 'dike') {
      g.strokeStyle = o.active ? '#ff3b6b' : '#a0526a';
      g.lineWidth = 2;
      g.beginPath();
      o.points.forEach(([u, z], n) => (n === 0 ? g.moveTo(X(u), Y(z)) : g.lineTo(X(u), Y(z))));
      g.stroke();
    }
  }

  if (mode === 'strata') labelUnits(g, s, box);
  g.restore();

  // axes
  g.lineWidth = 1;
  g.strokeStyle = '#30363d';
  g.strokeRect(box.x, box.y, w, h);
  g.fillStyle = '#8b949e';
  g.font = '11px system-ui';
  const zStep = niceStep((s.meta.zMax - s.meta.zMin) / Math.max(2, Math.round(h / 40)));
  for (let z = Math.ceil(s.meta.zMin / zStep) * zStep; z <= s.meta.zMax + 1e-9; z += zStep) {
    const label = relative ? (Math.abs(z) < 1e-9 ? '0' : `${+z.toFixed(2)}`) : `${z}`;
    g.fillText(label, 4, Y(z) + 4);
    g.fillRect(box.x - 4, Y(z), 4, 1);
  }
  // vertical exaggeration (how much z is stretched relative to distance)
  const ve = h / (s.meta.zMax - s.meta.zMin) / (w / s.meta.length);
  g.fillText(ve >= 1.5 ? `VE ×${ve >= 10 ? ve.toFixed(0) : ve.toFixed(1)}` : '', box.x + w - 52, box.y + 12);
  if (distanceAxis) {
    const uStep = niceStep(s.meta.length / 6);
    for (let u = 0; u <= s.meta.length; u += uStep) {
      g.fillText(`${(u / 1000).toFixed(uStep < 1000 ? 1 : 0)} km`, X(u) - 12, H - 8);
    }
  }
  g.fillText(relative ? 'm ±gnd' : 'm', 4, box.y + 10);
}

/**
 * Labels each eruption unit once, where its band is thickest on screen; units too thin for text
 * get a small pointer label instead so they stay findable.
 */
function labelUnits(g: CanvasRenderingContext2D, s: SectionFrame, box: Box) {
  const best = new Map<number, { px: number; i: number; k0: number; k1: number }>();
  const recent = new Map(s.meta.units.filter((u) => u.time != null).map((u) => [u.id, u]));
  if (recent.size === 0) return;
  const rowPx = box.h / s.nz;
  for (let i = 0; i < s.nu; i++) {
    let k = 0;
    while (k < s.nz) {
      const id = s.unit[k * s.nu + i];
      let k1 = k;
      while (k1 + 1 < s.nz && s.unit[(k1 + 1) * s.nu + i] === id) k1++;
      if (recent.has(id) && !(s.flags[k * s.nu + i] & SectionFlag.Air)) {
        const px = (k1 - k + 1) * rowPx;
        const b = best.get(id);
        if (!b || px > b.px) best.set(id, { px, i, k0: k, k1 });
      }
      k = k1 + 1;
    }
  }
  g.font = 'bold 10px system-ui';
  for (const [id, b] of best) {
    const u = recent.get(id)!;
    const x = Math.min(box.x + box.w - 90, Math.max(box.x + 4, box.x + ((b.i + 0.5) / s.nu) * box.w - 20));
    const yMid = box.y + box.h - ((b.k0 + b.k1 + 1) / 2) * rowPx;
    const text = b.px >= 11 ? u.label : `▸ ${u.label}`;
    const y = b.px >= 11 ? yMid + 3 : Math.min(box.y + box.h - 3, yMid + 12);
    g.fillStyle = 'rgba(0,0,0,0.55)';
    g.fillText(text, x + 1, y + 1);
    g.fillStyle = '#f0f6fc';
    g.fillText(text, x, y);
  }
}

function niceStep(x: number): number {
  const p = Math.pow(10, Math.floor(Math.log10(x)));
  const f = x / p;
  return (f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10) * p;
}
