import { useEffect, useState, type ReactNode } from 'react';
import { ArrowUpFromDot, ChevronRight, Crosshair, Droplets, Pickaxe, Plus, RefreshCw, Ruler, Square, Trash2, Triangle, X } from 'lucide-react';
import { Tip } from '@/components/tip';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { Kbd } from '@/components/ui/kbd';
import { Label } from '@/components/ui/label';
import { Separator } from '@/components/ui/separator';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { cn } from '@/lib/utils';
import { useCamera } from '../camera/cameraStore';
import { command, inspect, removeVolcano } from '../net/connection';
import type { EntityProp, InspectionMessage, WorldInfo } from '../protocol/messages';
import { sectionThrough, selectionAnchor } from '../scene/picking';
import { KIND_LABEL, entityColor, formatPlace, type EntityView, type Selection } from '../store/entities';
import { useStore } from '../store/store';
import { FEATURE_COLORS } from '../util/color';
import { formatSimTime, worldExtent } from '../util/world';
import { DESTRUCTIVE, contextActions, contextToggles, ventLifecycle, type ContextAction, type ContextToggle, type VentLifecycle } from './actions';
import { budgetVerdict, type BudgetState } from './budget';
import { formatVolume } from './events';
import { buildPanel, type BuiltPanel } from './objectPanel';
import { OverridesBanner, PanelTabBody, Skeleton, Waiting } from './ObjectPanel';
import { OVERLAY } from './Overlay';
import { showServerResult } from './serverResult';
import { useParamEdits } from './ParamRow';
import { HIDDEN_PROPS, formatProp, propLabel } from './props';

/** The selection's column is asked about again this often while it stays selected (ms). */
const REFRESH_MS = 2000;
/** A ground request unanswered this long offers a retry (ms). */
const GROUND_TIMEOUT_MS = 8000;

/** Where to ask the server about: the selection's map position, if it has one on the map. */
function inspectAt(sel: Selection | null, e: EntityView | undefined): [number, number] | null {
  if (!sel) return null;
  if (sel.type === 'point') return sel.at;
  if (sel.type === 'quake') return [sel.event.hypocenter[0], sel.event.hypocenter[1]];
  if (!e || e.kind === 'lavaField' || e.kind === 'world') return null;
  return [e.at[0], e.at[1]];
}

/** The tab last shown per kind of object, so moving between two chambers keeps the tab. */
const lastTab = new Map<string, string>();

interface TabDef {
  id: string;
  title: string;
  body: ReactNode;
}

/**
 * Properties of what is selected in the 3D view or the Entities panel, live, laid out by the server's
 * panel spec for its kind: tabs of measured and derived values with the object's own settings (dials,
 * then "More" and "Solver internals" collapsed), plus the ground column under it.
 */
