import { useMemo, useState } from 'react';
import { ChevronRight, Eye, EyeOff, Search } from 'lucide-react';
import { Hint, SimpleSelect } from '@/components/fields';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/ui/input-group';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { cn } from '@/lib/utils';
import { useCamera } from '../camera/cameraStore';
import { showEntity } from '../net/connection';
import type { SimEvent, WorldInfo } from '../protocol/messages';
import { KIND_LABEL, entityColor, formatPlace, type EntityView } from '../store/entities';
import { buildTree, ENTITY_TABS, tabCounts, type EntitySort, type EntityTab, type TreeGroup } from '../store/entityTree';
import { loadPref, savePref } from '../store/prefs';
import { filterQuakes } from '../store/quakeFilter';
import { simNow, useStore } from '../store/store';
import { FEATURE_COLORS } from '../util/color';
import { formatSimTime } from '../util/world';

/** Rows shown per category before "show all". */
const PAGE = 40;
/** Entities seen within this long (ms) are marked new. */
const NEW_MS = 60_000;
const TAB_KEY = 'typhon.entitiesTab';
const OPEN_KEY = 'typhon.entitiesOpen';

type Quake = Extract<SimEvent, { kind: 'seismic' }>;

const isTab = (v: unknown): v is EntityTab => ENTITY_TABS.some((t) => t.key === v);
const isFlags = (v: unknown): v is Record<string, boolean> => !!v && typeof v === 'object' && !Array.isArray(v);

/** Categories closed until opened (the long lists of diffuse ground deposits and stations). */
const CLOSED_BY_DEFAULT = new Set(['sulfurDeposits', 'alteredGround', 'sinter', 'cinnabar', 'otherDeposits', 'lavaFields']);

/**
 * Everything with a place and a lifetime, in tabs: the volcano itself (chambers, vents, fissures,
 * dikes, flows, columns), hydrothermal features and ground deposits, monitoring stations, and
 * earthquakes. Each tab is a tree of categories (split by volcano when there are several) with
 * counts, "new" badges and an eye to hide the category in the 3D view. Click a row to select it
 * and fly there.
 */
