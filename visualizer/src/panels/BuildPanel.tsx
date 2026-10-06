import { useEffect, useMemo, useState } from 'react';
import { Cable, Crosshair, Flame, MapPin, Plus, Redo2, Trash2, Undo2 } from 'lucide-react';
import { Hint, SimpleSelect } from '@/components/fields';
import { PanelSection, Tip } from '@/components/tip';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Kbd } from '@/components/ui/kbd';
import { Label } from '@/components/ui/label';
import { cn } from '@/lib/utils';
import { useCamera } from '../camera/cameraStore';
import type { ConfigResult, ParamSpec, ParamValue, WorldInfo, XY } from '../protocol/messages';
import { displayZ } from '../scene/Terrain';
import type { EntityView } from '../store/entities';
import { useStore } from '../store/store';
import { worldExtent } from '../util/world';
import { connectChambers, editChamber, placeDraftChamber, previewChamberEdit, redoBuild, removeChamber, removeConnection, undoBuild } from './buildActions';
import { fieldValue, positionReadout, radiusFromVolume, type BuildDraft } from './builder';
import { formatVolume } from './events';
import { fieldError } from './inject';
import { ParamInput } from './ParamInput';

/** Chambers and pathways of a volcano, from the entities the server publishes. */
function plumbingOf(entities: Record<string, EntityView>, volcanoId: string) {
  const chambers: EntityView[] = [];
  const links: EntityView[] = [];
  for (const e of Object.values(entities)) {
    if (e.volcanoId !== volcanoId || e.removedAt !== undefined) continue;
    if (e.kind === 'chamber') chambers.push(e);
    else if (e.kind === 'connection') links.push(e);
  }
  const id = (e: EntityView) => String(e.props.chamberId ?? 'main');
  chambers.sort((a, b) => (id(a) === 'main' ? -1 : id(b) === 'main' ? 1 : id(a).localeCompare(id(b))));
  links.sort((a, b) => a.id.localeCompare(b.id));
  return { chambers, links, chamberId: id };
}

const fmt = (m: number) => `${Math.round(m).toLocaleString()} m`;

/**
 * Build mode: the world's volcanoes with their chambers and pathways (add, edit, connect, remove),
 * the form of the chamber being placed or edited (in sync with its ghost and gizmos in the 3D view),
 * and undo/redo. Every edit goes through the server, which says what it does.
 */