export function Inspector({ world, sheet = false }: { world: WorldInfo; sheet?: boolean }) {
  const selection = useStore((s) => s.selection);
  const entity = useStore((s) => (s.selection?.type === 'entity' ? s.entities[s.selection.id] : undefined));
  const worldEntity = useStore((s) => s.entities.world);
  const schema = useStore((s) => s.schema);
  const replay = useStore((s) => s.clock?.replay ?? false);
  const { pending, edit } = useParamEdits();
  const at = inspectAt(selection, entity);
  const ax = at?.[0];
  const ay = at?.[1];
  const [retry, setRetry] = useState(0);

  useEffect(() => {
    if (ax === undefined || ay === undefined) return;
    inspect(ax, ay);
    const id = window.setInterval(() => {
      const c = useStore.getState().clock;
      if (c && c.mode !== 'PAUSED' && !document.hidden) inspect(ax, ay);
    }, REFRESH_MS);
    return () => window.clearInterval(id);
  }, [ax, ay, retry]);

  // the object whose panel shows: the selected entity, or the world for a point on the map
  const obj =
    selection?.type === 'entity' && entity
      ? { kind: entity.kind, owners: entity.paramOwners ?? [], props: entity.props as Record<string, EntityProp> }
      : selection?.type === 'point'
        ? { kind: 'world', owners: worldEntity?.paramOwners ?? schema?.objectOwners?.world ?? ['world'], props: worldEntity?.props as Record<string, EntityProp> | undefined }
        : null;
  const kindKey = selection?.type === 'entity' ? (entity?.kind ?? 'gone') : (selection?.type ?? 'none');
  const [tab, setTabState] = useState<string>(() => lastTab.get(kindKey) ?? '');
  const setTab = (t: string) => {
    lastTab.set(kindKey, t);
    setTabState(t);
  };
  useEffect(() => setTabState(lastTab.get(kindKey) ?? ''), [kindKey]);

  if (!selection) return null;
  const close = () => useStore.getState().select(null);
  const frame = () => useCamera.getState().requestCamera({ kind: 'frameSelection' });
  const cut = () => {
    const s = useStore.getState();
    const ext = worldExtent(world);
    const span = Math.max(2000, Math.min(Math.max(ext.maxX - ext.minX, ext.maxY - ext.minY) * 0.5, 12000));
    const line = sectionThrough(selection, s.entities, entity?.kind === 'chamber' ? span * 1.5 : span);
    if (line) s.set({ sectionPolyline: line, drawer: 'section', tool: 'orbit' });
  };

  let title: string;
  let kind: string;
  let color = '#ffe14d';
  if (selection.type === 'entity') {
    title = entity?.label ?? 'Gone';
    kind = entity ? (KIND_LABEL[entity.kind] ?? entity.kind) : 'Removed';
    if (entity) color = entityColor(entity, FEATURE_COLORS);
  } else if (selection.type === 'quake') {
    title = `M ${selection.event.magnitude.toFixed(1)} ${selection.event.type} earthquake`;
    kind = 'Earthquake';
    color = '#ffd166';
  } else {
    title = 'Ground';
    kind = 'Point on the map · the world’s settings';
  }
  const anchor = selectionAnchor(selection, useStore.getState().entities);
  const toggles = contextToggles(selection, useStore.getState().entities).filter((t) => t.id !== 'blockDikes' || !schema?.params.some((p) => p.id.endsWith('.dikes.blocked')));
  const layout = obj ? schema?.objectPanels?.[obj.kind] : undefined;
  const panel: BuiltPanel | null = obj && schema && layout ? buildPanel(schema, obj.kind, obj.owners, obj.props) : null;
  const vid = entity?.volcanoId;

  const widget = (id: string): ReactNode => {
    switch (id) {
      case 'magmaBudget':
        return vid ? <MagmaBudgetView volcanoId={vid} /> : null;
      case 'landscape':
        return vid ? <LandscapeSummary volcanoId={vid} /> : null;
      case 'ventState':
      case 'volcanoState':
        return entity ? <StateView e={entity} toggles={toggles} replay={replay} /> : null;
      case 'weatherNow':
        return <WeatherNow />;
      default:
        return null;
    }
  };

  const tabs: TabDef[] = [];
  if (selection.type === 'quake') tabs.push({ id: 'overview', title: 'Overview', body: <QuakeProps q={selection.event} /> });
  else if (selection.type === 'entity' && !entity) tabs.push({ id: 'overview', title: 'Overview', body: <p className="text-muted-foreground">This no longer exists.</p> });
  else if (panel && obj) {
    panel.tabs.forEach((t, k) =>
      tabs.push({
        id: t.id,
        title: t.title,
        body: (
          <>
            {schema && !schema.tunable && k === 0 && t.params.primary.length + t.params.more.length > 0 && <p className="text-xs text-muted-foreground">{schema.reason ?? 'The settings of this world cannot be changed.'}</p>}
            <PanelTabBody tab={t} kind={obj.kind} owners={obj.owners} props={obj.props} widget={widget} pending={pending} onEdit={edit} onTab={setTab} entityId={entity?.id} />
            {k === 0 && entity && <AllValues e={entity} />}
          </>
        ),
      }),
    );
  } else if (entity) {
    // no layout for this kind (or the schema is still on its way): the entity's own values
    tabs.push({
      id: 'overview',
      title: 'Overview',
      body: (
        <>
          {toggles.length > 0 && <Toggles toggles={toggles} replay={replay} />}
          <EntityProps e={entity} />
          {!schema && (entity.paramOwners?.length ?? 0) > 0 && <Waiting>Loading its settings…</Waiting>}
        </>
      ),
    });
  } else if (selection.type === 'point' && !schema) {
    tabs.push({ id: 'world', title: 'World', body: <><Waiting>Loading the world’s settings…</Waiting><Skeleton /></> });
  }
  if (at) {
    const groundTab = { id: 'ground', title: 'Ground', body: <GroundTab at={at} world={world} onRetry={() => setRetry((r) => r + 1)} /> };
    if (selection.type === 'point') tabs.unshift(groundTab);
    else tabs.push(groundTab);
  }

  return (
    <aside className={sheet ? 'flex min-h-0 flex-1 flex-col text-sm' : cn(OVERLAY, 'flex max-h-full min-h-0 w-80 max-w-full flex-col text-sm')} aria-label="Inspector">
      <div className="flex shrink-0 items-start gap-2 p-3 pb-2">
        <span className="mt-1 size-3 shrink-0 rounded-full ring-1 ring-black/40" style={{ background: color }} aria-hidden />
        <div className="min-w-0 flex-1">
          <h2 className="truncate font-semibold" title={title}>
            {title}
          </h2>
          <p className="text-xs text-muted-foreground">
            {kind}
            {anchor && selection.type !== 'point' && ` · ${formatPlace(anchor)}`}
          </p>
        </div>
        <Tip content={<span>Close <Kbd>Esc</Kbd></span>}>
          <Button variant="ghost" size="icon-xs" aria-label="Clear selection" onClick={close}>
            <X />
          </Button>
        </Tip>
      </div>
      <ActionRow onFrame={frame} onSection={cut} />
      {entity?.related && entity.related.length > 0 && <RelatedRow related={entity.related} />}
      {panel && <OverridesBanner panel={panel} onTab={setTab} />}
      <Separator />
      <InspectorTabs key={kindKey} tabs={tabs} tab={tab} onTab={setTab} />
    </aside>
  );
}

