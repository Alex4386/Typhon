import { useEffect, useRef, useState } from 'react';
import './camera.css';
import { Field } from '../protocol/fields';
import type { WorldInfo } from '../protocol/messages';
import { useStore } from '../store/store';
import { HYPSO, ramp, type RGB } from '../util/color';
import { sampleColumn, worldExtent } from '../util/world';
import { saveBookmarks, useCamera } from './cameraStore';
import { CAMERA_MODES, compassPoint, decodePose, FOLLOW_TARGETS, type CameraMode, type FollowTarget } from './math';
import { builtinBookmarks, type SceneInfo } from './targets';

const MODE_LABEL: Record<CameraMode, { label: string; key: string; title: string }> = {
  orbit: { label: 'Orbit', key: '1', title: 'Orbit around a point: drag to rotate, right-drag to pan, wheel zooms to the cursor' },
  fly: { label: 'Fly', key: '2', title: 'Free flight: WASD + Q/E, mouse look (click to capture), wheel = speed' },
  walk: { label: 'Walk', key: '3', title: 'Walk on the ground at eye height: WASD, Space jumps' },
  follow: { label: 'Follow', key: '4', title: 'Track a target with damped motion; orbit around it as usual' },
  tour: { label: 'Tour', key: '5', title: 'Slow automatic fly-around; any input stops it' },
};

const FOLLOW_LABEL: Record<FollowTarget, string> = {
  vent: 'vent',
  lavaFront: 'lava front',
  plumeTop: 'plume top',
  volcano: 'volcano',
};

function sceneInfo(world: WorldInfo): SceneInfo {
  const s = useStore.getState();
  const r = useCamera.getState().readout;
  return {
    world,
    state: s.state,
    volcanoId: s.selectedVolcano,
    vExag: s.verticalExaggeration,
    dExag: s.deformationExaggeration,
    sectionPolyline: s.sectionPolyline,
    fov: r?.fov ?? 38,
    aspect: r?.aspect ?? 1.6,
  };
}