export function BuildPanel({ world }: { world: WorldInfo }) {
  const entities = useStore((s) => s.entities);
  const draft = useStore((s) => s.buildDraft);
  const connect = useStore((s) => s.connectDraft);
  const history = useStore((s) => s.buildHistory);
  const set = useStore((s) => s.set);
  const ext = worldExtent(world);
  const mapCentre: XY = [(ext.minX + ext.maxX) / 2, (ext.minY + ext.maxY) / 2];

  const startChamber = (volcanoId: string | null) => {
    const v = volcanoId ? world.volcanoes.find((x) => x.id === volcanoId) : undefined;
    const at: XY = v ? [v.chamber.center[0], v.chamber.center[1]] : viewCentre(mapCentre);
    set({ buildDraft: { kind: 'chamber', volcanoId, at, values: {} }, connectDraft: null });
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center gap-1.5">
        <Tip content={<span>Place a magma chamber; its volcano forms from what it erupts <Kbd>click the map</Kbd></span>}>
          <Button size="sm" onClick={() => startChamber(null)}>
            <Flame /> New volcano
          </Button>
        </Tip>
        <span className="flex-1" />
        <Tip content={history.undo.length ? <span>Undo: {history.undo.at(-1)!.label} <Kbd>Ctrl Z</Kbd></span> : 'Nothing to undo'}>
          <Button size="icon-sm" variant="ghost" aria-label="Undo" disabled={!history.undo.length} onClick={() => void undoBuild()}>
            <Undo2 />
          </Button>
        </Tip>
        <Tip content={history.redo.length ? <span>Redo: {history.redo.at(-1)!.label} <Kbd>Ctrl Y</Kbd></span> : 'Nothing to redo'}>
          <Button size="icon-sm" variant="ghost" aria-label="Redo" disabled={!history.redo.length} onClick={() => void redoBuild()}>
            <Redo2 />
          </Button>
        </Tip>
      </div>

      {draft && <ChamberForm world={world} draft={draft} />}
      {connect && <ConnectForm world={world} />}

      {world.volcanoes.length === 0 && !draft && (
        <Hint>
          Nothing here yet. Place a magma chamber below the ground or the sea floor (New volcano, or the Tools menu and a click on the map) and
          let its eruptions build whatever forms.
        </Hint>
      )}
      {world.volcanoes.map((v) => {
        const p = plumbingOf(entities, v.id);
        return (
          <PanelSection key={v.id} title={v.name}>
            <ul className="flex flex-col gap-1" aria-label={`${v.name} plumbing`}>
              {p.chambers.map((c) => {
                const cid = p.chamberId(c);
                return (
                  <li key={c.id} className="flex items-center gap-1.5 text-sm">
                    <span className="size-2.5 shrink-0 rounded-full bg-orange-500/80" aria-hidden />
                    <button type="button" className="min-w-0 flex-1 truncate text-left hover:underline" onClick={() => useStore.getState().select({ type: 'entity', id: c.id })}>
                      {cid === 'main' ? 'Main chamber' : `Chamber ${cid}`}
                      <span className="text-xs text-muted-foreground">
                        {' '}
                        · {typeof c.props.depthM === 'number' ? fmt(c.props.depthM) : '?'} deep · {typeof c.props.volumeM3 === 'number' ? formatVolume(c.props.volumeM3) : ''}
                      </span>
                    </button>
                    {cid === 'main' && <Badge variant="outline">erupts</Badge>}
                    <Tip content="Move it, change its depth or size">
                      <Button
                        size="icon-xs"
                        variant="ghost"
                        aria-label={`Edit ${cid}`}
                        onClick={() => set({ buildDraft: { kind: 'chamber', volcanoId: v.id, chamberId: cid, at: [c.at[0], c.at[1]], values: { depthM: Number(c.props.depthM ?? 0) } }, connectDraft: null })}
                      >
                        <MapPin />
                      </Button>
                    </Tip>
                    <Tip content={cid === 'main' ? 'Remove the whole volcano (the server asks first)' : 'Remove this chamber and its pathways (the server asks first)'}>
                      <Button size="icon-xs" variant="ghost" aria-label={`Remove ${cid}`} onClick={() => void removeChamber(v.id, cid)}>
                        <Trash2 />
                      </Button>
                    </Tip>
                  </li>
                );
              })}
              {p.links.map((l) => (
                <li key={l.id} className="flex items-center gap-1.5 pl-4 text-sm">
                  <Cable className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
                  <button type="button" className="min-w-0 flex-1 truncate text-left hover:underline" onClick={() => useStore.getState().select({ type: 'entity', id: l.id })}>
                    {String(l.props.from)} → {String(l.props.to)}
                    <span className="text-xs text-muted-foreground">
                      {' '}
                      · {l.props.frozen ? 'frozen' : l.props.open === false ? 'closed' : `${Number(l.props.flowM3PerS ?? 0).toPrecision(2)} m³/s`}
                    </span>
                  </button>
                  <Tip content="Remove this pathway">
                    <Button size="icon-xs" variant="ghost" aria-label={`Remove pathway ${l.props.connectionId}`} onClick={() => void removeConnection(v.id, String(l.props.connectionId))}>
                      <Trash2 />
                    </Button>
                  </Tip>
                </li>
              ))}
            </ul>
            <div className="flex flex-wrap gap-1.5">
              <Button size="xs" variant="secondary" onClick={() => startChamber(v.id)}>
                <Plus /> Add chamber
              </Button>
              <Button size="xs" variant="secondary" disabled={p.chambers.length < 2} onClick={() => set({ connectDraft: { volcanoId: v.id, kind: 'conduit', values: {} }, buildDraft: null })}>
                <Cable /> Connect chambers
              </Button>
            </div>
          </PanelSection>
        );
      })}
    </div>
  );
}

/** Where the camera looks (map metres), else `fallback`. */
function viewCentre(fallback: XY): XY {
  const r = useCamera.getState().readout;
  if (!r) return fallback;
  // the point the camera looks at on the ground plane through its own altitude is a fair map centre
  const ahead = r.aboveGround > 0 && r.pitch < -0.05 ? r.aboveGround / Math.tan(-r.pitch) : 0;
  return [r.x + Math.sin(r.heading) * ahead, r.y + Math.cos(r.heading) * ahead];
}