/** Links to the objects related to this one (its chamber, the dikes feeding it, ...), from the server. */
function RelatedRow({ related }: { related: { id: string; kind: string; label: string }[] }) {
  const entities = useStore((s) => s.entities);
  return (
    <nav className="flex shrink-0 items-center gap-1.5 overflow-x-auto px-3 pb-2 text-xs [scrollbar-width:none]" aria-label="Related">
      <span className="shrink-0 text-muted-foreground">Related</span>
      {related.map((r) => (
        <Tip key={r.id} content={`Select ${r.label} and show its properties`}>
          <Button
            size="xs"
            variant="outline"
            className="h-6 shrink-0 gap-1 px-1.5"
            disabled={!entities[r.id]}
            onClick={() => useStore.getState().select({ type: 'entity', id: r.id })}
          >
            <span className="size-2 rounded-full" style={{ background: entities[r.id] ? entityColor(entities[r.id], FEATURE_COLORS) : '#888' }} aria-hidden />
            {r.label}
          </Button>
        </Tip>
      ))}
    </nav>
  );
}

/** The ground column under the selection; shown at once, with a spinner until the server answers. */
function GroundTab({ at, world, onRetry }: { at: [number, number]; world: WorldInfo; onRetry: () => void }) {
  const inspection = useStore((s) => s.inspection);
  const pendingId = useStore((s) => s.inspectPending);
  const [since, setSince] = useState(() => Date.now());
  const [now, setNow] = useState(() => Date.now());
  const fresh = inspection && Math.hypot(inspection.at[0] - at[0], inspection.at[1] - at[1]) <= world.cellSize * 1.5 ? inspection : null;
  useEffect(() => {
    setSince(Date.now());
  }, [at[0], at[1]]);
  useEffect(() => {
    if (fresh) return;
    const t = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, [fresh]);
  if (fresh && fresh.inside) return <Column c={fresh} world={world} />;
  if (fresh && !fresh.inside) return <p className="text-muted-foreground">Outside the simulated area.</p>;
  const late = now - since > GROUND_TIMEOUT_MS || (pendingId === null && now - since > 1500);
  if (late) {
    return (
      <div className="flex flex-col items-start gap-2 text-xs text-muted-foreground">
        <p>The server has not answered about the ground here yet.</p>
        <Button
          size="xs"
          variant="secondary"
          onClick={() => {
            setSince(Date.now());
            onRetry();
          }}
        >
          <RefreshCw /> Try again
        </Button>
      </div>
    );
  }
  return (
    <>
      <Waiting>Reading the ground here…</Waiting>
      <Skeleton rows={5} />
    </>
  );
}

/** Every value the server sends for the object, collapsed (the tabs show the curated ones). */
function AllValues({ e }: { e: EntityView }) {
  return (
    <Collapsible className="rounded-md border">
      <CollapsibleTrigger className="group flex w-full items-center gap-1.5 px-2 py-1.5 text-left text-xs font-medium text-muted-foreground hover:bg-muted/50">
        <ChevronRight className="size-3.5 transition-transform group-data-[panel-open]:rotate-90" />
        All values
      </CollapsibleTrigger>
      <CollapsibleContent className="border-t p-2">
        <EntityProps e={e} />
      </CollapsibleContent>
    </Collapsible>
  );
}

/** A vent's or volcano's state now, with its lasting switches (a vent's seal). */
function StateView({ e, toggles, replay }: { e: EntityView; toggles: ContextToggle[]; replay: boolean }) {
  const removed = e.removedAt !== undefined;
  const rows: [string, string][] = [];
  if (e.kind === 'volcano') {
    rows.push(['Erupting', e.props.erupting ? 'yes' : 'no']);
    if (typeof e.props.regime === 'string') rows.push(['Regime', e.props.regime]);
    if (typeof e.props.eruptions === 'number') rows.push(['Eruptions so far', String(e.props.eruptions)]);
  }
  return (
    <div className="flex flex-col gap-2">
      {(e.kind === 'vent' || e.kind === 'fissure') && !removed && (
        <div>
          <Tip content={VENT_STATE[ventLifecycle(e.props)].tip}>
            <Badge variant="outline" className={VENT_STATE[ventLifecycle(e.props)].className}>
              {VENT_STATE[ventLifecycle(e.props)].label}
            </Badge>
          </Tip>
        </div>
      )}
      {removed && <Badge variant="destructive">removed</Badge>}
      {rows.length > 0 && <PropTable rows={rows} />}
      {toggles.length > 0 && <Toggles toggles={toggles} replay={replay} />}
    </div>
  );
}

/** Weather now (what the world's weather settings are doing). */
function WeatherNow() {
  const w = useStore((s) => s.state?.world);
  if (!w) return <Skeleton rows={2} />;
  const rows: [string, string][] = [['Rain', `${(w.rainMmPerHour ?? 0).toFixed(0)} mm/h`]];
  if (w.wind) rows.push(['Wind', `${w.wind.speed.toFixed(0)} m/s towards ${w.wind.bearingDeg.toFixed(0)}°`]);
  return <PropTable rows={rows} />;
}

/** Checkbox states of the selection (a vent's seal). */
function Toggles({ toggles, replay }: { toggles: ContextToggle[]; replay: boolean }) {
  const flip = (t: ContextToggle, on: boolean) => {
    if (t.id === 'blockDikes') command({ kind: 'blockDikes', volcanoId: t.volcanoId, blocked: on });
    else command({ kind: on ? 'sealVent' : 'unsealVent', volcanoId: t.volcanoId, ventId: t.ventId });
  };
  return (
    <section className="flex flex-col gap-2">
      {toggles.map((t) => (
        <div key={t.id} className="flex items-start gap-2">
          <Checkbox id={`t-${t.id}`} className="mt-0.5" checked={t.checked} disabled={replay || !!('disabled' in t && t.disabled)} onCheckedChange={(on) => flip(t, on === true)} />
          <div className="flex flex-col gap-0.5">
            <Label htmlFor={`t-${t.id}`} className="font-normal">
              {t.label}
            </Label>
            <span className="text-xs text-muted-foreground">{'disabled' in t && t.disabled ? t.disabled : t.help}</span>
          </div>
        </div>
      ))}
    </section>
  );
}

function PropTable({ rows }: { rows: [string, string][] }) {
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
      {rows.map(([k, v]) => (
        <div key={k} className="contents">
          <dt className="text-muted-foreground">{k}</dt>
          <dd className="text-right tabular-nums">{v}</dd>
        </div>
      ))}
    </dl>
  );
}

