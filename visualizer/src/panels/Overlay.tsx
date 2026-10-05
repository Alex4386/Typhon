import { useEffect, useMemo, useRef, useState } from 'react';
import { command } from '../net/connection';
import type { ParamSpec, ParamValue, SimCommand, VolcanoState, WorldInfo } from '../protocol/messages';
import { useStore, type Tool } from '../store/store';
import { ALERT_COLORS } from '../util/color';
import { formatDuration, formatFactor } from '../util/world';
import { ALERT_LABEL, REGIME_LABEL, STYLE_LABEL } from './events';
import { FALLBACK_INJECT_FIELDS, MAGMA_PRESETS, fieldError, formatVolume, injectWarnings, mixPreview } from './inject';
import { ParamInput } from './ParamInput';

function useVolcano(world: WorldInfo): { id: string | undefined; name: string; vs: VolcanoState | undefined } {
  const selected = useStore((s) => s.selectedVolcano);
  const state = useStore((s) => s.state);
  const id = selected ?? world.volcanoes[0]?.id;
  const name = world.volcanoes.find((v) => v.id === id)?.name ?? id ?? '';
  return { id, name, vs: id ? state?.volcanoes[id] : undefined };
}

/** Compact summary of the selected volcano, top-left over the 3D view. */
export function StatusCard({ world }: { world: WorldInfo }) {
  const { id, name, vs } = useVolcano(world);
  const set = useStore((s) => s.set);
  const openDrawer = useStore((s) => s.openDrawer);
  const [collapsed, setCollapsed] = useState(false);
  if (!id) return null;
  const level = vs?.alert.level ?? 'DORMANT';
  const erupting = (vs?.chamber.eruptionRate ?? 0) > 0 || level === 'ERUPTING';
  const pressure = vs && vs.chamber.tensileStrengthMPa > 0 ? vs.chamber.overpressureMPa / vs.chamber.tensileStrengthMPa : null;
  const tc = vs?.timeCompression;
  return (
    <section className="panel status-card" aria-label="Volcano status">
      <div className="sc-head">
        {world.volcanoes.length > 1 ? (
          <select aria-label="Volcano" value={id} onChange={(e) => set({ selectedVolcano: e.target.value })}>
            {world.volcanoes.map((v) => (
              <option key={v.id} value={v.id}>
                {v.name}
              </option>
            ))}
          </select>
        ) : (
          <strong>{name}</strong>
        )}
        <span className="alert-badge" style={{ background: ALERT_COLORS[level] ?? '#444' }}>
          {ALERT_LABEL[level] ?? level}
        </span>
        <span className="spacer" />
        <button className="icon" aria-label={collapsed ? 'Expand status' : 'Collapse status'} aria-expanded={!collapsed} title={collapsed ? 'Show details' : 'Hide details'} onClick={() => setCollapsed(!collapsed)}>
          {collapsed ? '▸' : '▾'}
        </button>
      </div>
      {!collapsed && vs && (
        <>
          <div className="sc-what">
            {erupting
              ? `${STYLE_LABEL[vs.alert.style]?.split(' (')[0] ?? vs.alert.style} eruption — ${REGIME_LABEL[vs.chamber.regime] ?? vs.chamber.regime}`
              : vs.seismic.swarm
                ? 'Earthquake swarm under the volcano'
                : vs.seismic.tremor
                  ? 'Volcanic tremor: magma or gas on the move'
                  : 'No eruption'}
          </div>
          <dl className="sc-stats">
            {erupting && (
              <>
                <dt title="Dense-rock-equivalent volume of magma leaving the vent">Lava output</dt>
                <dd>{vs.chamber.eruptionRate >= 10 ? vs.chamber.eruptionRate.toFixed(0) : vs.chamber.eruptionRate.toFixed(1)} m³/s</dd>
              </>
            )}
            {vs.plume && vs.plume.topZ > 0 && (
              <>
                <dt title="Height of the eruption column above sea level">Ash column</dt>
                <dd>{(vs.plume.topZ / 1000).toFixed(1)} km</dd>
              </>
            )}
            {pressure !== null && (
              <>
                <dt title="Magma pressure as a share of what the rock around the chamber can hold before it cracks open">Chamber pressure</dt>
                <dd>
                  <span className="meter" aria-hidden>
                    <i style={{ width: `${Math.min(100, Math.max(0, pressure * 100))}%`, background: pressure > 0.9 ? '#f85149' : pressure > 0.6 ? '#d29922' : '#3fb950' }} />
                  </span>
                  {Math.round(pressure * 100)}% of limit
                </dd>
              </>
            )}
            <dt title="Rock-breaking (VT) earthquakes per minute">Quakes</dt>
            <dd>{vs.seismic.vtPerMinute.toFixed(1)} / min</dd>
            <dt title="Largest ground uplift measured by the GPS stations">Ground uplift</dt>
            <dd>{(vs.deformation.maxUpliftM * 100).toFixed(1)} cm</dd>
            {tc && (
              <>
                <dt title={`Volcano time runs ${formatFactor(tc.dormant)} faster than simulated time while quiet and ${formatFactor(tc.eruptive)} while erupting.`}>Time scale</dt>
                <dd>
                  {formatFactor(tc.current)}
                  {vs.physicalTime !== undefined && <span className="muted"> · ≈ {formatDuration(vs.physicalTime)} passed</span>}
                </dd>
              </>
            )}
          </dl>
          <button className="link" onClick={() => openDrawer('monitor')}>
            Instruments →
          </button>
        </>
      )}
    </section>
  );
}