/** The chamber being placed or edited: position (numbers and map pick), depth, size, magma; readouts. */
function ChamberForm({ world, draft }: { world: WorldInfo; draft: BuildDraft }) {
  const schema = useStore((s) => s.schema);
  const tool = useStore((s) => s.tool);
  const set = useStore((s) => s.set);
  const [busy, setBusy] = useState(false);
  const [preview, setPreview] = useState<ConfigResult | null>(null);
  const editing = draft.chamberId !== undefined;
  const specs: ParamSpec[] = useMemo(() => {
    const all = (draft.volcanoId === null ? schema?.commands.placeChamber : schema?.components?.chamber) ?? [];
    // editing an existing chamber: its place and size here (its magma and supply are live dials in the Inspector)
    return editing ? all.filter((p) => p.id === 'depthM') : all.filter((p) => p.id !== 'x' && p.id !== 'y');
  }, [schema, draft.volcanoId, editing]);
  const update = (d: Partial<BuildDraft>) => set({ buildDraft: { ...draft, ...d } });
  const setValue = (id: string, v: ParamValue) => update({ values: { ...draft.values, [id]: v } });
  const ground = displayZ(world, draft.at[0], draft.at[1], 1, 1);
  const depth = fieldValue(draft.values, specs, 'depthM') ?? 0;
  const volume = fieldValue(draft.values, schema?.commands.placeChamber, 'volumeM3') ?? 0;
  const volcano = draft.volcanoId ? world.volcanoes.find((v) => v.id === draft.volcanoId) : undefined;
  const origin: XY | undefined = volcano ? [volcano.chamber.center[0], volcano.chamber.center[1]] : undefined;
  const readout = positionReadout(draft.at, depth, ground, world.hasSea === false ? undefined : world.seaLevel, origin);
  const errors = specs.map((p) => (draft.values[p.id] !== undefined ? fieldError(p, draft.values[p.id]) : null));
  const posSpec = (id: 'x' | 'y') => schema?.commands.placeChamber?.find((p) => p.id === id);
  const groups = [...new Set(specs.map((p) => p.group))];
  const presets = schema?.magmaPresets ?? [];
  useEffect(() => setPreview(null), [draft.at, draft.values]);

  const submit = async () => {
    setBusy(true);
    try {
      if (editing) await editChamber(draft.volcanoId!, draft.chamberId!, draft.at, draft.values);
      else await placeDraftChamber(draft.volcanoId, draft.at, draft.values);
    } finally {
      setBusy(false);
    }
    if (useStore.getState().buildDraft === draft) set({ buildDraft: null });
  };
  const ask = async () => setPreview(await previewChamberEdit(draft.volcanoId!, draft.chamberId!, draft.at, draft.values));

  return (
    <section className="flex flex-col gap-3 rounded-lg border border-orange-500/40 p-3" aria-label="Chamber being placed">
      <div className="flex items-center gap-2">
        <Flame className="size-4 text-orange-400" />
        <strong className="flex-1 text-sm">{editing ? `${draft.chamberId === 'main' ? 'Main chamber' : `Chamber ${draft.chamberId}`} of ${volcano?.name ?? draft.volcanoId}` : 'New magma chamber'}</strong>
        <Tip content={<span>Cancel <Kbd>Esc</Kbd></span>}>
          <Button size="xs" variant="ghost" onClick={() => set({ buildDraft: null, tool: tool === 'chamber' ? 'orbit' : tool })}>
            Cancel
          </Button>
        </Tip>
      </div>
      {!editing && (
        <div className="flex items-center justify-between gap-2">
          <Label className="font-normal">Into</Label>
          <SimpleSelect
            label="Target volcano"
            value={draft.volcanoId ?? ''}
            onChange={(v) => update({ volcanoId: v === '' ? null : v })}
            options={[['', 'A new volcano'] as const, ...world.volcanoes.map((v) => [v.id, `${v.name}: a further chamber`] as const)]}
          />
        </div>
      )}
      <div className="grid grid-cols-[auto_1fr_auto] items-center gap-x-2 gap-y-1.5 text-sm">
        {(['x', 'y'] as const).map((k, i) => (
          <PositionField
            key={k}
            label={posSpec(k)?.label ?? (k === 'x' ? 'East' : 'North')}
            help={posSpec(k)?.help}
            value={draft.at[i]}
            onChange={(v) => update({ at: k === 'x' ? [v, draft.at[1]] : [draft.at[0], v] })}
          />
        ))}
      </div>
      <div className="flex flex-wrap items-center gap-1.5">
        <Tip content="Click the map to put the chamber there (the ghost follows); drag the ghost to move it, Shift-drag for depth, the knob for size">
          <Button size="xs" variant={tool === 'chamber' ? 'default' : 'secondary'} aria-pressed={tool === 'chamber'} onClick={() => set({ tool: tool === 'chamber' ? 'orbit' : 'chamber' })}>
            <Crosshair /> Pick on the map
          </Button>
        </Tip>
        <Button size="xs" variant="ghost" onClick={() => useCamera.getState().requestCamera({ kind: 'flyTo', at: draft.at })}>
          Show
        </Button>
      </div>
      <dl className="grid grid-cols-[1fr_auto] gap-x-3 text-xs text-muted-foreground">
        <dt>Ground there</dt>
        <dd className="text-right tabular-nums">{fmt(ground)}</dd>
        <dt>Chamber centre</dt>
        <dd className="text-right tabular-nums">
          {fmt(readout.centreZ)}
          {readout.belowSea !== undefined && ` (${fmt(Math.abs(readout.belowSea))} ${readout.belowSea >= 0 ? 'below' : 'above'} sea level)`}
        </dd>
        {volume > 0 && (
          <>
            <dt>Radius (as a sphere)</dt>
            <dd className="text-right tabular-nums">{fmt(radiusFromVolume(volume))}</dd>
          </>
        )}
        {readout.distance !== undefined && (
          <>
            <dt>From the volcano's main chamber</dt>
            <dd className="text-right tabular-nums">{fmt(readout.distance)}</dd>
          </>
        )}
      </dl>
      {!editing && presets.length > 0 && (
        <div className="flex flex-wrap gap-1" aria-label="Magma presets">
          {presets.map((p) => (
            <Tip key={p.id} content={p.help}>
              <Button size="xs" variant="outline" onClick={() => update({ values: { ...draft.values, ...p.values } })}>
                {p.name}
              </Button>
            </Tip>
          ))}
        </div>
      )}
      {groups.map((g) => (
        <section key={g} className="flex flex-col gap-2">
          <span className="text-xs font-medium text-muted-foreground">{g}</span>
          {specs
            .filter((p) => p.group === g)
            .map((p) => {
              const k = specs.indexOf(p);
              return (
                <div key={p.id} className="flex flex-col gap-1">
                  <Label htmlFor={`b-${p.id}`} className="font-normal" title={p.help}>
                    {p.label}
                    {p.unit ? ` (${p.unit})` : ''}
                  </Label>
                  <ParamInput spec={p} value={draft.values[p.id] ?? p.default} invalid={errors[k] !== null} onChange={(v) => setValue(p.id, v)} />
                  {errors[k] && <p className="text-xs text-destructive">{errors[k]}</p>}
                </div>
              );
            })}
        </section>
      ))}
      {preview && (
        <ul className={cn('list-disc pl-5 text-xs', preview.plan === 'reinit' ? 'text-amber-300' : 'text-muted-foreground')}>
          {(preview.consequences ?? []).map((c) => (
            <li key={c.message}>{c.message}</li>
          ))}
          {(preview.errors ?? []).map((e) => (
            <li key={e.path} className="text-destructive">
              {e.message}
            </li>
          ))}
        </ul>
      )}
      <div className="flex gap-1.5">
        {editing && (
          <Button size="sm" variant="outline" disabled={busy} onClick={() => void ask()}>
            What will this do?
          </Button>
        )}
        <span className="flex-1" />
        <Button size="sm" disabled={busy || errors.some((e) => e !== null) || !schema} onClick={() => void submit()}>
          {editing ? 'Apply' : 'Place chamber'}
        </Button>
      </div>
    </section>
  );
}

