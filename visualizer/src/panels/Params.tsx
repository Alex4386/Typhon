import { useEffect, useMemo, useRef, useState } from 'react';
import { command, setParams } from '../net/connection';
import type { ParamSpec, ParamValue } from '../protocol/messages';
import { useStore } from '../store/store';
import { fieldError } from './inject';
import { ParamInput, formatParam } from './ParamInput';

/** Live (hot) edits are sent this long after the last keystroke/slider move. */
const LIVE_DEBOUNCE_MS = 400;

function same(a: ParamValue | null | undefined, b: ParamValue | null | undefined): boolean {
  if (typeof a === 'number' && typeof b === 'number') return Math.abs(a - b) <= 1e-9 * Math.max(1, Math.abs(a), Math.abs(b));
  return a === b;
}

/** Parameters page: weather now, then every tunable value of the world grouped by subject. */
export function Params() {
  const schema = useStore((s) => s.schema);
  const [filter, setFilter] = useState('');
  const [pending, setPending] = useState<Record<string, ParamValue | null>>({});
  const [confirming, setConfirming] = useState(false);
  const liveQueue = useRef<Record<string, ParamValue | null>>({});
  const liveTimer = useRef<number | undefined>(undefined);
  useEffect(() => () => window.clearTimeout(liveTimer.current), []);
  // drop pending edits the server now reports as applied
  useEffect(() => {
    if (!schema) return;
    setPending((p) => {
      const next = { ...p };
      for (const spec of schema.params) {
        const v = next[spec.id];
        if (v === undefined) continue;
        if ((v === null && same(spec.value, spec.default)) || (v !== null && same(spec.value, v))) delete next[spec.id];
      }
      return next;
    });
  }, [schema]);

  const specs = schema?.params ?? [];
  const byId = useMemo(() => new Map(specs.map((p) => [p.id, p])), [specs]);
  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase();
    return q ? specs.filter((p) => `${p.label} ${p.group} ${p.id} ${p.help ?? ''}`.toLowerCase().includes(q)) : specs;
  }, [specs, filter]);
  const groups = useMemo(() => [...new Set(shown.map((p) => p.group))], [shown]);

  const valueOf = (p: ParamSpec): ParamValue | undefined => {
    const v = pending[p.id];
    if (v === null) return p.default;
    return v ?? p.value;
  };
  const edit = (p: ParamSpec, v: ParamValue | null) => {
    setPending((x) => ({ ...x, [p.id]: v }));
    if (p.apply !== 'hot') return;
    const value = v === null ? p.default : v;
    if (p.type === 'number' && fieldError(p, value) !== null) return;
    liveQueue.current[p.id] = v;
    window.clearTimeout(liveTimer.current);
    liveTimer.current = window.setTimeout(() => {
      const batch = liveQueue.current;
      liveQueue.current = {};
      setParams(batch);
    }, LIVE_DEBOUNCE_MS);
  };
  const restartEdits = Object.entries(pending).filter(([id]) => byId.get(id)?.apply === 'restart');
  const restartInvalid = restartEdits.some(([id, v]) => {
    const p = byId.get(id)!;
    return v !== null && fieldError(p, v) !== null;
  });
  const restartWho = [...new Set(restartEdits.map(([id]) => byId.get(id)?.volcanoId ?? 'world'))];

  return (
    <div className="params">
      <Weather />
      {!schema && <p className="muted">This server does not publish tunable parameters.</p>}
      {schema && !schema.tunable && <p className="notice">{schema.reason ?? 'This world cannot be changed.'}</p>}
      {schema && specs.length > 0 && (
        <>
          <div className="params-tools">
            <input type="search" placeholder="Find a setting (e.g. temperature, rain, silica)" aria-label="Find a setting" value={filter} onChange={(e) => setFilter(e.target.value)} />
          </div>
          <p className="muted small">
            <span className="tag live">live</span> changes apply at once. <span className="tag restart">restart</span> changes rebuild the volcano from its settings (its magma and surroundings start over;
            the landscape is kept).
          </p>
          {groups.map((g) => (
            <details key={g} open={groups.length <= 3 || filter !== '' || /Weather|Magma supply/.test(g)}>
              <summary>{g}</summary>
              {shown
                .filter((p) => p.group === g)
                .map((p) => {
                  const v = valueOf(p);
                  const err = fieldError(p, v);
                  const changed = pending[p.id] !== undefined;
                  const atDefault = same(v, p.default);
                  return (
                    <div key={p.id} className={`param${changed ? ' changed' : ''}`}>
                      <label htmlFor={`p-${p.id}`} title={`${p.help ?? ''}\n(${p.id})`.trim()}>
                        {p.label}
                      </label>
                      <ParamInput spec={p} value={v} invalid={!!err} onChange={(x) => edit(p, x)} />
                      <span className={`tag ${p.apply === 'hot' ? 'live' : 'restart'}`}>{p.apply === 'hot' ? 'live' : 'restart'}</span>
                      <button className="icon" disabled={atDefault || p.default === undefined} title={`Back to default (${formatParam(p.default, p)})`} aria-label={`Reset ${p.label} to default`} onClick={() => edit(p, null)}>
                        ↺
                      </button>
                      {err ? <span className="field-error">{err}</span> : p.help ? <span className="muted small param-help">{p.help}</span> : null}
                    </div>
                  );
                })}
            </details>
          ))}
          {restartEdits.length > 0 && (
            <div className="restart-bar" role="region" aria-label="Changes waiting for a restart">
              <span>
                {restartEdits.length} change{restartEdits.length > 1 ? 's' : ''} need{restartEdits.length > 1 ? '' : 's'} a restart of {restartWho.join(', ')}.
              </span>
              <span className="spacer" />
              <button onClick={() => setPending(Object.fromEntries(Object.entries(pending).filter(([id]) => byId.get(id)?.apply !== 'restart')))}>Discard</button>
              <button className="primary" disabled={restartInvalid} onClick={() => setConfirming(true)}>
                Apply and restart…
              </button>
            </div>
          )}
          {confirming && (
            <div className="confirm" role="alertdialog" aria-labelledby="confirm-title">
              <b id="confirm-title">Restart {restartWho.join(', ')}?</b>
              <p className="small">
                {restartWho.length > 1 ? 'Their magma systems start' : 'Its magma system starts'} over from the new settings: chamber pressure, recharge and alert history reset. The terrain, lava already on the ground and the
                replay up to now are kept.
              </p>
              <ul className="small">
                {restartEdits.map(([id, v]) => {
                  const p = byId.get(id)!;
                  return (
                    <li key={id}>
                      {p.label}: {formatParam(p.value, p)} → {formatParam(v === null ? p.default : v, p)}
                    </li>
                  );
                })}
              </ul>
              <div className="dialog-actions">
                <button onClick={() => setConfirming(false)}>Cancel</button>
                <button
                  className="primary danger"
                  onClick={() => {
                    setParams(Object.fromEntries(restartEdits), true);
                    setConfirming(false);
                  }}
                >
                  Apply and restart
                </button>
              </div>
            </div>
          )}
          <Audit />
        </>
      )}
    </div>
  );
}

