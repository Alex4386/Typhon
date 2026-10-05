import { useEffect, useState } from 'react';
import { attachSession, controlSession, createSession, deleteWorld, refreshCatalog, send } from '../net/connection';
import type { SessionInfo } from '../protocol/messages';
import { useStore } from '../store/store';
import { ALERT_COLORS } from '../util/color';
import { formatFactor, formatSimTime } from '../util/world';
import { ALERT_LABEL } from './events';
import { sessionLabel } from './Header';

/** Worlds page: what the server runs, what it can open, and starting new worlds. */
export function SessionManager() {
  const sessions = useStore((s) => s.sessions);
  const sessionId = useStore((s) => s.sessionId);
  const catalog = useStore((s) => s.catalog);
  const server = useStore((s) => s.serverInfo);
  useEffect(() => refreshCatalog(), []);
  const full = server ? sessions.length >= server.maxSessions : false;
  const closed = (catalog?.worlds ?? []).filter((w) => !w.sessionId);
  return (
    <div className="sessions">
      <section>
        <h3>Running now</h3>
        {sessions.length === 0 && <p className="muted">Nothing is running. Start a world below.</p>}
        <ul className="session-list">
          {sessions.map((s) => (
            <SessionRow key={s.id} s={s} attached={s.id === sessionId} />
          ))}
        </ul>
        {server && (
          <p className="muted small" title="Every running world shares the server's processors and memory; paused worlds use memory but no processor time.">
            {sessions.length} of {server.maxSessions} worlds · memory {server.heapUsedMB} / {server.heapMaxMB} MB · {server.cpus} processors
          </p>
        )}
      </section>
      <NewWorld disabled={full} />
      <section>
        <h3>Saved worlds</h3>
        {!server?.worldsDir && <p className="muted small">The server has no worlds folder (start it with --worlds-dir).</p>}
        {server?.worldsDir && closed.length === 0 && <p className="muted small">No other saved worlds in {server.worldsDir}.</p>}
        <ul className="session-list">
          {closed.map((w) => (
            <li key={w.name} className="session-row">
              <div className="grow">
                <b>{w.title || w.name}</b>
                <div className="muted small">
                  {w.error ? <span className="field-error">{w.error}</span> : `${w.volcanoes} volcano${w.volcanoes === 1 ? '' : 'es'} · ${w.hasState ? 'saved progress' : 'not started yet'}`}
                  {w.timeCompression && ` · time ${formatFactor(w.timeCompression.dormant)}`}
                </div>
              </div>
              <button disabled={full || !!w.error} title={full ? 'The server runs as many worlds as it may; close one first' : 'Load this world and watch it'} onClick={() => createSession({ world: w.name })}>
                Open
              </button>
              <button
                className="danger"
                title="Delete this world's folder, including its saved progress and replay"
                onClick={() => {
                  if (window.confirm(`Delete the world “${w.name}” and all its saved progress? This cannot be undone.`)) deleteWorld(w.name);
                }}
              >
                Delete
              </button>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}

function SessionRow({ s, attached }: { s: SessionInfo; attached: boolean }) {
  const paused = s.mode === 'PAUSED';
  const top = s.volcanoes?.reduce<string | null>((best, v) => {
    const order = ['EXTINCT', 'DORMANT', 'MINOR_ACTIVITY', 'MAJOR_ACTIVITY', 'ERUPTION_IMMINENT', 'ERUPTING'];
    return best === null || order.indexOf(v.alert) > order.indexOf(best) ? v.alert : best;
  }, null);
  const tc = s.volcanoes?.[0]?.timeCompression;
  return (
    <li className={`session-row${attached ? ' attached' : ''}`}>
      <div className="grow">
        <b title={s.name}>{sessionLabel(s)}</b> {attached && <span className="tag">watching</span>}
        {top && (
          <span className="alert-badge" style={{ background: ALERT_COLORS[top] ?? '#444', marginLeft: 6 }}>
            {ALERT_LABEL[top] ?? top}
          </span>
        )}
        <div className="muted small">
          {paused ? 'Paused' : s.mode === 'UNBOUNDED' ? 'Running flat out' : `Running ${s.speed ?? ''}×`} · sim time {formatSimTime(s.time)}
          {tc && ` · volcano time ${formatFactor(tc.current ?? tc.dormant)}`}
          {s.clients ? ` · ${s.clients} viewer${s.clients > 1 ? 's' : ''}` : ''}
          {!s.world && ' · not saved to disk'}
        </div>
      </div>
      {!attached && (
        <button className="primary" onClick={() => attachSession(s.id)}>
          Watch
        </button>
      )}
      <button title={paused ? 'Resume this world' : 'Pause this world (it keeps its memory but stops computing)'} onClick={() => (attached ? send(paused ? { type: 'transport', mode: 'REALTIME', speed: s.speed ?? 20 } : { type: 'transport', mode: 'PAUSED' }) : controlSession(s.id, paused ? 'resume' : 'pause'))}>
        {paused ? 'Resume' : 'Pause'}
      </button>
      <button
        title={s.world ? 'Save and unload this world (open it again from Saved worlds)' : 'Unload this world; it was never saved, so it is gone afterwards'}
        onClick={() => {
          if (s.world || window.confirm(`“${sessionLabel(s)}” only lives in memory. Close it and lose it?`)) controlSession(s.id, 'close');
        }}
      >
        Close
      </button>
    </li>
  );
}

function NewWorld({ disabled }: { disabled: boolean }) {
  const catalog = useStore((s) => s.catalog);
  const presets = catalog?.presets ?? [];
  const [preset, setPreset] = useState('');
  const [name, setName] = useState('');
  const [advanced, setAdvanced] = useState(false);
  const [dormant, setDormant] = useState('');
  const [eruptive, setEruptive] = useState('');
  const [paused, setPaused] = useState(false);
  const chosen = presets.find((p) => p.name === (preset || presets[0]?.name));
  const nameOk = name === '' || /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(name);
  const num = (t: string) => (t.trim() === '' ? undefined : Number(t));
  const factorsOk = [dormant, eruptive].every((t) => t.trim() === '' || Number(t) > 0);
  return (
    <section>
      <h3>Start a new world</h3>
      <form
        className="new-world"
        onSubmit={(e) => {
          e.preventDefault();
          if (!chosen || !nameOk || !factorsOk) return;
          createSession({ preset: chosen.name, name: name || undefined, dormant: num(dormant), eruptive: num(eruptive), paused });
          setName('');
        }}
      >
        <label>
          Volcano
          <select value={chosen?.name ?? ''} onChange={(e) => setPreset(e.target.value)}>
            {presets.map((p) => (
              <option key={p.name} value={p.name}>
                {p.title || p.name}
                {p.realScale ? ' (real scale)' : ''}
              </option>
            ))}
          </select>
        </label>
        {chosen?.description && <p className="muted small">{chosen.description}</p>}
        <label>
          Name
          <input type="text" placeholder={chosen ? `${chosen.name} (automatic)` : ''} value={name} aria-invalid={!nameOk} className={nameOk ? '' : 'invalid'} onChange={(e) => setName(e.target.value)} />
        </label>
        {!nameOk && <span className="field-error">Letters, digits, dot, dash and underscore only.</span>}
        <button type="button" className="link" aria-expanded={advanced} onClick={() => setAdvanced(!advanced)}>
          {advanced ? '▾' : '▸'} Time scale and start options
        </button>
        {advanced && (
          <div className="advanced">
            <p className="muted small">
              Real volcanoes take years to recharge. Time compression makes volcano processes run faster than simulated time so you can watch them. Leave empty for the volcano's default.
            </p>
            <label title="Physical seconds per simulated second while the volcano is quiet">
              Quiet periods ×
              <input type="text" inputMode="decimal" placeholder="default" value={dormant} onChange={(e) => setDormant(e.target.value)} />
            </label>
            <label title="Physical seconds per simulated second while it erupts (usually small so lava looks right)">
              During eruptions ×
              <input type="text" inputMode="decimal" placeholder="default" value={eruptive} onChange={(e) => setEruptive(e.target.value)} />
            </label>
            {!factorsOk && <span className="field-error">Factors must be positive numbers.</span>}
            <label className="check">
              <input type="checkbox" checked={paused} onChange={(e) => setPaused(e.target.checked)} /> Start paused
            </label>
          </div>
        )}
        <button type="submit" className="primary" disabled={disabled || !chosen || !nameOk || !factorsOk} title={disabled ? 'The server runs as many worlds as it may; close one first' : undefined}>
          Start world
        </button>
      </form>
    </section>
  );
}