export function EntitiesPanel({ world }: { world: WorldInfo }) {
  const entities = useStore((s) => s.entities);
  const [tab, setTabState] = useState<EntityTab>(() => loadPref(TAB_KEY, 'volcano', isTab));
  const [text, setText] = useState('');
  const [sort, setSort] = useState<EntitySort>('time');
  const [volcano, setVolcano] = useState('all');
  const counts = useMemo(() => tabCounts(entities), [entities]);
  const setTab = (t: EntityTab) => {
    setTabState(t);
    savePref(TAB_KEY, t);
  };
  return (
    <div className="flex flex-col gap-3">
      <Tabs value={tab} onValueChange={(v) => isTab(v) && setTab(v)}>
        {/* one row: short labels, and a horizontal scroll if the drawer is narrower still */}
        <TabsList className="w-full justify-start overflow-x-auto overflow-y-hidden [scrollbar-width:none]">
          {ENTITY_TABS.map((t) => (
            <TabsTrigger key={t.key} value={t.key} title={t.label} aria-label={t.label} className="min-w-fit flex-none px-2">
              {t.short}
              {t.key !== 'quakes' && counts[t.key] > 0 && (
                <Badge variant="secondary" className="h-4 px-1 text-[10px] tabular-nums">
                  {counts[t.key]}
                </Badge>
              )}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>
      <div className="flex gap-2">
        <InputGroup className="flex-1">
          <InputGroupAddon>
            <Search />
          </InputGroupAddon>
          <InputGroupInput type="search" placeholder="Filter by name or type" aria-label="Filter entities" value={text} onChange={(e) => setText(e.target.value)} />
        </InputGroup>
        {tab !== 'quakes' && (
          <SimpleSelect
            label="Sort by"
            value={sort}
            onChange={setSort}
            options={[
              ['time', 'Newest'],
              ['temperature', 'Hottest'],
              ['name', 'Name'],
            ]}
          />
        )}
        {world.volcanoes.length > 1 && (
          <SimpleSelect label="Volcano" value={volcano} onChange={setVolcano} options={[['all', 'All volcanoes'] as const, ...world.volcanoes.map((v) => [v.id, v.name] as const)]} />
        )}
      </div>
      {tab === 'quakes' ? (
        <QuakesTab world={world} text={text} volcanoId={volcano === 'all' ? null : volcano} />
      ) : (
        <EntityTree world={world} tab={tab} text={text} sort={sort} volcanoId={volcano === 'all' ? null : volcano} />
      )}
      <Hint>Click a row to select it and fly there; press F to frame the selection, Esc to clear it. The eye hides a category in the 3D view.</Hint>
    </div>
  );
}

function useOpenState(): [Record<string, boolean>, (key: string, open: boolean) => void] {
  const [open, setOpen] = useState<Record<string, boolean>>(() => loadPref(OPEN_KEY, {}, isFlags));
  return [
    open,
    (key, o) => {
      const next = { ...open, [key]: o };
      setOpen(next);
      savePref(OPEN_KEY, next);
    },
  ];
}

function EntityTree({ world, tab, text, sort, volcanoId }: { world: WorldInfo; tab: EntityTab; text: string; sort: EntitySort; volcanoId: string | null }) {
  const entities = useStore((s) => s.entities);
  const [open, setOpen] = useOpenState();
  const sections = useMemo(() => buildTree(entities, tab, { text, sort, volcanoId, volcanoes: world.volcanoes.map((v) => v.id) }), [entities, tab, text, sort, volcanoId, world]);
  if (sections.length === 0)
    return (
      <Hint>
        {text
          ? 'Nothing matches.'
          : tab === 'volcano'
            ? 'Nothing here yet. Vents, dikes and flows appear as the volcano creates them.'
            : tab === 'hydro'
              ? 'No hydrothermal features yet. Fumaroles, springs and their deposits appear as hot water reaches the surface.'
              : 'No monitoring stations in this world.'}
      </Hint>
    );
  return (
    <div className="flex flex-col gap-3">
      {sections.map((sec) => (
        <div key={sec.title ?? '-'} className="flex flex-col gap-2">
          {sec.title && <h3 className="px-1 text-xs font-medium tracking-wide text-muted-foreground uppercase">{sec.title}</h3>}
          {sec.groups.map((g) => (
            <CategoryNode
              key={g.category.key}
              group={g}
              world={world}
              open={open[g.category.key] ?? (!CLOSED_BY_DEFAULT.has(g.category.key) && !text ? true : !!text)}
              onOpen={(o) => setOpen(g.category.key, o)}
            />
          ))}
        </div>
      ))}
    </div>
  );
}

/** Eye toggle: shows or hides a category in the 3D view. */
function VisibilityToggle({ category, label }: { category: string; label: string }) {
  const hidden = useStore((s) => s.hiddenCategories[category] === true);
  const set = useStore((s) => s.set);
  return (
    <Button
      variant="ghost"
      size="icon-xs"
      aria-pressed={!hidden}
      aria-label={hidden ? `Show ${label} in the view` : `Hide ${label} in the view`}
      title={hidden ? 'Hidden in the 3D view — click to show' : 'Shown in the 3D view — click to hide'}
      onClick={(e) => {
        e.stopPropagation();
        const cur = useStore.getState().hiddenCategories;
        set({ hiddenCategories: { ...cur, [category]: !hidden } });
      }}
    >
      {hidden ? <EyeOff className="text-muted-foreground" /> : <Eye />}
    </Button>
  );
}

function CategoryNode({ group, world, open, onOpen }: { group: TreeGroup; world: WorldInfo; open: boolean; onOpen: (o: boolean) => void }) {
  const now = performance.now();
  const c = group.category;
  const fresh = group.rows.filter((e) => isNew(e, now)).length;
  const live = group.rows.filter((e) => e.removedAt === undefined).length;
  return (
    <Collapsible open={open} onOpenChange={onOpen} className="rounded-lg border">
      <div className="flex items-center pr-1.5">
        <CollapsibleTrigger className="group flex min-w-0 flex-1 items-center gap-2 px-3 py-2 text-left text-sm font-medium hover:bg-muted/50">
          <ChevronRight className="size-4 shrink-0 transition-transform group-data-[panel-open]:rotate-90" />
          <span className="flex-1 truncate">{c.label}</span>
          {fresh > 0 && <Badge>{fresh} new</Badge>}
          <Badge variant="secondary" className="tabular-nums">
            {live}
          </Badge>
        </CollapsibleTrigger>
        {c.drawn && <VisibilityToggle category={c.key} label={c.label.toLowerCase()} />}
      </div>
      <CollapsibleContent>
        {group.byVolcano ? (
          <div className="flex flex-col gap-1 border-t px-2 py-1.5">
            {group.byVolcano.map((v) => (
              <Collapsible key={v.volcanoId} defaultOpen className="rounded-md">
                <CollapsibleTrigger className="group flex w-full items-center gap-2 px-2 py-1 text-left text-xs font-medium text-muted-foreground hover:bg-muted/50">
                  <ChevronRight className="size-3.5 transition-transform group-data-[panel-open]:rotate-90" />
                  <span className="flex-1">{world.volcanoes.find((w) => w.id === v.volcanoId)?.name ?? 'Unassigned'}</span>
                  <span className="tabular-nums">{v.rows.length}</span>
                </CollapsibleTrigger>
                <CollapsibleContent>
                  <Rows rows={v.rows} label={c.label} />
                </CollapsibleContent>
              </Collapsible>
            ))}
          </div>
        ) : (
          <div className="border-t">
            <Rows rows={group.rows} label={c.label} />
          </div>
        )}
      </CollapsibleContent>
    </Collapsible>
  );
}

function Rows({ rows, label }: { rows: EntityView[]; label: string }) {
  const selection = useStore((s) => s.selection);
  const hoverId = useStore((s) => s.hoverId);
  const set = useStore((s) => s.set);
  const [all, setAll] = useState(false);
  const now = performance.now();
  const selectedId = selection?.type === 'entity' ? selection.id : null;
  return (
    <>
      <ul className="py-1" role="listbox" aria-label={label}>
        {(all ? rows : rows.slice(0, PAGE)).map((e) => (
          <EntityRow key={e.id} e={e} selected={e.id === selectedId} hovered={e.id === hoverId} now={now} onHover={(h) => set({ hoverId: h ? e.id : null })} />
        ))}
      </ul>
      {rows.length > PAGE && (
        <Button variant="link" size="xs" className="mb-1 ml-2" onClick={() => setAll(!all)}>
          {all ? 'Show fewer' : `Show all ${rows.length}`}
        </Button>
      )}
    </>
  );
}

/** Earthquakes: the recent ones (as limited in View → Earthquakes) and the notable ones the server tracks. */
function QuakesTab({ world, text, volcanoId }: { world: WorldInfo; text: string; volcanoId: string | null }) {
  const events = useStore((s) => s.events);
  const filter = useStore((s) => s.quakeFilter);
  const showHypo = useStore((s) => s.showHypocentres);
  const selection = useStore((s) => s.selection);
  const set = useStore((s) => s.set);
  const clockTime = useStore((s) => s.clock?.time ?? 0);
  const entities = useStore((s) => s.entities);
  const [open, setOpen] = useOpenState();
  const notable = useMemo(() => buildTree(entities, 'quakes', { text, volcanoId, sort: 'time' }), [entities, text, volcanoId]);
  const recent = useMemo(() => {
    const t = text.trim().toLowerCase();
    const quakes = events.filter((e): e is Quake => e.kind === 'seismic' && (!volcanoId || e.volcanoId === volcanoId));
    return filterQuakes(quakes, Math.max(simNow(), clockTime), filter)
      .filter((q) => !t || `m ${q.magnitude.toFixed(1)} ${q.type}`.toLowerCase().includes(t))
      .reverse();
    // eslint-disable-next-line react-hooks/exhaustive-deps -- clockTime moves "now"
  }, [events, filter, text, volcanoId, clockTime]);
  const selected = selection?.type === 'quake' ? selection.event : null;
  const fly = (q: Quake) => {
    set({ selection: { type: 'quake', event: q } });
    useCamera.getState().requestCamera({ kind: 'frameSelection' });
  };
  const recentOpen = open.recentQuakes ?? true;
  return (
    <div className="flex flex-col gap-2">
      <Collapsible open={recentOpen} onOpenChange={(o) => setOpen('recentQuakes', o)} className="rounded-lg border">
        <div className="flex items-center pr-1.5">
          <CollapsibleTrigger className="group flex min-w-0 flex-1 items-center gap-2 px-3 py-2 text-left text-sm font-medium hover:bg-muted/50">
            <ChevronRight className="size-4 shrink-0 transition-transform group-data-[panel-open]:rotate-90" />
            <span className="flex-1 truncate">Recent earthquakes</span>
            <Badge variant="secondary" className="tabular-nums">
              {recent.length}
            </Badge>
          </CollapsibleTrigger>
          <Button
            variant="ghost"
            size="icon-xs"
            aria-pressed={showHypo}
            aria-label={showHypo ? 'Hide earthquakes in the view' : 'Show earthquakes in the view'}
            onClick={() => set({ showHypocentres: !showHypo })}
          >
            {showHypo ? <Eye /> : <EyeOff className="text-muted-foreground" />}
          </Button>
        </div>
        <CollapsibleContent>
          <ul className="border-t py-1" role="listbox" aria-label="Recent earthquakes">
            {recent.slice(0, 200).map((q, k) => (
              <li key={`${q.time}-${k}`} role="option" aria-selected={selected === q}>
                <button
                  type="button"
                  className={cn('flex w-full items-center gap-2 px-3 py-1 text-left text-sm transition-colors hover:bg-muted/60', selected === q && 'bg-primary/15')}
                  onClick={() => fly(q)}
                  title={`${formatPlace(q.hypocenter)} · ${Math.round(-q.hypocenter[2])} m below sea level`}
                >
                  <span className="w-12 shrink-0 font-medium tabular-nums">M {q.magnitude.toFixed(1)}</span>
                  <span className="w-16 shrink-0 text-xs text-muted-foreground">{q.type}</span>
                  <span className="min-w-0 flex-1 truncate text-xs text-muted-foreground tabular-nums">{(-q.hypocenter[2] / 1000).toFixed(1)} km deep</span>
                  <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{formatSimTime(q.time)}</span>
                </button>
              </li>
            ))}
          </ul>
          {recent.length === 0 && <p className="px-3 pb-2 text-sm text-muted-foreground">No earthquakes {filter.count === 0 ? '(the limit is 0)' : 'yet'}.</p>}
          <p className="border-t px-3 py-1.5 text-xs text-muted-foreground">
            The last {filter.count} as of now
            {filter.windowS ? `, within ${Math.round(filter.windowS / 3600) || `${Math.round(filter.windowS / 60)} min`}${filter.windowS >= 3600 ? ' h' : ''}` : ''}
            {filter.minMagnitude != null ? `, M ≥ ${filter.minMagnitude}` : ''}.{' '}
            <Button variant="link" size="xs" className="h-auto p-0" onClick={() => set({ drawer: 'view' })}>
              Change in View
            </Button>
          </p>
        </CollapsibleContent>
      </Collapsible>
      {notable.flatMap((s) => s.groups).map((g) => (
        <CategoryNode key={g.category.key} group={g} world={world} open={open[g.category.key] ?? false} onOpen={(o) => setOpen(g.category.key, o)} />
      ))}
    </div>
  );
}

function isNew(e: EntityView, now: number): boolean {
  return e.fresh && e.removedAt === undefined && now - e.seenAt < NEW_MS;
}

/** One-line summary of the most telling property. */
function summary(e: EntityView): string {
  const p = e.props;
  switch (e.kind) {
    case 'feature':
      return typeof p.groundTemperatureC === 'number' ? `${p.groundTemperatureC} °C` : '';
    case 'dike':
      return `${String(p.status ?? '').toLowerCase()}${typeof p.tipDepthM === 'number' ? ` · tip ${Math.round(p.tipDepthM)} m deep` : ''}`;
    case 'vent':
    case 'fissure':
      return p.erupting ? 'erupting' : 'quiet';
    case 'quake':
      return `M ${Number(p.magnitude).toFixed(1)} ${String(p.type ?? '')}`;
    case 'chamber':
      return typeof p.overpressureMPa === 'number' ? `${p.overpressureMPa.toFixed(1)} MPa over` : '';
    case 'plume':
      return typeof p.heightM === 'number' ? `${(p.heightM / 1000).toFixed(1)} km tall` : '';
    case 'pdc':
    case 'lahar':
    case 'lavaFront':
      return typeof p.runoutM === 'number' ? `${Math.round(p.runoutM)} m run-out` : typeof p.lengthM === 'number' ? `${Math.round(p.lengthM)} m long` : '';
    default:
      return '';
  }
}

function EntityRow({ e, selected, hovered, now, onHover }: { e: EntityView; selected: boolean; hovered: boolean; now: number; onHover: (h: boolean) => void }) {
  const removed = e.removedAt !== undefined;
  return (
    <li role="option" aria-selected={selected}>
      <button
        title={`${KIND_LABEL[e.kind] ?? e.kind} · ${formatPlace(e.at)} · since ${formatSimTime(e.createdAt)}${removed ? ' · removed' : ''}`}
        type="button"
        className={cn(
          'flex w-full items-center gap-2 py-1 pr-3 pl-8 text-left text-sm transition-colors hover:bg-muted/60',
          selected && 'bg-primary/15 hover:bg-primary/20',
          hovered && !selected && 'bg-muted/60',
          removed && 'line-through opacity-50',
        )}
        onClick={() => showEntity(e.id)}
        onPointerEnter={() => onHover(true)}
        onPointerLeave={() => onHover(false)}
      >
        <span className="size-2.5 shrink-0 rounded-full ring-1 ring-black/30" style={{ background: entityColor(e, FEATURE_COLORS) }} aria-hidden />
        <span className="min-w-0 flex-1 truncate">{e.label}</span>
        <span className="shrink-0 text-xs text-muted-foreground tabular-nums">{summary(e)}</span>
        {isNew(e, now) && <span className="size-1.5 shrink-0 animate-pulse rounded-full bg-primary" aria-label="new" />}
      </button>
    </li>
  );
}
