import { useEffect } from 'react';
import { BuildPanel } from './BuildPanel';
import { redoBuild, removeChamber, removeConnection, undoBuild } from './buildActions';
import { X } from 'lucide-react';
import { Tip } from '@/components/tip';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Kbd } from '@/components/ui/kbd';
import { Table, TableBody, TableCell, TableRow } from '@/components/ui/table';
import { useCamera } from '../camera/cameraStore';
import { send } from '../net/connection';
import type { WorldInfo } from '../protocol/messages';
import { rememberGuideSeen, useStore } from '../store/store';
import { EntitiesPanel } from './EntitiesPanel';
import { EventLog } from './EventLog';
import { DRAWER_TABS } from './Header';
import { Observatory } from './Observatory';
import { Params } from './Params';
import { SectionPanel } from './SectionPanel';
import { SessionManager } from './SessionManager';
import { ViewSettings } from './ViewSettings';

/** The side panel: one page at a time (the header buttons pick it); resized by its left edge. */
export function SidePanel({ world }: { world: WorldInfo | null }) {
  const drawer = useStore((s) => s.drawer);
  const set = useStore((s) => s.set);
  if (!drawer) return null;
  const tab = DRAWER_TABS.find((d) => d.tab === drawer);
  const needsWorld = drawer !== 'sims' && drawer !== 'view';
  return (
    <aside className="flex h-full flex-col bg-card" aria-label={tab?.label}>
      <div className="flex items-start gap-2 border-b px-4 py-3">
        {tab && <tab.icon className="mt-0.5 size-4 text-muted-foreground" />}
        <div className="min-w-0 flex-1">
          <h2 className="font-semibold">{tab?.label}</h2>
          <p className="text-xs text-muted-foreground">{tab?.title}</p>
        </div>
        <Tip content={<span>Close <Kbd>Esc</Kbd></span>}>
          <Button variant="ghost" size="icon-sm" aria-label="Close panel" onClick={() => set({ drawer: null })}>
            <X />
          </Button>
        </Tip>
      </div>
      <div className={`@container min-h-0 flex-1 overflow-y-auto p-4 drawer-${drawer}`}>
        {needsWorld && !world && <p className="text-sm text-muted-foreground">Open a world first (Worlds).</p>}
        {drawer === 'sims' && <SessionManager />}
        {world && drawer === 'build' && <BuildPanel world={world} />}
        {drawer === 'view' && <ViewSettings />}
        {world && drawer === 'entities' && <EntitiesPanel world={world} />}
        {world && drawer === 'monitor' && <Observatory world={world} />}
        {world && drawer === 'events' && <EventLog />}
        {world && drawer === 'section' && <SectionPanel world={world} />}
        {world && drawer === 'tune' && <Params />}
      </div>
    </aside>
  );
}

const SHORTCUTS: [string, string][] = [
  ['K', 'Play / pause'],
  ['.', 'Step forward once (while paused)'],
  ['Click', 'Select what is under the pointer and see its properties'],
  ['F', 'Frame the selection (or the volcano)'],
  ['Esc', 'Stop the map tool, clear the selection, close the panel'],
  ['E', 'Entities list'],
  ['Ctrl K', 'Find anything (command palette)'],
  ['?', 'Camera keys and mouse controls'],
  ['1 / 2 / 3', 'Camera: orbit, fly, walk'],
];

