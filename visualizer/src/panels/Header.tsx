import { useEffect, useRef, useState } from 'react';
import { attachSession, send } from '../net/connection';
import type { SessionInfo } from '../protocol/messages';
import { simNow, useStore, type DrawerTab } from '../store/store';
import { formatDuration, formatFactor, formatSimTime } from '../util/world';

/** Playback speeds offered in the menu (simulated seconds per real second). */
const SPEEDS = [0.5, 1, 5, 20, 100, 1000];

/** Drawer pages reachable from the header, in order. */
export const DRAWER_TABS: { tab: DrawerTab; label: string; title: string }[] = [
  { tab: 'sims', label: 'Worlds', title: 'Start, open, switch, pause and close simulated worlds' },
  { tab: 'monitor', label: 'Monitor', title: 'Instruments: earthquakes, magma, ground motion, status history' },
  { tab: 'events', label: 'Events', title: 'What happened, newest first; click an event to replay from it' },
  { tab: 'section', label: 'Section', title: 'Cut the ground along a line to see layers, heat and water' },
  { tab: 'tune', label: 'Settings', title: 'Weather, magma supply, time scale and every other setting of this world' },
  { tab: 'view', label: 'View', title: 'What to show on the map, graphics quality, camera tools' },
];

/** What to call a session: its world folder (what the user named it), else its preset title. */
export function sessionLabel(s: SessionInfo): string {
  return s.world ?? s.name;
}

/** The current world's name with a dropdown of the other loaded worlds. */
export function WorldSwitcher() {
  const sessions = useStore((s) => s.sessions);
  const sessionId = useStore((s) => s.sessionId);
  const world = useStore((s) => s.world);
  const openDrawer = useStore((s) => s.openDrawer);
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    window.addEventListener('mousedown', close);
    return () => window.removeEventListener('mousedown', close);
  }, [open]);
  const current = sessions.find((x) => x.id === sessionId);
  return (
    <div className="world-switch" ref={ref}>
      <button className="world-btn" aria-haspopup="menu" aria-expanded={open} title="Switch between the worlds this server is running" onClick={() => setOpen(!open)}>
        <span className="world-name" title={current?.name}>{current ? sessionLabel(current) : world?.name ?? 'No world open'}</span>
        <span aria-hidden>▾</span>
      </button>
      {open && (
        <div className="menu" role="menu">
          {sessions.map((x) => {
            const erupting = x.volcanoes?.some((v) => v.erupting);
            return (
              <button
                key={x.id}
                role="menuitem"
                className={x.id === sessionId ? 'on' : ''}
                onClick={() => {
                  attachSession(x.id);
                  setOpen(false);
                }}
              >
                <span className={`dot ${erupting ? 'hot' : x.mode === 'PAUSED' ? 'paused' : 'live'}`} aria-hidden />
                <span className="grow" title={x.name}>{sessionLabel(x)}</span>
                <span className="muted small">
                  {x.mode === 'PAUSED' ? 'paused' : erupting ? 'erupting' : 'running'} · {formatSimTime(x.time)}
                </span>
              </button>
            );
          })}
          {sessions.length === 0 && <div className="muted pad">No worlds are running.</div>}
          <button
            role="menuitem"
            className="menu-foot"
            onClick={() => {
              openDrawer('sims');
              setOpen(false);
            }}
          >
            ＋ Start or open a world…
          </button>
        </div>
      )}
    </div>
  );
}

/** Simulation clock with the volcano time it stands for. */
export function Clock() {
  const clock = useStore((s) => s.clock);
  const [now, setNow] = useState(0);
  useEffect(() => {
    const id = window.setInterval(() => setNow(simNow()), 250);
    return () => window.clearInterval(id);
  }, []);
  const c = clock?.compression;
  const physical = clock?.physicalTime;
  const title =
    'Simulated time: how long the simulation has run.\n' +
    (c
      ? `Volcano time runs ${formatFactor(c)} faster than simulated time right now (time compression), so slow volcanic processes fit into a session.`
      : '') +
    '\nPlayback speed (next to the play button) only changes how fast you watch it.';
  return (
    <div className="clock" title={title}>
      <span className="sim-time">{formatSimTime(now)}</span>
      {c !== undefined && (
        <span className="muted small">
          volcano time {formatFactor(c)}
          {physical !== undefined ? ` · ≈ ${formatDuration(physical)}` : ''}
        </span>
      )}
      {clock?.replay && <span className="replay-tag">REPLAY</span>}
    </div>
  );
}