/** Magma injection: volume, temperature and composition (fields come from the server schema). */
export function InjectDialog({ world, onClose }: { world: WorldInfo; onClose: () => void }) {
  const { id, name, vs } = useVolcano(world);
  const schema = useStore((s) => s.schema);
  const fields: ParamSpec[] = schema?.commands.injectMagma ?? FALLBACK_INJECT_FIELDS;
  const defaults = useMemo(() => {
    const d: Record<string, ParamValue> = {};
    for (const f of fields) if (f.value !== undefined) d[f.id] = f.value;
    else if (f.default !== undefined) d[f.id] = f.default;
    return d;
  }, [fields]);
  const [values, setValues] = useState<Record<string, ParamValue>>(defaults);
  useEffect(() => setValues((v) => ({ ...defaults, ...v })), [defaults]);
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    ref.current?.showModal?.();
  }, []);
  const has = (k: string) => fields.some((f) => f.id === k);
  const errors = Object.fromEntries(fields.map((f) => [f.id, fieldError(f, values[f.id])]));
  const ok = Object.values(errors).every((e) => e === null) && !!id;
  const preview = vs ? mixPreview(vs, values) : null;
  const warnings = injectWarnings(values, vs);
  const groups = [...new Set(fields.map((f) => f.group))];
  const inject = () => {
    if (!ok || !id) return;
    const c: Record<string, string | number> = { kind: 'injectMagma', volcanoId: id };
    for (const f of fields) {
      const v = values[f.id];
      if (typeof v === 'number' || typeof v === 'string') c[f.id] = v;
      else if (typeof v === 'boolean') c[f.id] = v ? 1 : 0;
    }
    command(c as unknown as SimCommand);
    useStore.getState().toast(`Injecting ${formatVolume(Number(values.volumeM3))} of magma into ${name}`, 'info');
    onClose();
  };
  return (
    <dialog ref={ref} className="panel dialog inject" aria-labelledby="inject-title" onClose={onClose} onCancel={onClose}>
      <form
        method="dialog"
        onSubmit={(e) => {
          e.preventDefault();
          inject();
        }}
      >
        <h2 id="inject-title">Add magma to {name}</h2>
        <p className="muted small">A new batch of magma rises into the chamber and mixes with what is there. More pressure means more quakes, swelling and eventually an eruption.</p>
        {has('temperatureC') && (
          <div className="presets" role="group" aria-label="Magma type">
            {vs && (
              <button type="button" title="Same temperature and composition as the magma in the chamber now" onClick={() => setValues({ ...values, temperatureC: Math.round(vs.chamber.temperatureC), silicaWt: Number(vs.chamber.silicaWt.toFixed(1)), waterWt: Number(vs.chamber.waterWt.toFixed(1)) })}>
                Like the chamber
              </button>
            )}
            {MAGMA_PRESETS.map((p) => (
              <button type="button" key={p.name} title={p.help} onClick={() => setValues({ ...values, ...Object.fromEntries(Object.entries(p.values).filter(([k]) => has(k))) })}>
                {p.name}
              </button>
            ))}
          </div>
        )}
        {groups.map((g) => (
          <fieldset key={g}>
            {groups.length > 1 && <legend>{g}</legend>}
            {fields
              .filter((f) => f.group === g)
              .map((f) => (
                <div className="field" key={f.id}>
                  <label htmlFor={`p-${f.id}`} title={f.help}>
                    {f.label}
                  </label>
                  <ParamInput spec={f} value={values[f.id]} invalid={!!errors[f.id]} onChange={(v) => setValues({ ...values, [f.id]: v })} />
                  {errors[f.id] ? <span className="field-error">{errors[f.id]}</span> : f.help ? <span className="muted small">{f.help}</span> : null}
                </div>
              ))}
          </fieldset>
        ))}
        {preview && vs && (
          <div className="mix" aria-live="polite">
            <b>After mixing</b> <span className="muted small">(new magma is {(preview.fraction * 100).toPrecision(2)}% of the chamber; rough estimate)</span>
            <table>
              <thead>
                <tr>
                  <th />
                  <th>now</th>
                  <th>after</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>Temperature</td>
                  <td>{vs.chamber.temperatureC.toFixed(0)} °C</td>
                  <td>{preview.temperatureC.toFixed(0)} °C</td>
                </tr>
                <tr>
                  <td>Silica</td>
                  <td>{vs.chamber.silicaWt.toFixed(1)} wt%</td>
                  <td>{preview.silicaWt.toFixed(1)} wt%</td>
                </tr>
                <tr>
                  <td>Water</td>
                  <td>{vs.chamber.waterWt.toFixed(2)} wt%</td>
                  <td>{preview.waterWt.toFixed(2)} wt%</td>
                </tr>
              </tbody>
            </table>
          </div>
        )}
        {warnings.length > 0 && (
          <ul className="warnings">
            {warnings.map((w) => (
              <li key={w}>{w}</li>
            ))}
          </ul>
        )}
        {!has('temperatureC') && <p className="muted small">This server only accepts a volume; temperature and composition use the volcano's configured recharge magma.</p>}
        <div className="dialog-actions">
          <button type="button" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="primary" disabled={!ok}>
            Inject {Number.isFinite(Number(values.volumeM3)) ? formatVolume(Number(values.volumeM3)) : ''}
          </button>
        </div>
      </form>
    </dialog>
  );
}