/** Camera toolbar: modes, follow target, framing, bookmarks, settings and help. */
export function CameraBar({ world }: { world: WorldInfo }) {
  const mode = useCamera((c) => c.mode);
  const followTarget = useCamera((c) => c.followTarget);
  const speed = useCamera((c) => c.speedMultiplier);
  const clearance = useCamera((c) => c.clearance);
  const underground = useCamera((c) => c.allowUnderground);
  const bookmarks = useCamera((c) => c.bookmarks);
  const locked = useCamera((c) => c.pointerLocked);
  const set = useCamera((c) => c.set);
  const req = useCamera((c) => c.requestCamera);
  const hasPlume = useStore((s) => Object.values(s.state?.volcanoes ?? {}).some((v) => !!v.plume && v.plume.topZ > 0));
  const hasSection = useStore((s) => s.sectionPolyline.length >= 2);
  const [menu, setMenu] = useState(false);

  const builtins = builtinBookmarks(sceneInfo(world));
  const savePose = () => {
    const r = useCamera.getState().readout;
    if (!r) return;
    const name = window.prompt('Bookmark name', `View ${bookmarks.length + 1}`);
    if (!name) return;
    const url = new URL(window.location.href).searchParams.get('cam');
    const pose = decodePose(url);
    if (!pose) return;
    const list = [...bookmarks.filter((b) => b.name !== name), { name, pose }];
    set({ bookmarks: list });
    saveBookmarks(world.name, list);
  };
  const removeBookmark = (name: string) => {
    const list = bookmarks.filter((b) => b.name !== name);
    set({ bookmarks: list });
    saveBookmarks(world.name, list);
  };

  return (
    <div className="panel camerabar">
      <div className="row">
        {CAMERA_MODES.map((m) => (
          <button key={m} className={mode === m ? 'on' : ''} title={`${MODE_LABEL[m].title} [${MODE_LABEL[m].key}]`} onClick={() => req({ kind: 'mode', mode: m })}>
            {MODE_LABEL[m].label}
          </button>
        ))}
        {mode === 'follow' && (
          <select value={followTarget} onChange={(e) => set({ followTarget: e.target.value as FollowTarget })} title="Follow target">
            {FOLLOW_TARGETS.map((t) => (
              <option key={t} value={t}>
                {FOLLOW_LABEL[t]}
              </option>
            ))}
          </select>
        )}
        <span className="sep" />
        <button title="Frame the selected volcano [F]" onClick={() => req({ kind: 'frame', what: 'volcano' })}>
          ⌖ volcano
        </button>
        <button title="Fit the eruption column [P]" disabled={!hasPlume} onClick={() => req({ kind: 'frame', what: 'plume' })}>
          ⇡ plume
        </button>
        <button title="Whole world [O]" onClick={() => req({ kind: 'frame', what: 'overview' })}>
          ▦ overview
        </button>
        <button title="Look across the cross-section line" disabled={!hasSection} onClick={() => req({ kind: 'frame', what: 'section' })}>
          ⟂ section
        </button>
        <span className="sep" />
        <select
          value=""
          title="Camera bookmarks"
          onChange={(e) => {
            const v = e.target.value;
            if (v === '+') savePose();
            else if (v.startsWith('-')) removeBookmark(v.slice(1));
            else {
              const b = [...builtins, ...bookmarks].find((x) => x.name === v);
              if (b) req({ kind: 'pose', pose: b.pose });
            }
          }}
        >
          <option value="">★ views</option>
          <optgroup label="Built in">
            {builtins.map((b) => (
              <option key={`b-${b.name}`} value={b.name}>
                {b.name}
              </option>
            ))}
          </optgroup>
          {bookmarks.length > 0 && (
            <optgroup label="Saved">
              {bookmarks.map((b) => (
                <option key={`u-${b.name}`} value={b.name}>
                  {b.name}
                </option>
              ))}
            </optgroup>
          )}
          <option value="+">＋ save current view…</option>
          {bookmarks.length > 0 && (
            <optgroup label="Delete">
              {bookmarks.map((b) => (
                <option key={`d-${b.name}`} value={`-${b.name}`}>
                  ✕ {b.name}
                </option>
              ))}
            </optgroup>
          )}
        </select>
        <button className={menu ? 'on' : ''} title="Camera settings" onClick={() => setMenu(!menu)}>
          ⚙
        </button>
        <button title="Keyboard and mouse controls [?]" onClick={() => set({ helpOpen: true })}>
          ?
        </button>
      </div>
      {menu && (
        <div className="row">
          <label title="Fly speed multiplier (wheel or [ ] while flying)">
            speed ×
            <input type="range" min={-3} max={3} step={0.1} value={Math.log2(speed)} onChange={(e) => set({ speedMultiplier: 2 ** Number(e.target.value) })} />
            <span className="num">{speed.toFixed(2)}</span>
          </label>
          <label title="Minimum height above the ground while flying (m)">
            clearance
            <input type="number" min={0} max={500} step={1} value={clearance} onChange={(e) => set({ clearance: Math.max(0, Number(e.target.value) || 0) })} style={{ width: 64 }} />
            m
          </label>
          <label title="Allow flying below the surface to look at the chamber and subsurface [G]">
            <input type="checkbox" checked={underground} onChange={(e) => set({ allowUnderground: e.target.checked })} />
            underground
          </label>
        </div>
      )}
      {(mode === 'fly' || mode === 'walk') && (
        <div className="camera-hint">
          {locked ? 'Esc releases the mouse · ' : 'Click the view to capture the mouse (or drag to look) · '}
          WASD move{mode === 'fly' ? ' · Q/E down/up · Shift fast · Ctrl/Alt slow · wheel speed' : ' · Space jump · Shift run'}
        </div>
      )}
    </div>
  );
}

/** Compass rose and position readout. */
export function CameraReadoutPanel() {
  const r = useCamera((c) => c.readout);
  const mode = useCamera((c) => c.mode);
  if (!r) return null;
  const deg = (r.heading * 180) / Math.PI;
  return (
    <div className="panel compass" title="Camera position (world metres)">
      <div className="rose" onClick={() => useCamera.getState().requestCamera({ kind: 'frame', what: 'volcano' })} title="Heading (click: frame volcano)">
        <div className="needle" style={{ transform: `rotate(${-deg}deg)` }}>
          <span className="n">N</span>
        </div>
      </div>
      <div className="readout">
        <div>
          <b>{compassPoint(r.heading)}</b> {deg.toFixed(0)}° · pitch {((r.pitch * 180) / Math.PI).toFixed(0)}° · <span className="muted">{mode}</span>
        </div>
        <div>
          E {fmtM(r.x)} · N {fmtM(r.y)}
        </div>
        <div>
          alt {fmtM(r.altitude)} · AGL {fmtM(r.aboveGround)}
          {r.speed > 0 ? ` · ${fmtSpeed(r.speed)}` : ''}
        </div>
      </div>
    </div>
  );
}