function Heading({ children }: { children: ReactNode }) {
  return <h3 className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{children}</h3>;
}

/** What erosion and collapse have done to the volcano so far (slope failures, craters, caldera). */
const BUDGET_TONE: Record<BudgetState, string> = {
  recharging: 'text-muted-foreground',
  idle: 'text-muted-foreground',
  settling: 'text-amber-300',
  steady: 'text-emerald-300',
  draining: 'text-sky-300',
  pinned: 'text-red-300',
};

function rate(r: number): string {
  return `${r >= 10 ? r.toFixed(0) : r >= 0.1 ? r.toFixed(2) : r.toPrecision(2)} m³/s`;
}

/** Inflow vs outflow of the chamber and where its pressure is heading. */
function MagmaBudgetView({ volcanoId }: { volcanoId: string }) {
  const ch = useStore((s) => s.state?.volcanoes[volcanoId]?.chamber);
  const b = ch?.budget;
  if (!ch || !b) return null;
  const erupting = ch.eruptionRate > 0;
  const v = budgetVerdict(b, ch.overpressureMPa, ch.ruptureOverpressureMPa, erupting);
  const rows: [string, string][] = [
    ['In: deep supply', rate(b.supplyM3PerS)],
    ['Out: eruption', rate(b.eruptionM3PerS)],
  ];
  if (erupting && b.balanceOverpressureMPa !== undefined && b.balanceOverpressureMPa !== null) rows.push(['Balance pressure', `${b.balanceOverpressureMPa.toFixed(1)} MPa (now ${ch.overpressureMPa.toFixed(1)})`]);
  if (ch.ruptureOverpressureMPa !== undefined) rows.push(["Walls' limit", `${ch.ruptureOverpressureMPa.toFixed(1)} MPa`]);
  rows.push(['Erupted so far', formatVolume(b.eruptedM3)]);
  if (b.intrudedM3 > 0) rows.push(['Into dikes so far', formatVolume(b.intrudedM3)]);
  if (b.wallGrowthM3 > 0) rows.push(['Chamber growth so far', formatVolume(b.wallGrowthM3)]);
  if (b.frozen) rows.push(['Chamber size', 'frozen']);
  if ((b.refusedM3 ?? 0) > 0) rows.push(['Refused by the frozen chamber', formatVolume(b.refusedM3 ?? 0)]);
  return (
    <section className="flex flex-col gap-1.5 text-xs">
      <span className="font-medium text-muted-foreground">Magma budget</span>
      <p className={BUDGET_TONE[v.state]}>{v.text}</p>
      <PropTable rows={rows} />
    </section>
  );
}