const TOOL_HINT: Partial<Record<Tool, string>> = {
  water: 'Click the map to pour water there.',
  dig: 'Click the map to dig a pit there.',
  section: 'Click points on the map to draw a cross-section line, then open “Cross-section” and press Cut.',
};

/** Volcano actions and map tools, bottom-left over the 3D view. */
export function ActionBar({ world }: { world: WorldInfo }) {
  const { id, vs } = useVolcano(world);
  const tool = useStore((s) => s.tool);
  const waterVolume = useStore((s) => s.waterVolume);
  const digRadius = useStore((s) => s.digRadius);
  const digDepth = useStore((s) => s.digDepth);
  const set = useStore((s) => s.set);
  const replay = useStore((s) => s.clock?.replay ?? false);
  const [injecting, setInjecting] = useState(false);
  const [more, setMore] = useState(false);
  const erupting = (vs?.chamber.eruptionRate ?? 0) > 0 || vs?.alert.level === 'ERUPTING';
  const pick = (t: Tool) => {
    set({ tool: tool === t ? 'orbit' : t });
    setMore(false);
  };
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && useStore.getState().tool !== 'orbit') set({ tool: 'orbit' });
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [set]);
  if (!id) return null;
  return (
    <>
      <div className="panel action-bar" role="toolbar" aria-label="Volcano actions">
        {erupting ? (
          <button disabled={replay} title="End the eruption now" onClick={() => command({ kind: 'stopEruption', volcanoId: id })}>
            ■ Stop eruption
          </button>
        ) : (
          <button disabled={replay} title="Open a vent and start an eruption now, whatever the pressure" onClick={() => command({ kind: 'startEruption', volcanoId: id })}>
            ▲ Start eruption
          </button>
        )}
        <button disabled={replay} title="Add a batch of magma with chosen temperature and composition" onClick={() => setInjecting(true)}>
          ＋ Add magma…
        </button>
        <div className="more-menu">
          <button aria-haspopup="menu" aria-expanded={more} title="More actions and map tools" onClick={() => setMore(!more)}>
            Tools ▾
          </button>
          {more && (
            <div className="menu up" role="menu" onMouseLeave={() => setMore(false)}>
              <button role="menuitem" disabled={replay} title="Force magma to crack a path upwards (a dike)" onClick={() => {
                command({ kind: 'forceDike', volcanoId: id });
                setMore(false);
              }}>
                ⤴ Push magma up (dike)
              </button>
              <button role="menuitem" className={tool === 'water' ? 'on' : ''} disabled={replay} onClick={() => pick('water')}>
                💧 Pour water
              </button>
              <button role="menuitem" className={tool === 'dig' ? 'on' : ''} disabled={replay} onClick={() => pick('dig')}>
                ⛏ Dig a pit
              </button>
              <button role="menuitem" className={tool === 'section' ? 'on' : ''} onClick={() => pick('section')}>
                ✎ Draw a cross-section line
              </button>
            </div>
          )}
        </div>
      </div>
      {tool !== 'orbit' && (
        <div className="panel tool-hint" role="status">
          <span>{TOOL_HINT[tool]}</span>
          {tool === 'water' && (
            <label>
              Volume <input type="number" value={waterVolume} min={100} step={1000} onChange={(e) => set({ waterVolume: Number(e.target.value) })} /> m³
            </label>
          )}
          {tool === 'dig' && (
            <>
              <label>
                Radius <input type="number" value={digRadius} min={5} step={5} onChange={(e) => set({ digRadius: Number(e.target.value) })} /> m
              </label>
              <label>
                Depth <input type="number" value={digDepth} min={1} step={5} onChange={(e) => set({ digDepth: Number(e.target.value) })} /> m
              </label>
            </>
          )}
          <button onClick={() => set({ tool: 'orbit' })}>Done [Esc]</button>
        </div>
      )}
      {injecting && <InjectDialog world={world} onClose={() => setInjecting(false)} />}
    </>
  );
}