function Weather() {
  const world = useStore((s) => s.state?.world);
  const [rain, setRain] = useState<number | null>(null);
  const rainNow = world?.rainMmPerHour ?? 0;
  return (
    <section className="weather">
      <h3>Weather now</h3>
      <label className="slider" title="Rain over the whole map; it soaks into the ground, fills lakes and can start mudflows on ash">
        <span>Rain {(rain ?? rainNow).toFixed(0)} mm/h</span>
        <input
          type="range"
          min={0}
          max={100}
          value={rain ?? rainNow}
          onChange={(e) => setRain(Number(e.target.value))}
          onPointerUp={() => {
            if (rain !== null) command({ kind: 'rain', mmPerHour: rain });
            setRain(null);
          }}
          onKeyUp={() => {
            if (rain !== null) command({ kind: 'rain', mmPerHour: rain });
            setRain(null);
          }}
        />
      </label>
      {world?.wind && (
        <p className="muted small">
          Wind {world.wind.speed.toFixed(0)} m/s towards {world.wind.bearingDeg.toFixed(0)}°
        </p>
      )}
    </section>
  );
}

function Audit() {
  const audit = useStore((s) => s.schema?.audit ?? []);
  if (audit.length === 0) return null;
  return (
    <details className="audit">
      <summary>Change history ({audit.length})</summary>
      <ul>
        {[...audit].reverse().map((a, k) => (
          <li key={k}>
            <time>{new Date(a.at).toLocaleTimeString()}</time> {a.label}: {formatParam(a.from)} → {a.to === null ? 'default' : formatParam(a.to)}
            {a.apply === 'restart' && <span className="tag restart">restart</span>}
          </li>
        ))}
      </ul>
    </details>
  );
}