function LandscapeSummary({ volcanoId }: { volcanoId: string }) {
  const g = useStore((s) => s.state?.volcanoes[volcanoId]?.geomorph);
  if (!g || (g.failures === 0 && g.craters === 0 && g.calderaSubsidenceM <= 0)) return null;
  const parts: string[] = [];
  if (g.failures > 0) parts.push(`${g.failures.toLocaleString()} slope failure${g.failures > 1 ? 's' : ''} moved ${formatVolume(g.failedM3)}${g.avalanches > 0 ? ` (${g.avalanches} as avalanches or debris flows)` : ''}`);
  if (g.craters > 0) parts.push(`${g.craters} explosion crater${g.craters > 1 ? 's' : ''}, the widest ${Math.round(2 * g.maxCraterRadiusM)} m`);
  if (g.calderaSubsidenceM > 0) parts.push(`crater floor down ${g.calderaSubsidenceM.toFixed(g.calderaSubsidenceM < 10 ? 1 : 0)} m`);
  return (
    <section className="flex flex-col gap-1 text-xs">
      <span className="font-medium text-muted-foreground">Landscape so far</span>
      <p>{parts.join('; ')}.</p>
    </section>
  );
}

/** How a vent's lifecycle state reads, and its colour. */
const VENT_STATE: Record<VentLifecycle, { label: string; tip: string; className: string }> = {
  idle: { label: 'idle', tip: 'Not erupting now; it can erupt again', className: 'text-muted-foreground' },
  active: { label: 'erupting', tip: 'Magma is coming out of it', className: 'border-orange-500/60 text-orange-400' },
  waning: { label: 'waning', tip: 'Its feeder is narrowing as the magma in it cools; it will freeze shut unless flow picks up', className: 'border-amber-500/60 text-amber-400' },
  frozen: { label: 'frozen', tip: 'Its feeder froze shut: it will not erupt again (a new dike can open a new fissure)', className: 'border-sky-500/60 text-sky-400' },
  sealed: { label: 'sealed', tip: 'Plugged: magma leaves through the other open vents', className: 'border-violet-500/60 text-violet-400' },
  removed: { label: 'removed', tip: 'Deleted: no longer a vent', className: 'text-destructive' },
};

