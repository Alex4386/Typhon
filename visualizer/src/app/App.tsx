import { useEffect, useState } from 'react';
import { BottomSheet } from '@/components/BottomSheet';
import type { SheetSnap } from '@/components/sheetMath';
import { NARROW_VIEWPORT, useMediaQuery } from '../util/useMediaQuery';
import { TouchSticks } from '../camera/TouchSticks';
import { CircleHelp, Search } from 'lucide-react';
import { Tip } from '@/components/tip';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Kbd } from '@/components/ui/kbd';
import { ResizableHandle, ResizablePanel, ResizablePanelGroup } from '@/components/ui/resizable';
import { Hud, HudToasts } from './Hud';
import { TooltipProvider } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { CameraBar, CameraHelp, CameraReadoutPanel, Minimap } from '../camera/CameraHud';
import { connect } from '../net/connection';
import { CommandPalette } from '../panels/CommandPalette';
import { ReplayBar } from '../panels/Controls';
import { Guide, SidePanel, useGlobalKeys } from '../panels/Drawer';
import { Clock, DrawerButtons, MobileMenu, Playback, WorldSwitcher } from '../panels/Header';
import { Inspector } from '../panels/Inspector';
import { ActionBar, StatusCard, ToolHint } from '../panels/Overlay';
import { ConfigConfirm } from '../panels/ParamRow';
import { PerfHud } from '../scene/PerfHud';
import { Viewer } from '../scene/Viewer';
import { rememberDrawerWidth, useStore } from '../store/store';

/** Name of what is under the pointer in the 3D view, next to the cursor. */
function HoverLabel() {
  const hover = useStore((s) => s.hover);
  if (!hover) return null;
  return (
    <div className="pointer-events-none fixed z-40 rounded-md bg-popover/95 px-2 py-1 text-xs text-popover-foreground shadow-md ring-1 ring-foreground/10" style={{ left: hover.x + 14, top: hover.y + 14 }}>
      <div className="font-medium">{hover.label}</div>
      {hover.detail && <div className="text-muted-foreground">{hover.detail}</div>}
    </div>
  );
}

