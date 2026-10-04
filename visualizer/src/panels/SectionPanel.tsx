import { useEffect, useMemo, useRef, useState } from 'react';
import { SectionFlag, type SectionFrame } from '../protocol/frames';
import type { WorldInfo } from '../protocol/messages';
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

export function SectionPanel({ world }: { world: WorldInfo }) {
  const section = useStore((s) => s.section);
  const pending = useStore((s) => s.sectionPending);
  const polyline = useStore((s) => s.sectionPolyline);
  const tool = useStore((s) => s.tool);
  const set = useStore((s) => s.set);
  const [mode, setMode] = useState<SectionMode>('strata');
  const [depth, setDepth] = useState(6000);
  const [hover, setHover] = useState<string>('');
  const canvasRef = useRef<HTMLCanvasElement>(null);

  const zMax = world.elevationRange[1] + 300;
  const zMin = Math.min(world.seaLevel, world.elevationRange[0]) - depth;

  const cut = () => {
    if (polyline.length < 2) return;
    const w = canvasRef.current?.clientWidth ?? 600;
    requestSection(polyline, zMin, zMax, Math.min(1024, Math.max(64, Math.round(w - PAD.l - PAD.r))), 260);
  };

  // auto-refresh the section while it is shown, to follow the simulation
  useEffect(() => {
    if (polyline.length < 2 || !section) return;
    const id = window.setInterval(cut, 3000);
    return () => window.clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [polyline, section !== null, depth]);

  useEffect(() => {
    const cv = canvasRef.current;
    if (!cv) return;
    const dpr = window.devicePixelRatio || 1;
    cv.width = Math.round(cv.clientWidth * dpr);
    cv.height = Math.round(cv.clientHeight * dpr);
    const g = cv.getContext('2d')!;
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, cv.clientWidth, cv.clientHeight);
    if (section) drawSection(g, cv.clientWidth, cv.clientHeight, section, world, mode);
    else {
      g.fillStyle = '#8b949e';
      g.font = '13px system-ui';
      g.fillText(polyline.length < 2 ? 'Pick the "Section" tool and click two or more points on the map, then "Cut".' : 'Press "Cut" to sample the section.', 16, 28);
    }
  }, [section, mode, world, polyline.length]);

  const onMove = (e: React.MouseEvent<HTMLCanvasElement>) => {
    if (!section) return;
    const cv = e.currentTarget;
    const r = cv.getBoundingClientRect();
    const w = r.width - PAD.l - PAD.r;
    const h = r.height - PAD.t - PAD.b;
    const fx = (e.clientX - r.left - PAD.l) / w;
    const fy = 1 - (e.clientY - r.top - PAD.t) / h;
    if (fx < 0 || fx > 1 || fy < 0 || fy > 1) return setHover('');
    const i = Math.min(section.nu - 1, Math.floor(fx * section.nu));
    const k = Math.min(section.nz - 1, Math.floor(fy * section.nz));
    const p = k * section.nu + i;
    const z = section.meta.zMin + fy * (section.meta.zMax - section.meta.zMin);
    const mat = world.materials.find((m) => m.id === section.material[p]);
    const unit = section.meta.units.find((u) => u.id === section.unit[p]);
    const f = section.flags[p];
    setHover(
      `${((fx * section.meta.length) / 1000).toFixed(2)} km, z ${z.toFixed(0)} m · ${f & SectionFlag.Air ? 'air' : f & SectionFlag.Void ? 'void' : mat?.name ?? '?'}` +
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
        <label>
          depth
          <select value={depth} onChange={(e) => setDepth(Number(e.target.value))}>
            {[1000, 3000, 6000, 10000].map((d) => (
              <option key={d} value={d}>
                {d / 1000} km
              </option>
            ))}
          </select>
        </label>
        <span className="muted">{polyline.length} pts</span>
      </div>
      <canvas ref={canvasRef} className="section-canvas" onMouseMove={onMove} onMouseLeave={() => setHover('')} />
      <div className="section-foot">
        <span className="muted">{hover || (section ? `t = ${section.time.toFixed(0)} s` : '')}</span>
        {section && <UnitLegend section={section} world={world} />}
      </div>
    </div>
  );
}

function UnitLegend({ section, world }: { section: SectionFrame; world: WorldInfo }) {
  const items = useMemo(() => section.meta.units.slice(-8), [section]);
  return (
    <span className="legend">
      {items.map((u) => (
        <span key={u.id} className="legend-item">
          <i style={{ background: rgbCss(unitColour(world, u.id, u.depositType, u.time !== null)) }} />
          {u.label}
        </span>
      ))}
    </span>
  );
}

function unitColour(world: WorldInfo, id: number, depositType: number, recent: boolean): RGB {
  const base = hexToRgb(world.depositTypes.find((d) => d.id === depositType)?.color ?? '#777777');
  return recent ? shadeFor(base, id) : base;
}

function drawSection(g: CanvasRenderingContext2D, W: number, H: number, s: SectionFrame, world: WorldInfo, mode: SectionMode) {
  const w = W - PAD.l - PAD.r;
  const h = H - PAD.t - PAD.b;
  const img = new ImageData(s.nu, s.nz);
  const matRgb = new Map(world.materials.map((m) => [m.id, hexToRgb(m.color)]));
  const unitRgb = new Map(s.meta.units.map((u) => [u.id, unitColour(world, u.id, u.depositType, u.time !== null)]));
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
  g.drawImage(tmp, PAD.l, PAD.t, w, h);

  const X = (u: number) => PAD.l + (u / s.meta.length) * w;
  const Y = (z: number) => PAD.t + (1 - (z - s.meta.zMin) / (s.meta.zMax - s.meta.zMin)) * h;
  const colX = (i: number) => PAD.l + ((i + 0.5) / s.nu) * w;

  // isotherms (100, 300, 700 °C): mark vertical crossings
  if (mode !== 'temperature') {
    for (const [iso, col] of [
      [100, '#ffd166'],
      [300, '#f78c6b'],
      [700, '#ef476f'],
    ] as const) {
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

  // axes
  g.lineWidth = 1;
  g.strokeStyle = '#30363d';
  g.strokeRect(PAD.l, PAD.t, w, h);
  g.fillStyle = '#8b949e';
  g.font = '11px system-ui';
  const zStep = niceStep((s.meta.zMax - s.meta.zMin) / 6);
  for (let z = Math.ceil(s.meta.zMin / zStep) * zStep; z <= s.meta.zMax; z += zStep) {
    g.fillText(`${z}`, 4, Y(z) + 4);
    g.fillRect(PAD.l - 4, Y(z), 4, 1);
  }
  const uStep = niceStep(s.meta.length / 6);
  for (let u = 0; u <= s.meta.length; u += uStep) {
    g.fillText(`${(u / 1000).toFixed(uStep < 1000 ? 1 : 0)} km`, X(u) - 12, H - 8);
  }
  g.fillText('m', 4, PAD.t + 10);
}

function niceStep(x: number): number {
  const p = Math.pow(10, Math.floor(Math.log10(x)));
  const f = x / p;
  return (f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10) * p;
}