function EntityProps({ e }: { e: EntityView }) {
  const rows: [string, string][] = Object.entries(e.props)
    .filter(([k, v]) => !HIDDEN_PROPS.has(k) && v !== null && v !== undefined)
    .map(([k, v]) => [propLabel(k), formatProp(k, v as EntityProp)]);
  const removed = e.removedAt !== undefined;
  return (
    <section className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
        <span>Appeared {formatSimTime(typeof e.props.startedAt === 'number' ? e.props.startedAt : e.createdAt)}</span>
        <span>· updated {formatSimTime(e.updatedAt)}</span>
        {removed && <Badge variant="destructive">removed</Badge>}
        {(e.kind === 'vent' || e.kind === 'fissure') && !removed && (
          <Tip content={VENT_STATE[ventLifecycle(e.props)].tip}>
            <Badge variant="outline" className={VENT_STATE[ventLifecycle(e.props)].className}>
              {VENT_STATE[ventLifecycle(e.props)].label}
            </Badge>
          </Tip>
        )}
      </div>
      {e.kind === 'dike' && e.path && e.path.length > 1 && (
        <p className="text-xs text-muted-foreground">
          From {Math.round(-e.path[0][2])} m below sea level up to {e.path[e.path.length - 1][2] >= 0 ? `${Math.round(e.path[e.path.length - 1][2])} m above` : `${Math.round(-e.path[e.path.length - 1][2])} m below`} sea level
        </p>
      )}
      <PropTable rows={rows} />
    </section>
  );
}

function QuakeProps({ q }: { q: { magnitude: number; type: string; time: number; hypocenter: [number, number, number]; durationSeconds: number; swarm: boolean } }) {
  return (
    <PropTable
      rows={[
        ['Magnitude', `M ${q.magnitude.toFixed(1)}`],
        ['Type', q.type],
        ['When', formatSimTime(q.time)],
        ['Depth', `${Math.round(-q.hypocenter[2])} m below sea level`],
        ['Duration', `${q.durationSeconds.toFixed(1)} s`],
        ['Part of a swarm', q.swarm ? 'yes' : 'no'],
      ]}
    />
  );
}

/** Colour of a material in the layer log: the world's suggestion, else a stable muted hue. */
function materialColor(name: string, world: WorldInfo): string {
  const m = world.materials.find((x) => x.name.toLowerCase() === name.toLowerCase());
  if (m?.color) return m.color;
  let h = 0;
  for (let k = 0; k < name.length; k++) h = (h * 31 + name.charCodeAt(k)) >>> 0;
  return `hsl(${h % 360} 28% ${38 + (h % 17)}%)`;
}