export function App() {
  const world = useStore((s) => s.world);
  const status = useStore((s) => s.status);
  const welcome = useStore((s) => s.welcome);
  const serverUrl = useStore((s) => s.serverUrl);
  const underground = useStore((s) => s.underground);
  const showCameraTools = useStore((s) => s.showCameraTools);
  const replay = useStore((s) => s.clock?.replay ?? false);
  const sessionsLoaded = useStore((s) => s.sessions.length);
  const drawer = useStore((s) => s.drawer);
  const drawerWidth = useStore((s) => s.drawerWidth);
  const set = useStore((s) => s.set);
  const selection = useStore((s) => s.selection);
  const narrow = useMediaQuery(NARROW_VIEWPORT);
  const [drawerSnap, setDrawerSnap] = useState<SheetSnap>('half');
  const [inspectorSnap, setInspectorSnap] = useState<SheetSnap>('peek');
  // a new selection on a phone shows its sheet at peek height: the view stays visible
  useEffect(() => setInspectorSnap('peek'), [selection]);

  useEffect(() => {
    connect();
  }, []);
  useGlobalKeys();

  const view = (
    <section className={cn('view relative h-full overflow-hidden', underground && 'underground')} aria-label="3D view">
      {world ? (
        <>
          <Viewer world={world} />
          <Hud
            topLeft={<StatusCard world={world} />}
            bottomLeft={<ActionBar world={world} />}
            top={
              <>
                <PerfHud />
                <HudToasts />
              </>
            }
            bottom={
              <>
                <ToolHint />
                {showCameraTools && <CameraReadoutPanel />}
              </>
            }
            topRight={narrow ? null : <Inspector world={world} />}
            bottomRight={
              <>
                {!narrow && <Minimap world={world} />}
                <CameraBar world={world} />
              </>
            }
          />
          <TouchSticks />
          <CameraHelp />
        </>
      ) : (
        <div className="flex h-full items-center justify-center p-6 text-center">
          {status !== 'open' ? (
            <p>
              Connecting to {serverUrl} …<br />
              <span className="text-sm text-muted-foreground">Start the sim-server (or the mock with “npm run mock”).</span>
            </p>
          ) : sessionsLoaded === 0 ? (
            <div className="flex flex-col items-center gap-3">
              <p>No world is running yet.</p>
              <Button onClick={() => set({ drawer: 'sims' })}>Start or open a world</Button>
            </div>
          ) : (
            <p>Loading world…</p>
          )}
        </div>
      )}
    </section>
  );

  return (
    <TooltipProvider delay={350}>
      <div className={cn('flex h-dvh flex-col bg-background text-foreground', replay && 'in-replay')}>
        <header className="flex shrink-0 items-center gap-2 border-b bg-card px-3 pt-[env(safe-area-inset-top)] pr-[max(0.75rem,env(safe-area-inset-right))] pl-[max(0.75rem,env(safe-area-inset-left))] h-[calc(3rem+env(safe-area-inset-top))] phone:gap-1 phone:px-2">
          <span className="text-sm font-black tracking-[0.2em] text-primary phone:hidden" aria-label="Typhon">
            TYPHON
          </span>
          <span className="phone:hidden">
            <WorldSwitcher />
          </span>
          {welcome?.mock && <Badge variant="outline" className="phone:hidden">MOCK DATA</Badge>}
          <span className="flex-1 phone:hidden" />
          {world && (
            <>
              <Clock />
              <Playback />
            </>
          )}
          <span className="flex-1" />
          {narrow ? (
            <MobileMenu />
          ) : (
            <>
          <Tip
            content={
              <span>
                Find a panel, action or anything on the map <Kbd>Ctrl K</Kbd>
              </span>
            }
          >
            <Button variant="outline" size="sm" className="text-muted-foreground" onClick={() => set({ paletteOpen: true })}>
              <Search /> <span className="hidden 2xl:inline">Find…</span>
            </Button>
          </Tip>
          <DrawerButtons />
          <Tip content="Quick guide and keyboard shortcuts">
            <Button variant="ghost" size="icon-sm" aria-label="Help" onClick={() => set({ guideOpen: true })}>
              <CircleHelp />
            </Button>
          </Tip>
            </>
          )}
          {status !== 'open' && (
            <Tip content={serverUrl}>
              <Badge variant={status === 'connecting' ? 'secondary' : 'destructive'} role="status" className="phone:max-w-24 phone:truncate">
                ● {status === 'connecting' ? 'connecting…' : 'disconnected, retrying'}
              </Badge>
            </Tip>
          )}
        </header>
        <main className="min-h-0 flex-1">
          {narrow ? (
            <>
              {view}
              {/* phones: panels and the Inspector are bottom sheets over the view (a panel wins over the Inspector) */}
              <BottomSheet open={!!drawer} snap={drawerSnap} onSnap={setDrawerSnap} onClose={() => set({ drawer: null })} label="Panel" z={35}>
                <SidePanel world={world} />
              </BottomSheet>
              {world && (
                <BottomSheet open={!!selection && !drawer} snap={inspectorSnap} onSnap={setInspectorSnap} onClose={() => useStore.getState().select(null)} label="Inspector" z={30}>
                  <Inspector world={world} sheet />
                </BottomSheet>
              )}
            </>
          ) : (
          <ResizablePanelGroup orientation="horizontal">
            <ResizablePanel id="view" minSize={240}>
              {view}
            </ResizablePanel>
            {drawer && (
              <>
                <ResizableHandle withHandle />
                <ResizablePanel
                  id="side"
                  defaultSize={drawerWidth}
                  minSize={300}
                  maxSize="70%"
                  groupResizeBehavior="preserve-pixel-size"
                  onResize={(size) => {
                    if (size.inPixels >= 300) {
                      useStore.setState({ drawerWidth: size.inPixels });
                      rememberDrawerWidth(size.inPixels);
                    }
                  }}
                >
                  <SidePanel world={world} />
                </ResizablePanel>
              </>
            )}
          </ResizablePanelGroup>
          )}
        </main>
        {replay && (
          <footer>
            <ReplayBar />
          </footer>
        )}
        <Guide />
        <CommandPalette />
        <ConfigConfirm />
        <HoverLabel />
      </div>
    </TooltipProvider>
  );
}