function PositionField({ label, help, value, onChange }: { label: string; help?: string; value: number; onChange: (v: number) => void }) {
  const [text, setText] = useState(String(Math.round(value)));
  useEffect(() => setText(String(Math.round(value))), [value]);
  return (
    <>
      <Label className="font-normal" title={help}>
        {label}
      </Label>
      <Input
        type="number"
        className="h-7"
        aria-label={`${label} (m)`}
        value={text}
        step={10}
        onChange={(e) => {
          setText(e.target.value);
          const v = Number(e.target.value);
          if (e.target.value !== '' && Number.isFinite(v)) onChange(v);
        }}
      />
      <span className="text-xs text-muted-foreground">m</span>
    </>
  );
}

/** A pathway being drawn between two chambers of a volcano. */
function ConnectForm({ world }: { world: WorldInfo }) {
  const draft = useStore((s) => s.connectDraft)!;
  const entities = useStore((s) => s.entities);
  const selection = useStore((s) => s.selection);
  const schema = useStore((s) => s.schema);
  const set = useStore((s) => s.set);
  const [busy, setBusy] = useState(false);
  const p = plumbingOf(entities, draft.volcanoId);
  const options = p.chambers.map((c) => [p.chamberId(c), p.chamberId(c) === 'main' ? 'Main chamber' : `Chamber ${p.chamberId(c)}`] as const);
  const specs = (schema?.components?.connection ?? []).filter((s) => s.id !== 'kind' && (draft.kind === 'conduit' ? s.id !== 'widthM' && s.id !== 'strikeLengthM' : s.id !== 'radiusM'));
  const update = (d: Partial<typeof draft>) => set({ connectDraft: { ...draft, ...d } });
  // picking chambers in the 3D view fills the source, then the target
  useEffect(() => {
    if (selection?.type !== 'entity') return;
    const e = entities[selection.id];
    if (!e || e.kind !== 'chamber' || e.volcanoId !== draft.volcanoId) return;
    const cid = p.chamberId(e);
    const d = useStore.getState().connectDraft;
    if (!d) return;
    if (!d.from) set({ connectDraft: { ...d, from: cid } });
    else if (!d.to && cid !== d.from) set({ connectDraft: { ...d, to: cid } });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selection]);
  const name = world.volcanoes.find((v) => v.id === draft.volcanoId)?.name ?? draft.volcanoId;
  const ready = draft.from && draft.to && draft.from !== draft.to;
  return (
    <section className="flex flex-col gap-3 rounded-lg border border-sky-500/40 p-3" aria-label="Pathway being drawn">
      <div className="flex items-center gap-2">
        <Cable className="size-4 text-sky-400" />
        <strong className="flex-1 text-sm">Connect chambers of {name}</strong>
        <Button size="xs" variant="ghost" onClick={() => set({ connectDraft: null })}>
          Cancel
        </Button>
      </div>
      <Hint>Magma flows from the first to the second when their pressure difference beats the magma column between them. Pick the chambers here or click them in the 3D view.</Hint>
      <div className="grid grid-cols-[auto_1fr] items-center gap-2 text-sm">
        <Label className="font-normal">From</Label>
        <SimpleSelect label="From chamber" value={draft.from ?? ''} onChange={(v) => update({ from: v })} options={options} />
        <Label className="font-normal">To</Label>
        <SimpleSelect label="To chamber" value={draft.to ?? ''} onChange={(v) => update({ to: v })} options={options} />
        <Label className="font-normal">Kind</Label>
        <SimpleSelect label="Pathway kind" value={draft.kind} onChange={(v) => update({ kind: v as 'conduit' | 'dike' })} options={[['conduit', 'Conduit (pipe)'] as const, ['dike', 'Dike (sheet)'] as const]} />
      </div>
      {specs.map((s) => (
        <div key={s.id} className="flex flex-col gap-1">
          <Label className="font-normal" title={s.help}>
            {s.label}
            {s.unit ? ` (${s.unit})` : ''}
          </Label>
          <ParamInput spec={s} value={draft.values[s.id] ?? s.default} onChange={(v) => update({ values: { ...draft.values, [s.id]: v } })} />
        </div>
      ))}
      <div className="flex">
        <span className="flex-1" />
        <Button
          size="sm"
          disabled={!ready || busy}
          onClick={async () => {
            setBusy(true);
            try {
              const r = await connectChambers(draft.volcanoId, draft.from!, draft.to!, draft.kind, draft.values);
              if (r) set({ connectDraft: null });
            } finally {
              setBusy(false);
            }
          }}
        >
          Connect
        </Button>
      </div>
    </section>
  );
}