/** The ground column: surface, layers, water and temperature with depth. */
function Column({ c, world }: { c: InspectionMessage; world: WorldInfo }) {
  const rows: [string, string][] = [];
  if (c.surfaceZ !== undefined) rows.push(['Ground elevation', `${Math.round(c.surfaceZ)} m`]);
  if (c.surfaceMaterial) rows.push(['Surface', formatProp('material', c.surfaceMaterial)]);
  if (c.groundTemperatureC !== undefined) rows.push(['Ground temperature', `${Math.round(c.groundTemperatureC)} °C`]);
  if (c.water) {
    rows.push(['Water table', c.water.tableDepthM <= 0 ? 'at the surface' : `${c.water.tableDepthM.toFixed(1)} m down`]);
    if (c.water.surfaceWaterDepthM > 0) rows.push(['Standing water', `${c.water.surfaceWaterDepthM.toFixed(2)} m`]);
    if (c.water.steamFluxKgPerSm2 > 0) rows.push(['Steam flux', formatProp('steamFluxKgPerSm2', c.water.steamFluxKgPerSm2)]);
  }
  if (c.lava) rows.push(['Lava', `${c.lava.thicknessM.toFixed(1)} m thick · ${Math.round(c.lava.temperatureC)} °C · crust ${c.lava.crustM.toFixed(2)} m`]);
  if (c.pdc) rows.push(['Pyroclastic flow', `${c.pdc.depthM.toFixed(1)} m · ${c.pdc.speedMPerS.toFixed(0)} m/s · ${Math.round(c.pdc.temperatureC)} °C`]);
  if (c.lahar) rows.push(['Lahar', `${c.lahar.depthM.toFixed(1)} m · ${c.lahar.speedMPerS.toFixed(1)} m/s`]);
  const layers = c.layers ?? [];
  const top = layers[0]?.top ?? 0;
  const bottom = layers[layers.length - 1]?.bottom ?? top;
  const span = Math.max(1, top - bottom);
  const profile = c.temperatureProfile ?? [];
  const tMax = Math.max(100, ...profile.map((p) => p.temperatureC));
  const dMax = Math.max(1, ...profile.map((p) => p.depthM));
  return (
    <section className="flex flex-col gap-2">
      <Heading>Ground here</Heading>
      <PropTable rows={rows} />
      {layers.length > 0 && (
        <>
          <Heading>
            Layers, top down {c.layerCount && c.layerCount > layers.length ? `(top ${layers.length} of ${c.layerCount})` : ''}
          </Heading>
          <div className="flex gap-2">
            <div className="flex w-5 shrink-0 flex-col overflow-hidden rounded-sm ring-1 ring-border" style={{ height: 160 }} aria-hidden>
              {layers.map((l, k) => (
                <div key={k} style={{ flexGrow: Math.max(0.02, (l.top - l.bottom) / span), background: materialColor(l.material, world) }} />
              ))}
            </div>
            <ol className="flex min-w-0 flex-1 flex-col gap-0.5 text-xs">
              {layers.slice(0, 10).map((l, k) => (
                <li key={k} className="flex gap-1.5" title={`${l.label ?? ''} porosity ${(l.porosity * 100).toFixed(0)}%`}>
                  <span className="mt-0.5 size-2 shrink-0 rounded-[2px]" style={{ background: materialColor(l.material, world) }} />
                  <span className="min-w-0 flex-1 truncate">
                    {formatProp('material', l.material)}
                    {l.label && <span className="text-muted-foreground"> · {l.label}</span>}
                  </span>
                  <span className="text-muted-foreground tabular-nums">{(l.top - l.bottom).toFixed(l.top - l.bottom < 10 ? 1 : 0)} m</span>
                </li>
              ))}
              {layers.length > 10 && <li className="text-muted-foreground">… {layers.length - 10} more</li>}
            </ol>
          </div>
        </>
      )}
      {profile.length > 1 && (
        <>
          <Heading>Temperature with depth</Heading>
          <svg viewBox="0 0 280 110" className="w-full" role="img" aria-label="Temperature with depth">
            <line x1="30" y1="5" x2="30" y2="95" stroke="currentColor" strokeOpacity={0.25} />
            <line x1="30" y1="95" x2="275" y2="95" stroke="currentColor" strokeOpacity={0.25} />
            <text x="2" y="10" fontSize="9" fill="currentColor" opacity={0.6}>
              0 m
            </text>
            <text x="2" y="95" fontSize="9" fill="currentColor" opacity={0.6}>
              {dMax >= 1000 ? `${(dMax / 1000).toFixed(1)} km` : `${Math.round(dMax)} m`}
            </text>
            <text x="275" y="106" fontSize="9" fill="currentColor" opacity={0.6} textAnchor="end">
              {Math.round(tMax)} °C
            </text>
            <polyline
              fill="none"
              stroke="#ff7b39"
              strokeWidth={1.5}
              points={profile.map((p) => `${30 + (p.temperatureC / tMax) * 245},${5 + (p.depthM / dMax) * 90}`).join(' ')}
            />
            {profile.map((p, k) =>
              p.steam ? <circle key={k} cx={30 + (p.temperatureC / tMax) * 245} cy={5 + (p.depthM / dMax) * 90} r={2 + p.steam * 2} fill="#cfe8ff" opacity={0.8} /> : null,
            )}
          </svg>
        </>
      )}
    </section>
  );
}

const ACTION_ICON: Record<ContextAction['id'], ReactNode> = {
  inject: <Plus />,
  startEruption: <Triangle />,
  stopEruption: <Square />,
  forceDike: <ArrowUpFromDot />,
  frame: <Crosshair />,
  section: <Ruler />,
  water: <Droplets />,
  dig: <Pickaxe />,
  removeVent: <Trash2 />,
  removeDike: <Trash2 />,
  removeVolcano: <Trash2 />,
};

const ACTION_TIP: Partial<Record<ContextAction['id'], ReactNode>> = {
  inject: 'Add a batch of magma to this chamber; the form starts from the magma its supply delivers',
  startEruption: 'Open a vent and start an eruption of this volcano now',
  stopEruption: 'End the eruption of this volcano now',
  forceDike: 'Send a dike (magma-filled crack) up from this chamber',
  frame: (
    <span>
      Fly to it and frame it <Kbd>F</Kbd>
    </span>
  ),
  section: 'Cut a cross-section through it (along a dike or fissure, else west to east)',
  water: 'Pour water at this point (the volume is set in the tool bar)',
  dig: 'Dig a pit at this point',
  removeVent: 'Delete this fissure and its dike: it stops being a vent; the intrusion stays in the rock',
  removeDike: 'Delete this dike (arresting it if still rising); its fissure stops being a vent',
  removeVolcano: 'Remove this volcano: its chamber and activity go; what it built stays in the landscape',
};