/** First-run guide; reopened from the header's help button. */
export function Guide() {
  const open = useStore((s) => s.guideOpen);
  const set = useStore((s) => s.set);
  const close = () => {
    rememberGuideSeen();
    set({ guideOpen: false });
  };
  return (
    <Dialog open={open} onOpenChange={(o) => !o && close()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Welcome to Typhon</DialogTitle>
          <DialogDescription>A volcano simulator: magma, earthquakes, lava, ash and groundwater, computed live.</DialogDescription>
        </DialogHeader>
        <ol className="flex list-decimal flex-col gap-1.5 pl-5 text-sm">
          <li>
            <b>Look around</b>: drag to rotate, right-drag to pan, scroll to zoom. Double-click the ground to focus there.
          </li>
          <li>
            <b>Select</b> anything — a vent, a dike, a hot spring, a quake or just the ground — by clicking it. The inspector on the right shows what it is, live.
          </li>
          <li>
            <b>Play</b> the simulation and choose how fast to watch it. <i>Speed</i> only changes how fast you watch; the physics stays the same.
          </li>
          <li>
            <b>Make things happen</b> with the buttons at the bottom left: start an eruption, add magma, or push up a dike.
          </li>
          <li>
            <b>Entities</b> (top right) lists everything that appears and disappears: new springs, dikes, fissures and flows. New ones also pop up as notifications you can click.
          </li>
        </ol>
        <p className="text-xs text-muted-foreground">
          <b>Time:</b> the clock shows <i>simulated time</i>. Volcanoes are slow, so volcano processes run faster than that (time compression, e.g. ×5 000 while quiet); the clock's second line
          estimates the volcano time that has passed.
        </p>
        <Table>
          <TableBody>
            {SHORTCUTS.map(([k, v]) => (
              <TableRow key={k}>
                <TableCell className="w-24">
                  <Kbd>{k}</Kbd>
                </TableCell>
                <TableCell>{v}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => {
              close();
              useCamera.getState().set({ helpOpen: true });
            }}
          >
            All camera controls
          </Button>
          <Button autoFocus onClick={close}>
            Got it
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** Global keys that do not clash with the camera (WASD, QE, 1–5, F, P, O, G, [ ], arrows, Space). */
export function useGlobalKeys(): void {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const t = e.target as HTMLElement | null;
      if (t && (t.tagName === 'INPUT' || t.tagName === 'SELECT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) return;
      const s = useStore.getState();
      if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) {
        e.preventDefault();
        s.set({ paletteOpen: !s.paletteOpen });
        return;
      }
      // builder undo/redo (when there is something to undo or redo, so the browser's own stays otherwise)
      if ((e.ctrlKey || e.metaKey) && !e.altKey) {
        const z = e.key === 'z' || e.key === 'Z';
        if (z && !e.shiftKey && s.buildHistory.undo.length > 0) {
          e.preventDefault();
          void undoBuild();
          return;
        }
        if (((z && e.shiftKey) || e.key === 'y' || e.key === 'Y') && s.buildHistory.redo.length > 0) {
          e.preventDefault();
          void redoBuild();
          return;
        }
      }
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      // dialogs and menus handle their own Escape
      if (document.querySelector('[role="dialog"], [role="menu"], [role="listbox"][data-open]')) return;
      const clock = s.clock;
      if (e.key === 'k' || e.key === 'K') {
        if (!clock || clock.replay) return;
        send(clock.mode === 'PAUSED' ? { type: 'transport', mode: 'REALTIME', speed: clock.speed } : { type: 'transport', mode: 'PAUSED' });
      } else if (e.key === '.') {
        if (clock && !clock.replay) send({ type: 'step', steps: 1 });
      } else if (e.key === 'e' || e.key === 'E') {
        if (s.world && useCamera.getState().mode === 'orbit') s.openDrawer('entities');
      } else if (e.key === 'b' || e.key === 'B') {
        if (s.world && useCamera.getState().mode === 'orbit') s.openDrawer('build');
      } else if (e.key === 'Delete' || e.key === 'Backspace') {
        // Build mode: Delete removes the selected chamber or pathway (the server asks first when it resets something)
        const sel = s.selection;
        const ent = sel?.type === 'entity' ? s.entities[sel.id] : undefined;
        if (!ent?.volcanoId || s.drawer !== 'build') return;
        if (ent.kind === 'chamber') void removeChamber(ent.volcanoId, String(ent.props.chamberId ?? 'main'));
        else if (ent.kind === 'connection') void removeConnection(ent.volcanoId, String(ent.props.connectionId));
      } else if (e.key === 'Escape') {
        if (useCamera.getState().helpOpen || document.pointerLockElement) return;
        if (s.buildDraft || s.connectDraft) s.set({ buildDraft: null, connectDraft: null, ...(s.tool === 'chamber' ? { tool: 'orbit' as const } : {}) });
        else if (s.tool !== 'orbit') s.set({ tool: 'orbit' });
        else if (s.selection) s.select(null);
        else if (s.drawer) s.set({ drawer: null });
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
}
