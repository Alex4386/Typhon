import { useEffect, useRef } from 'react';
import { useCamera } from '../camera/cameraStore';
import { send } from '../net/connection';
import type { WorldInfo } from '../protocol/messages';
import { rememberDrawerWidth, rememberGuideSeen, useStore } from '../store/store';
import { EventLog } from './EventLog';
import { DRAWER_TABS } from './Header';
import { Observatory } from './Observatory';
import { Params } from './Params';
import { SectionPanel } from './SectionPanel';
import { SessionManager } from './SessionManager';
import { ViewSettings } from './ViewSettings';

/** The single side drawer: one page at a time, resizable by its left edge. */
export function Drawer({ world }: { world: WorldInfo | null }) {
  const drawer = useStore((s) => s.drawer);
  const width = useStore((s) => s.drawerWidth);
  const set = useStore((s) => s.set);
  const drag = useRef<{ x: number; w: number } | null>(null);
  if (!drawer) return null;
  const tab = DRAWER_TABS.find((d) => d.tab === drawer);
  const needsWorld = drawer !== 'sims' && drawer !== 'view';
  const resize = (w: number) => set({ drawerWidth: Math.min(Math.max(300, w), Math.max(320, window.innerWidth - 240)) });
  return (
    <aside className="drawer" style={{ width }} aria-label={tab?.label}>
      <div
        className="drawer-grip"
        role="separator"
        aria-orientation="vertical"
        aria-label="Resize panel"
        tabIndex={0}
        title="Drag to resize"
        onPointerDown={(e) => {
          drag.current = { x: e.clientX, w: width };
          (e.target as HTMLElement).setPointerCapture(e.pointerId);
        }}
        onPointerMove={(e) => {
          if (drag.current) resize(drag.current.w + drag.current.x - e.clientX);
        }}
        onPointerUp={() => {
          drag.current = null;
          rememberDrawerWidth(useStore.getState().drawerWidth);
        }}
        onKeyDown={(e) => {
          if (e.key === 'ArrowLeft') resize(width + 40);
          if (e.key === 'ArrowRight') resize(width - 40);
          rememberDrawerWidth(useStore.getState().drawerWidth);
        }}
      />
      <div className="drawer-head">
        <h2>{tab?.label}</h2>
        <span className="muted small grow">{tab?.title}</span>
        <button className="icon" aria-label="Close panel" title="Close [Esc]" onClick={() => set({ drawer: null })}>
          ×
        </button>
      </div>
      <div className={`drawer-body drawer-${drawer}`}>
        {needsWorld && !world && <p className="muted">Open a world first (Worlds).</p>}
        {drawer === 'sims' && <SessionManager />}
        {drawer === 'view' && <ViewSettings />}
        {world && drawer === 'monitor' && <Observatory world={world} />}
        {world && drawer === 'events' && <EventLog />}
        {world && drawer === 'section' && <SectionPanel world={world} />}
        {world && drawer === 'tune' && <Params />}
      </div>
    </aside>
  );
}

/** Pop-up notifications (eruptions, big quakes, errors), top centre. */
export function Toasts() {
  const toasts = useStore((s) => s.toasts);
  const dismiss = useStore((s) => s.dismissToast);
  const openDrawer = useStore((s) => s.openDrawer);
  return (
    <div className="toasts" role="log" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className={`toast toast-${t.tone}`}>
          <span className="grow">{t.text}</span>
          {t.tone !== 'info' && (
            <button className="link" onClick={() => useStore.getState().drawer !== 'events' && openDrawer('events')}>
              Events
            </button>
          )}
          <button className="icon" aria-label="Dismiss" onClick={() => dismiss(t.id)}>
            ×
          </button>
        </div>
      ))}
    </div>
  );
}

const SHORTCUTS: [string, string][] = [
  ['K', 'Play / pause'],
  ['.', 'Step forward once (while paused)'],
  ['Esc', 'Stop the current map tool, close the panel'],
  ['?', 'Camera keys and mouse controls'],
  ['1 / 2 / 3', 'Camera: orbit, fly, walk'],
  ['F', 'Look at the volcano'],
];

/** First-run guide; reopened from the header's “?”. */
export function Guide() {
  const open = useStore((s) => s.guideOpen);
  const set = useStore((s) => s.set);
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    if (open) ref.current?.showModal?.();
  }, [open]);
  if (!open) return null;
  const close = () => {
    rememberGuideSeen();
    set({ guideOpen: false });
  };
  return (
    <dialog ref={ref} className="panel dialog guide" aria-labelledby="guide-title" onClose={close} onCancel={close}>
      <h2 id="guide-title">Welcome to Typhon</h2>
      <p>A volcano simulator: magma, earthquakes, lava, ash and groundwater, computed live.</p>
      <ol>
        <li>
          <b>Look around</b>: drag to rotate, right-drag to pan, scroll to zoom. Double-click the ground to focus there.
        </li>
        <li>
          <b>Play</b> the simulation with ▶ and choose how fast to watch it. <i>Speed</i> only changes how fast you watch; the physics stays the same.
        </li>
        <li>
          <b>The card at the top left</b> tells you what the volcano is doing. Big events pop up at the top.
        </li>
        <li>
          <b>Make things happen</b> with the buttons at the bottom left: start an eruption or add magma of your choosing.
        </li>
        <li>
          <b>More</b> lives behind the buttons at the top right: Worlds (run several), Monitor (instruments), Events (click one to replay it), Cross-section, Parameters and View.
        </li>
      </ol>
      <p className="muted small">
        <b>Time:</b> the clock shows <i>simulated time</i>. Volcanoes are slow, so volcano processes run faster than that (time compression, e.g. ×5 000 while quiet); the clock's second line
        estimates the volcano time that has passed.
      </p>
      <table className="shortcuts">
        <tbody>
          {SHORTCUTS.map(([k, v]) => (
            <tr key={k}>
              <td>
                <kbd>{k}</kbd>
              </td>
              <td>{v}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="dialog-actions">
        <button
          onClick={() => {
            close();
            useCamera.getState().set({ helpOpen: true });
          }}
        >
          All camera controls
        </button>
        <button className="primary" autoFocus onClick={close}>
          Got it
        </button>
      </div>
    </dialog>
  );
}

/** Global keys that do not clash with the camera (WASD, QE, 1–5, F, P, O, G, [ ], arrows, Space). */
export function useGlobalKeys(): void {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      if (t && (t.tagName === 'INPUT' || t.tagName === 'SELECT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      const s = useStore.getState();
      const clock = s.clock;
      if (e.key === 'k' || e.key === 'K') {
        if (!clock || clock.replay) return;
        send(clock.mode === 'PAUSED' ? { type: 'transport', mode: 'REALTIME', speed: clock.speed } : { type: 'transport', mode: 'PAUSED' });
      } else if (e.key === '.') {
        if (clock && !clock.replay) send({ type: 'step', steps: 1 });
      } else if (e.key === 'Escape') {
        if (s.tool === 'orbit' && s.drawer && !useCamera.getState().helpOpen && !document.pointerLockElement) s.set({ drawer: null });
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
}