function fmtM(v: number): string {
  const a = Math.abs(v);
  return a >= 10000 ? `${(v / 1000).toFixed(1)} km` : `${v.toFixed(0)} m`;
}

function fmtSpeed(v: number): string {
  return v >= 1000 ? `${(v / 1000).toFixed(1)} km/s` : `${v.toFixed(v < 10 ? 1 : 0)} m/s`;
}

const MAP_PX = 168;

/** Minimap of the world elevation with vents, the section line and the camera frustum; click to fly there. */
export function Minimap({ world }: { world: WorldInfo }) {
  const base = useRef<HTMLCanvasElement>(null);
  const overlay = useRef<HTMLCanvasElement>(null);
  const tileRevision = useStore((s) => s.tileRevision);
  const readout = useCamera((c) => c.readout);
  const section = useStore((s) => s.sectionPolyline);
  const [open, setOpen] = useState(true);
  const lastDraw = useRef(0);
  const pending = useRef<number | null>(null);
  const ext = worldExtent(world);
  const w = ext.maxX - ext.minX;
  const h = ext.maxY - ext.minY;
  const scale = MAP_PX / Math.max(w, h);
  const toPx = (x: number, y: number): [number, number] => [(x - ext.minX) * scale, MAP_PX - (y - ext.minY) * scale];

  // base layer: elevation shading, redrawn at most every 2 s as tiles stream in
  useEffect(() => {
    if (!open) return;
    const draw = () => {
      pending.current = null;
      lastDraw.current = performance.now();
      const c = base.current;
      if (!c) return;
      const g = c.getContext('2d');
      if (!g) return;
      const img = g.createImageData(MAP_PX, MAP_PX);
      const [lo, hi] = world.elevationRange;
      const rgb: RGB = [0, 0, 0];
      for (let py = 0; py < MAP_PX; py++) {
        for (let px = 0; px < MAP_PX; px++) {
          const x = ext.minX + (px + 0.5) / scale;
          const y = ext.minY + (MAP_PX - py - 0.5) / scale;
          const i = Math.floor((x - world.origin[0]) / world.cellSize);
          const j = Math.floor((y - world.origin[1]) / world.cellSize);
          const e = sampleColumn(world, Field.SurfaceElevation, i, j, Number.NaN);
          const k = (py * MAP_PX + px) * 4;
          if (!Number.isFinite(e)) {
            img.data[k + 3] = 0;
            continue;
          }
          const water = sampleColumn(world, Field.WaterDepth, i, j, 0);
          const lava = sampleColumn(world, Field.LavaDepth, i, j, 0);
          if (lava > 0.1) rgb.splice(0, 3, 1, 0.43, 0.12);
          else if (water > 0.3 || e < world.seaLevel) rgb.splice(0, 3, 0.16, 0.35, 0.55);
          else ramp(HYPSO, (e - lo) / Math.max(1, hi - lo), rgb);
          // light hillshade from the west-east gradient
          const e2 = sampleColumn(world, Field.SurfaceElevation, i + 1, j, e);
          const shade = Math.max(0.55, Math.min(1.25, 1 - ((e2 - e) / world.cellSize) * 0.8));
          img.data[k] = Math.min(255, rgb[0] * 255 * shade);
          img.data[k + 1] = Math.min(255, rgb[1] * 255 * shade);
          img.data[k + 2] = Math.min(255, rgb[2] * 255 * shade);
          img.data[k + 3] = 255;
        }
      }
      g.putImageData(img, 0, 0);
    };
    const since = performance.now() - lastDraw.current;
    if (since > 2000) draw();
    else if (pending.current == null) pending.current = window.setTimeout(draw, 2000 - since);
    return () => {
      if (pending.current != null) {
        window.clearTimeout(pending.current);
        pending.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tileRevision, open, world]);

  // overlay: vents, section line, camera and frustum
  useEffect(() => {
    if (!open) return;
    const c = overlay.current;
    const g = c?.getContext('2d');
    if (!c || !g) return;
    g.clearRect(0, 0, MAP_PX, MAP_PX);
    if (section.length >= 2) {
      g.strokeStyle = '#ffd36e';
      g.lineWidth = 1.5;
      g.beginPath();
      section.forEach(([x, y], k) => {
        const [px, py] = toPx(x, y);
        if (k === 0) g.moveTo(px, py);
        else g.lineTo(px, py);
      });
      g.stroke();
    }
    for (const v of world.volcanoes) {
      for (const vent of v.vents) {
        const [px, py] = toPx(vent.at[0], vent.at[1]);
        g.fillStyle = '#ff4d2e';
        g.beginPath();
        g.arc(px, py, 2.5, 0, Math.PI * 2);
        g.fill();
      }
    }
    if (readout) {
      const [px, py] = toPx(readout.x, readout.y);
      const hfov = Math.atan(Math.tan(((readout.fov / 2) * Math.PI) / 180) * readout.aspect);
      const len = 26;
      // screen y is flipped (north up): bearing θ → (sin θ, −cos θ)
      const a1 = readout.heading - hfov;
      const a2 = readout.heading + hfov;
      g.fillStyle = 'rgba(255, 255, 255, 0.22)';
      g.strokeStyle = 'rgba(255, 255, 255, 0.85)';
      g.lineWidth = 1;
      g.beginPath();
      g.moveTo(px, py);
      g.lineTo(px + Math.sin(a1) * len, py - Math.cos(a1) * len);
      g.lineTo(px + Math.sin(a2) * len, py - Math.cos(a2) * len);
      g.closePath();
      g.fill();
      g.stroke();
      g.fillStyle = '#4fc3f7';
      g.beginPath();
      g.arc(px, py, 3, 0, Math.PI * 2);
      g.fill();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [readout, section, open, world]);

  if (!open) {
    return (
      <button className="minimap-toggle" title="Show minimap" onClick={() => setOpen(true)}>
        ▣
      </button>
    );
  }
  return (
    <div className="panel minimap" title="Click to fly there">
      <div className="minimap-canvas" style={{ width: MAP_PX, height: MAP_PX }}>
        <canvas ref={base} width={MAP_PX} height={MAP_PX} />
        <canvas
          ref={overlay}
          width={MAP_PX}
          height={MAP_PX}
          onClick={(e) => {
            const rect = (e.target as HTMLCanvasElement).getBoundingClientRect();
            const x = ext.minX + (e.clientX - rect.left) / scale;
            const y = ext.minY + (MAP_PX - (e.clientY - rect.top)) / scale;
            useCamera.getState().requestCamera({ kind: 'flyTo', at: [x, y] });
          }}
        />
      </div>
      <button className="minimap-close" title="Hide minimap" onClick={() => setOpen(false)}>
        ×
      </button>
    </div>
  );
}

const HELP: [string, string][] = [
  ['1 – 5', 'Orbit · Fly · Walk · Follow · Tour'],
  ['F / Shift+F', 'Frame the selected volcano'],
  ['P', 'Fit the eruption column (plume)'],
  ['O', 'Overview of the whole world'],
  ['Double-click', 'Focus on a point of the terrain'],
  ['Minimap click', 'Fly to that place'],
  ['Orbit: drag', 'Rotate · right-drag / two fingers pan · wheel / pinch zooms to the cursor'],
  ['W A S D', 'Move (fly / walk; gamepad left stick)'],
  ['Q / E, Space', 'Down / up (fly) · Space jumps (walk)'],
  ['Mouse', 'Click the view to capture the mouse and look around; Esc releases (drag also looks)'],
  ['Arrows', 'Look around without a mouse'],
  ['Shift / Ctrl, Alt', 'Faster / slower'],
  ['Wheel, [ ]', 'Fly speed'],
  ['G', 'Toggle flying underground'],
  ['Gamepad', 'Left stick move · right stick look · triggers down/up · A boost'],
  ['?', 'This help · Esc closes'],
];

export function CameraHelp() {
  const open = useCamera((c) => c.helpOpen);
  if (!open) return null;
  return (
    <div className="help-overlay" onClick={() => useCamera.getState().set({ helpOpen: false })}>
      <div className="panel help" onClick={(e) => e.stopPropagation()}>
        <div className="row">
          <b>Camera controls</b>
          <span className="spacer" />
          <button onClick={() => useCamera.getState().set({ helpOpen: false })}>×</button>
        </div>
        <table>
          <tbody>
            {HELP.map(([k, v]) => (
              <tr key={k}>
                <td>
                  <kbd>{k}</kbd>
                </td>
                <td>{v}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <div className="muted">The view is kept in the URL (?cam=…), so copying the address shares exactly this view.</div>
      </div>
    </div>
  );
}
