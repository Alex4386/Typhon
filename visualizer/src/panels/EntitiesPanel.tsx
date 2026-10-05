import { useMemo, useState } from 'react';
import { ChevronRight, Search } from 'lucide-react';
import { Hint, SimpleSelect } from '@/components/fields';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible';
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/ui/input-group';
import { cn } from '@/lib/utils';
import { showEntity } from '../net/connection';
import type { WorldInfo } from '../protocol/messages';
import { KIND_GROUPS, KIND_LABEL, entityColor, formatPlace, listEntities, type EntityView } from '../store/entities';
import { useStore } from '../store/store';
import { FEATURE_COLORS } from '../util/color';
import { formatSimTime } from '../util/world';

/** Rows shown per group before "show all". */
const PAGE = 40;
/** Entities seen within this long (ms) are marked new. */
const NEW_MS = 60_000;

/**
 * Everything with a place and a lifetime: vents, dikes, hot springs, flows, stations, notable quakes.
 * Click a row to select it and fly there; removed ones fade out of the list.
 */
export function EntitiesPanel({ world }: { world: WorldInfo }) {
  const entities = useStore((s) => s.entities);
  const selection = useStore((s) => s.selection);
  const hoverId = useStore((s) => s.hoverId);
  const set = useStore((s) => s.set);
  const [text, setText] = useState('');
  const [volcano, setVolcano] = useState('all');
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const now = performance.now();
  const groups = useMemo(
    () => KIND_GROUPS.map((g) => ({ ...g, rows: listEntities(entities, g.kinds, { text, volcanoId: volcano === 'all' ? null : volcano }) })),
    [entities, text, volcano],
  );
  const total = groups.reduce((n, g) => n + g.rows.length, 0);
  const selectedId = selection?.type === 'entity' ? selection.id : null;
  return (
    <div className="flex flex-col gap-3">
      <div className="flex gap-2">
        <InputGroup className="flex-1">
          <InputGroupAddon>
            <Search />
          </InputGroupAddon>
          <InputGroupInput type="search" placeholder="Filter (e.g. spring, dike 3, fissure)" aria-label="Filter entities" value={text} onChange={(e) => setText(e.target.value)} />
        </InputGroup>
        {world.volcanoes.length > 1 && (
          <SimpleSelect label="Volcano" value={volcano} onChange={setVolcano} options={[['all', 'All volcanoes'] as const, ...world.volcanoes.map((v) => [v.id, v.name] as const)]} />
        )}
      </div>
      {total === 0 && <Hint>{text ? 'Nothing matches.' : 'Nothing here yet. Vents, dikes, hot springs and flows appear as the volcano creates them.'}</Hint>}
      {groups
        .filter((g) => g.rows.length > 0)
        .map((g) => {
          const fresh = g.rows.filter((e) => isNew(e, now)).length;
          const all = expanded[g.key] ?? false;
          return (
            <Collapsible key={g.key} defaultOpen={g.key !== 'quakes' && g.key !== 'stations'} className="rounded-lg border">
              <CollapsibleTrigger className="group flex w-full items-center gap-2 px-3 py-2 text-left text-sm font-medium hover:bg-muted/50">
                <ChevronRight className="size-4 transition-transform group-data-[panel-open]:rotate-90" />
                <span className="flex-1">{g.label}</span>
                {fresh > 0 && <Badge>{fresh} new</Badge>}
                <Badge variant="secondary">{g.rows.length}</Badge>
              </CollapsibleTrigger>
              <CollapsibleContent>
                <ul className="border-t py-1" role="listbox" aria-label={g.label}>
                  {(all ? g.rows : g.rows.slice(0, PAGE)).map((e) => (
                    <EntityRow key={e.id} e={e} selected={e.id === selectedId} hovered={e.id === hoverId} now={now} onHover={(h) => set({ hoverId: h ? e.id : null })} />
                  ))}
                </ul>
                {g.rows.length > PAGE && (
                  <Button variant="link" size="xs" className="mb-1 ml-2" onClick={() => setExpanded({ ...expanded, [g.key]: !all })}>
                    {all ? 'Show fewer' : `Show all ${g.rows.length}`}
                  </Button>
                )}
              </CollapsibleContent>
            </Collapsible>
          );
        })}
      <Hint>Click a row to select it and fly there; press F to frame the selection, Esc to clear it.</Hint>
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
            'flex w-full items-center gap-2 px-3 py-1 text-left text-sm transition-colors hover:bg-muted/60',
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