/** The selection's one-off actions (see {@link contextActions}); lasting states are in the Settings tab. */
function ActionRow({ onFrame, onSection }: { onFrame: () => void; onSection: () => void }) {
  const selection = useStore((s) => s.selection);
  const entities = useStore((s) => s.entities);
  const volcanoes = useStore((s) => s.state?.volcanoes);
  const replay = useStore((s) => s.clock?.replay ?? false);
  // removals ask once more: the first click arms the button for a few seconds
  const [armed, setArmed] = useState<string | null>(null);
  useEffect(() => {
    if (!armed) return;
    const t = window.setTimeout(() => setArmed(null), 4000);
    return () => window.clearTimeout(t);
  }, [armed]);
  useEffect(() => setArmed(null), [selection]);
  const actions = contextActions(selection, entities, volcanoes);
  const run = (a: ContextAction) => {
    const s = useStore.getState();
    switch (a.id) {
      case 'inject':
        return s.set({ injectFor: a.volcanoId, selectedVolcano: a.volcanoId });
      case 'startEruption':
      case 'stopEruption':
      case 'forceDike':
        return command({ kind: a.id, volcanoId: a.volcanoId });
      case 'frame':
        return onFrame();
      case 'section':
        return onSection();
      case 'removeVent':
        setArmed(null);
        return command({ kind: a.id, volcanoId: a.volcanoId, ventId: a.ventId });
      case 'removeVolcano': {
        const vid = a.volcanoId;
        void removeVolcano(vid).then((r) => showServerResult(r, (token) => removeVolcano(vid, token), () => s.select(null)));
        return;
      }
      case 'removeDike':
        setArmed(null);
        return command({ kind: 'removeDike', volcanoId: a.volcanoId, dikeId: a.dikeId });
      case 'water':
      case 'dig':
        if (selection?.type === 'point')
          command(a.id === 'water' ? { kind: 'addWater', at: selection.at, volumeM3: s.waterVolume, seconds: 600 } : { kind: 'dig', at: selection.at, radius: s.digRadius, depth: s.digDepth });
        return;
    }
  };
  if (actions.length === 0) return null;
  return (
    <>
      <div className="flex shrink-0 flex-wrap gap-1.5 px-3 pb-2" role="toolbar" aria-label="Actions">
        {actions.map((a) => (
          <Tip key={a.id} content={ACTION_TIP[a.id]}>
            <Button
              size="xs"
              variant={a.id === 'stopEruption' || (DESTRUCTIVE.has(a.id) && armed === a.id) ? 'destructive' : a.id === 'startEruption' || a.id === 'inject' ? 'default' : 'secondary'}
              disabled={replay && a.id !== 'frame' && a.id !== 'section'}
              onClick={() => (DESTRUCTIVE.has(a.id) && armed !== a.id ? setArmed(a.id) : run(a))}
            >
              {ACTION_ICON[a.id]} {DESTRUCTIVE.has(a.id) && armed === a.id ? 'Click again to remove' : a.label}
            </Button>
          </Tip>
        ))}
      </div>
    </>
  );
}

/** Tabs over the scrolling body (a row that scrolls sideways); a single tab shows without a tab bar. */
function InspectorTabs({ tabs, tab, onTab }: { tabs: TabDef[]; tab: string; onTab: (t: string) => void }) {
  if (tabs.length === 0) return null;
  const current = tabs.some((t) => t.id === tab) ? tab : tabs[0].id;
  const body = (t: TabDef) => <div className="flex min-h-0 flex-col gap-3 overflow-y-auto overscroll-contain p-3">{t.body}</div>;
  if (tabs.length === 1) return body(tabs[0]);
  return (
    <Tabs value={current} onValueChange={(v) => onTab(String(v))} className="flex min-h-0 flex-col gap-0">
      <TabsList className="mx-3 mt-2 w-[calc(100%-1.5rem)] shrink-0 justify-start overflow-x-auto overflow-y-hidden [scrollbar-width:none]" aria-label="Inspector sections">
        {tabs.map((t) => (
          <TabsTrigger key={t.id} value={t.id} className="min-w-fit flex-none px-2">
            {t.title}
          </TabsTrigger>
        ))}
      </TabsList>
      {tabs.map((t) => (
        <TabsContent key={t.id} value={t.id} className="flex min-h-0 flex-col">
          {body(t)}
        </TabsContent>
      ))}
    </Tabs>
  );
}