/** Play/pause, playback speed and stepping. */
export function Playback() {
  const clock = useStore((s) => s.clock);
  const mode = clock?.mode ?? 'PAUSED';
  const replay = clock?.replay ?? false;
  const speed = clock?.speed ?? 20;
  const playing = mode !== 'PAUSED' && !replay;
  const [stepOpen, setStepOpen] = useState(false);
  const canReplay = useStore((s) => (s.replayInfo?.keyframes.length ?? 0) > 0);
  const speedValue = mode === 'UNBOUNDED' ? 'max' : String(SPEEDS.reduce((a, b) => (Math.abs(b - speed) < Math.abs(a - speed) ? b : a)));
  return (
    <div className="playback">
      <button
        className={`play ${playing ? 'on' : ''}`}
        disabled={replay}
        title={playing ? 'Pause [K]' : 'Play [K]'}
        aria-label={playing ? 'Pause' : 'Play'}
        onClick={() => send(playing ? { type: 'transport', mode: 'PAUSED' } : { type: 'transport', mode: 'REALTIME', speed })}
      >
        {playing ? '❚❚ Pause' : '▶ Play'}
      </button>
      <label className="speed-select" title="Playback speed: simulated seconds per real second (does not change the physics)">
        <select
          value={speedValue}
          disabled={replay}
          aria-label="Playback speed"
          onChange={(e) => {
            const v = e.target.value;
            if (v === 'max') send({ type: 'transport', mode: 'UNBOUNDED', speed });
            else send({ type: 'transport', mode: mode === 'PAUSED' ? 'PAUSED' : 'REALTIME', speed: Number(v) });
          }}
        >
          {SPEEDS.map((s) => (
            <option key={s} value={String(s)}>
              {s}× speed
            </option>
          ))}
          <option value="max" title="As fast as the computer can">max speed</option>
        </select>
      </label>
      <div className="step-menu">
        <button disabled={replay} title="Step forward by a fixed time, or rewind to watch again" aria-haspopup="menu" aria-expanded={stepOpen} onClick={() => setStepOpen(!stepOpen)}>
          Step ▾
        </button>
        {stepOpen && (
          <div className="menu" role="menu" onMouseLeave={() => setStepOpen(false)}>
            {(
              [
                ['One step', { steps: 1 }],
                ['10 seconds', { seconds: 10 }],
                ['10 minutes', { seconds: 600 }],
                ['1 hour', { seconds: 3600 }],
              ] as const
            ).map(([label, arg]) => (
              <button
                key={label}
                role="menuitem"
                onClick={() => {
                  send({ type: 'step', ...arg });
                  setStepOpen(false);
                }}
              >
                + {label}
              </button>
            ))}
            <button
              role="menuitem"
              className="menu-foot"
              disabled={!canReplay}
              title="Rewind and watch what already happened; the simulation waits meanwhile"
              onClick={() => {
                send({ type: 'replay', action: 'enter' });
                setStepOpen(false);
              }}
            >
              ⟲ Watch again (replay)
            </button>
          </div>
        )}
      </div>
      {clock && mode !== 'PAUSED' && !replay && clock.rate > 0 && Math.abs(clock.rate - speed) / speed > 0.3 && (
        <span className="muted small" title="The computer cannot keep up with the requested speed">
          (running {clock.rate >= 10 ? clock.rate.toFixed(0) : clock.rate.toFixed(1)}×)
        </span>
      )}
    </div>
  );
}

/** Labelled buttons that open drawer pages. */
export function DrawerButtons() {
  const drawer = useStore((s) => s.drawer);
  const openDrawer = useStore((s) => s.openDrawer);
  return (
    <nav className="drawer-buttons" aria-label="Panels">
      {DRAWER_TABS.map((d) => (
        <button key={d.tab} className={drawer === d.tab ? 'on' : ''} aria-pressed={drawer === d.tab} title={d.title} onClick={() => openDrawer(d.tab)}>
          {d.label}
        </button>
      ))}
    </nav>
  );
}
