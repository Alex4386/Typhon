import { useEffect } from 'react';
import { CameraBar, CameraHelp, CameraReadoutPanel, Minimap } from '../camera/CameraHud';
import { connect } from '../net/connection';
import { ReplayBar } from '../panels/Controls';
import { Drawer, Guide, Toasts, useGlobalKeys } from '../panels/Drawer';
import { Clock, DrawerButtons, Playback, WorldSwitcher } from '../panels/Header';
import { ActionBar, StatusCard } from '../panels/Overlay';
import { Viewer } from '../scene/Viewer';
import { useStore } from '../store/store';

export function App() {
  const world = useStore((s) => s.world);
  const status = useStore((s) => s.status);
  const welcome = useStore((s) => s.welcome);
  const serverUrl = useStore((s) => s.serverUrl);
  const underground = useStore((s) => s.underground);
  const showCameraTools = useStore((s) => s.showCameraTools);
  const replay = useStore((s) => s.clock?.replay ?? false);
  const sessionsLoaded = useStore((s) => s.sessions.length);
  const openDrawer = useStore((s) => s.openDrawer);

  useEffect(() => {
    connect();
  }, []);
  useGlobalKeys();

  return (
    <div className={`app${replay ? ' in-replay' : ''}`}>
      <header>
        <span className="brand" aria-label="Typhon">
          TYPHON
        </span>
        <WorldSwitcher />
        {welcome?.mock && <span className="mock-badge">MOCK DATA</span>}
        <span className="spacer" />
        {world && (
          <>
            <Clock />
            <Playback />
          </>
        )}
        <span className="spacer" />
        <DrawerButtons />
        <button className="icon" aria-label="Help" title="Quick guide and keyboard shortcuts" onClick={() => useStore.getState().set({ guideOpen: true })}>
          ?
        </button>
        {status !== 'open' && (
          <span className={`conn conn-${status}`} title={serverUrl} role="status">
            ● {status === 'connecting' ? 'connecting…' : 'disconnected, retrying'}
          </span>
        )}
      </header>
      <main>
        <section className={underground ? 'view underground' : 'view'} aria-label="3D view">
          {world ? (
            <>
              <Viewer world={world} />
              <StatusCard world={world} />
              <ActionBar world={world} />
              <CameraBar world={world} />
              {showCameraTools && <CameraReadoutPanel />}
              <Minimap world={world} />
              <CameraHelp />
            </>
          ) : (
            <div className="waiting">
              {status !== 'open' ? (
                <p>
                  Connecting to {serverUrl} …<br />
                  <span className="muted small">Start the sim-server (or the mock with “npm run mock”).</span>
                </p>
              ) : sessionsLoaded === 0 ? (
                <p>
                  No world is running yet.
                  <br />
                  <button className="primary" onClick={() => useStore.getState().drawer !== 'sims' && openDrawer('sims')}>
                    Start or open a world
                  </button>
                </p>
              ) : (
                <p>Loading world…</p>
              )}
            </div>
          )}
          <Toasts />
        </section>
        <Drawer world={world} />
      </main>
      {replay && (
        <footer>
          <ReplayBar />
        </footer>
      )}
      <Guide />
    </div>
  );
}
