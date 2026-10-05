import { useEffect, useState } from 'react';
import { command, send } from '../net/connection';
import type { WorldInfo } from '../protocol/messages';
import { rememberQuality, simNow, useStore, type Quality, type SurfaceColorMode, type Tool } from '../store/store';
import { formatSimTime } from '../util/world';

/** Speed slider position (0..1) ↔ multiplier 0.1..1000 on a log scale. */
const toSpeed = (f: number) => Math.pow(10, -1 + f * 4);
const toSlider = (s: number) => (Math.log10(s) + 1) / 4;

export function Transport() {
  const clock = useStore((s) => s.clock);
  const [now, setNow] = useState(0);
  const [speed, setSpeed] = useState(20);

  useEffect(() => {
    const id = window.setInterval(() => setNow(simNow()), 200);
    return () => window.clearInterval(id);
  }, []);
  useEffect(() => {
    if (clock) setSpeed(clock.speed);
  }, [clock?.speed]);

  const mode = clock?.mode ?? 'PAUSED';
  const replay = clock?.replay ?? false;
  return (
    <div className="transport">
      <span className="sim-time" title="simulation time">
        {formatSimTime(now)}
      </span>
      <button title="Play" className={mode === 'REALTIME' ? 'on' : ''} disabled={replay} onClick={() => send({ type: 'transport', mode: 'REALTIME', speed })}>
        ▶
      </button>
      <button title="Pause" className={mode === 'PAUSED' ? 'on' : ''} disabled={replay} onClick={() => send({ type: 'transport', mode: 'PAUSED' })}>
        ⏸
      </button>
      <button title="One engine step" disabled={replay} onClick={() => send({ type: 'step', steps: 1 })}>
        +1
      </button>
      <button title="Step 10 s" disabled={replay} onClick={() => send({ type: 'step', seconds: 10 })}>
        +10s
      </button>
      <button title="Step 10 min" disabled={replay} onClick={() => send({ type: 'step', seconds: 600 })}>
        +10m
      </button>
      <label className="speed" title="Simulated seconds per wall second">
        <input
          type="range"
          min={0}
          max={1}
          step={0.001}
          value={toSlider(speed)}
          disabled={replay}
          onChange={(e) => setSpeed(Number(toSpeed(Number(e.target.value)).toPrecision(2)))}
          onPointerUp={() => send({ type: 'transport', mode: mode === 'UNBOUNDED' ? 'UNBOUNDED' : 'REALTIME', speed })}
        />
        <span>{speed < 1 ? speed.toFixed(1) : speed.toFixed(0)}×</span>
      </label>
      <button title="Run as fast as possible" className={mode === 'UNBOUNDED' ? 'on' : ''} disabled={replay} onClick={() => send({ type: 'transport', mode: 'UNBOUNDED', speed })}>
        ⏩ max
      </button>
      <span className="muted" title="measured rate">
        {clock ? `${clock.rate >= 10 ? clock.rate.toFixed(0) : clock.rate.toFixed(1)}× actual` : ''}
      </span>
    </div>
  );
}

export function ReplayBar() {
  const info = useStore((s) => s.replayInfo);
  const clock = useStore((s) => s.clock);
  const events = useStore((s) => s.events);
  const [scrub, setScrub] = useState<number | null>(null);
  const replay = clock?.replay ?? false;
  if (!info) return null;
  const start = info.start;
  const end = Math.max(info.end, start + 1);
  const pos = scrub ?? clock?.time ?? end;
  const eruptions = events.filter((e) => e.kind === 'eruptionStarted');
  return (
    <div className="replay">
      <button className={replay ? 'on' : ''} onClick={() => send({ type: 'replay', action: replay ? 'exit' : 'enter' })}>
        {replay ? '⏏ back to live' : '⟲ replay'}
      </button>
      <div className="timeline">
        <input
          type="range"
          min={start}
          max={end}
          step={1}
          value={Math.min(end, Math.max(start, pos))}
          disabled={!replay}
          onChange={(e) => setScrub(Number(e.target.value))}
          onPointerUp={() => {
            if (scrub !== null) send({ type: 'seek', time: scrub });
            setScrub(null);
          }}
        />
        <div className="ticks">
          {info.keyframes.map((k) => (
            <i key={k} className="kf" style={{ left: `${((k - start) / (end - start)) * 100}%` }} />
          ))}
          {eruptions.map((e, n) => (
            <i key={n} className="er" title={`eruption at ${formatSimTime(e.time)}`} style={{ left: `${((e.time - start) / (end - start)) * 100}%` }} />
          ))}
        </div>
      </div>
      <span className="muted">
        {formatSimTime(start)} – {formatSimTime(end)}
      </span>
    </div>
  );
}

const TOOLS: [Tool, string, string][] = [
  ['orbit', '🖐', 'Navigate'],
  ['section', '✎', 'Draw cross-section line'],
  ['water', '💧', 'Pour water (click map)'],
  ['dig', '⛏', 'Dig a pit (click map)'],
];

