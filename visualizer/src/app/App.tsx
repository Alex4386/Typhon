import { useEffect } from 'react';
import { CameraBar, CameraHelp, CameraReadoutPanel, Minimap } from '../camera/CameraHud';
import { connect } from '../net/connection';
import { EventLog } from '../panels/EventLog';
import { AlertBadge, Observatory } from '../panels/Observatory';
import { ReplayBar, Toolbox, Transport } from '../panels/Controls';
import { SectionPanel } from '../panels/SectionPanel';
import { Viewer } from '../scene/Viewer';
import { useStore } from '../store/store';

export function App() {
  const world = useStore((s) => s.world);
  const status = useStore((s) => s.status);
  const welcome = useStore((s) => s.welcome);
  const renderer = useStore((s) => s.renderer);
  const errors = useStore((s) => s.errors);
  const state = useStore((s) => s.state);
  const serverUrl = useStore((s) => s.serverUrl);
  const underground = useStore((s) => s.underground);

  useEffect(() => {
    connect();
  }, []);

  return (
    <div className="app">
      <header>
        <span className="brand">TYPHON</span>
        <span className="muted">{world?.name ?? 'volcano simulator'}</span>
        {welcome?.mock && <span className="mock-badge">MOCK DATA</span>}
        {state &&
          world?.volcanoes.map((v) => {
            const vs = state.volcanoes[v.id];
            return vs ? (
              <span key={v.id} className="hdr-volcano" onClick={() => useStore.getState().set({ selectedVolcano: v.id })}>
                {v.name.replace(' (MOCK)', '')} <AlertBadge level={vs.alert.level} />
              </span>
            ) : null;
          })}
        <span className="spacer" />
        <Transport />
        <span className={`conn conn-${status}`} title={serverUrl}>
          ● {status}
        </span>
        <span className="muted" title="renderer backend">
          {renderer}
        </span>
      </header>
      {world ? (
        <main>
          <section className={underground ? 'view underground' : 'view'}>
            <Viewer world={world} />
            <Toolbox world={world} />
            <CameraBar world={world} />
            <CameraReadoutPanel />
            <Minimap world={world} />
            <CameraHelp />
          </section>
          <section className="side">
            <Observatory world={world} />
            <EventLog />
          </section>
          <section className="bottom">
            <SectionPanel world={world} />
          </section>
        </main>
      ) : (
        <div className="waiting">
          {status === 'open' ? 'Waiting for world…' : `Connecting to ${serverUrl} … (start the mock with "npm run mock" or the sim-server)`}
        </div>
      )}
      <footer>
        <ReplayBar />
        {errors.length > 0 && <span className="error">{errors[errors.length - 1]}</span>}
      </footer>
    </div>
  );
}