const COLOR_MODES: [SurfaceColorMode, string][] = [
  ['natural', 'Natural'],
  ['surfaceTemperature', 'Ground temperature'],
  ['waterTable', 'Water-table depth'],
  ['topUnit', 'Surface unit'],
  ['uplift', 'Deformation'],
  ['ash', 'Ash thickness'],
  ['steam', 'Steam'],
];

export function Toolbox({ world }: { world: WorldInfo }) {
  const s = useStore();
  const volcano = s.selectedVolcano ?? world.volcanoes[0]?.id;
  const [rain, setRain] = useState(0);
  const rainNow = s.state?.world.rainMmPerHour ?? 0;
  const wind = s.state?.world.wind;
  return (
    <div className="panel toolbox">
      <div className="row">
        {TOOLS.map(([t, icon, title]) => (
          <button key={t} title={title} className={s.tool === t ? 'on' : ''} onClick={() => s.set({ tool: t })}>
            {icon}
          </button>
        ))}
        {s.tool === 'water' && (
          <label>
            m³
            <input type="number" value={s.waterVolume} min={100} step={1000} onChange={(e) => s.set({ waterVolume: Number(e.target.value) })} />
          </label>
        )}
        {s.tool === 'dig' && (
          <>
            <label>
              r
              <input type="number" value={s.digRadius} min={5} step={5} onChange={(e) => s.set({ digRadius: Number(e.target.value) })} />
            </label>
            <label>
              depth
              <input type="number" value={s.digDepth} min={1} step={5} onChange={(e) => s.set({ digDepth: Number(e.target.value) })} />
            </label>
          </>
        )}
      </div>
      <div className="row">
        <span className="muted">volcano</span>
        <button onClick={() => volcano && command({ kind: 'startEruption', volcanoId: volcano })}>🌋 erupt</button>
        <button onClick={() => volcano && command({ kind: 'stopEruption', volcanoId: volcano })}>⏹ stop</button>
        <button onClick={() => volcano && command({ kind: 'forceDike', volcanoId: volcano })}>⤴ dike</button>
        <button onClick={() => volcano && command({ kind: 'injectMagma', volcanoId: volcano, volumeM3: 5e6 })}>＋ magma</button>
        <span className="spacer" />
        <button className="toggle" title={s.toolboxOpen ? 'Fewer controls' : 'More controls'} onClick={() => s.set({ toolboxOpen: !s.toolboxOpen })}>
          {s.toolboxOpen ? '▴' : '▾'}
        </button>
      </div>
      {s.toolboxOpen && (
      <>
      <div className="row">
        <span className="muted">rain</span>
        <input type="range" min={0} max={100} value={rain} onChange={(e) => setRain(Number(e.target.value))} onPointerUp={() => command({ kind: 'rain', mmPerHour: rain })} />
        <span>{rainNow.toFixed(0)} mm/h</span>
        {wind && (
          <span className="muted">
            wind {wind.speed.toFixed(0)} m/s → {wind.bearingDeg.toFixed(0)}°
          </span>
        )}
      </div>
      <div className="row">
        <span className="muted">surface</span>
        <select value={s.colorMode} onChange={(e) => s.set({ colorMode: e.target.value as SurfaceColorMode })}>
          {COLOR_MODES.map(([m, l]) => (
            <option key={m} value={m}>
              {l}
            </option>
          ))}
        </select>
        <label title="vertical exaggeration">
          z×
          <input type="range" min={1} max={4} step={0.1} value={s.verticalExaggeration} onChange={(e) => s.set({ verticalExaggeration: Number(e.target.value) })} />
          {s.verticalExaggeration.toFixed(1)}
        </label>
        <label title="deformation exaggeration">
          def×
          <input type="range" min={0} max={4} step={0.1} value={Math.log10(s.deformationExaggeration)} onChange={(e) => s.set({ deformationExaggeration: Math.pow(10, Number(e.target.value)) })} />
          {s.deformationExaggeration.toFixed(0)}
        </label>
      </div>
      <div className="row">
        {(
          [
            ['showHypocentres', 'quakes'],
            ['showChambers', 'chambers'],
            ['showAtmosphere', 'atmosphere'],
            ['showFeatures', 'features'],
          ] as const
        ).map(([k, label]) => (
          <label key={k} className="check">
            <input type="checkbox" checked={s[k]} onChange={(e) => s.set({ [k]: e.target.checked })} />
            {label}
          </label>
        ))}
      </div>
      <div className="row">
        <span className="muted">quality</span>
        <select
          value={s.quality}
          onChange={(e) => {
            const q = e.target.value as Quality;
            rememberQuality(q);
            s.set({ quality: q });
          }}
        >
          <option value="low">low (integrated GPU)</option>
          <option value="medium">medium</option>
          <option value="high">high (shadows)</option>
        </select>
        <label className="check" title="smooth the block-stepped elevations for display">
          <input type="checkbox" checked={s.smoothTerrain} onChange={(e) => s.set({ smoothTerrain: e.target.checked })} />
          smooth terrain
        </label>
      </div>
      </>
      )}
    </div>
  );
}
