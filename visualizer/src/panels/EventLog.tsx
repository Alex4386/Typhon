import { useMemo, useState } from 'react';
import { History, MapPin } from 'lucide-react';
import { Hint } from '@/components/fields';
import { Tip } from '@/components/tip';
import { Button } from '@/components/ui/button';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useCamera } from '../camera/cameraStore';
import { send, showEntity } from '../net/connection';
import type { SimEvent } from '../protocol/messages';
import { nearestSurfaceEntity } from '../scene/picking';
import { simNow, useStore } from '../store/store';
import { formatSimTime } from '../util/world';
import { filterQuakes } from '../store/quakeFilter';
import { collapse, describeEvent, describeRow, isImportant, keyEventRows } from './events';

const FILTERS: [string, string, (e: SimEvent) => boolean][] = [
  ['Key events', 'Eruptions, status and style changes, dikes, new vents, lava reaching the sea, big quakes and bursts', isImportant],
  ['Quakes', 'Recent earthquakes and tremor (the last N set in View → Earthquakes)', (e) => e.kind === 'seismic'],
  ['Hot springs etc.', 'Geothermal features and lava reaching the sea', (e) => e.kind === 'geothermalFeature' || e.kind === 'oceanEntry'],
  ['Everything', 'All events', () => true],
];

/** Jumps the view back to `time` (enters replay; the server picks the nearest saved moment before it). */
export function jumpTo(time: number): void {
  const clock = useStore.getState().clock;
  if (!clock?.replay) send({ type: 'replay', action: 'enter' });
  send({ type: 'seek', time });
}

/** Where an event happened, if it has a place. */
function placeOf(e: SimEvent): [number, number] | null {
  switch (e.kind) {
    case 'geothermalFeature':
      return [e.at[0], e.at[1]];
    case 'fissureOpened':
    case 'ventFormed':
      return e.vent.at;
    case 'seismic':
      return [e.hypocenter[0], e.hypocenter[1]];
    case 'oceanEntry':
      return e.at;
    case 'dikeAdvanced':
      return e.path.length ? [e.path[e.path.length - 1][0], e.path[e.path.length - 1][1]] : null;
    case 'dikeStarted':
      return [e.origin[0], e.origin[1]];
    case 'dikeStalled':
    case 'dikeResumed':
    case 'dikeSolidified':
      return [e.tip[0], e.tip[1]];
    case 'bombLaunched':
      return [e.start[0], e.start[1]];
    default:
      return null;
  }
}

/** Selects what an event is about (its entity if it still exists, else the place) and flies there. */
export function locateEvent(e: SimEvent): void {
  const s = useStore.getState();
  if (e.kind === 'seismic') {
    s.select({ type: 'quake', event: e });
    useCamera.getState().requestCamera({ kind: 'frameSelection' });
    return;
  }
  if (e.kind === 'dikeAdvanced' || e.kind === 'dikeStarted' || e.kind === 'dikeStalled'
      || e.kind === 'dikeResumed' || e.kind === 'dikeSolidified') {
    const id = `dike:${e.volcanoId}:${e.dikeId}`;
    if (s.entities[id]) return showEntity(id);
  }
  const at = placeOf(e);
  if (!at) return;
  const near = nearestSurfaceEntity(s.entities, at, Math.max(30, (s.world?.cellSize ?? 10) * 2));
  if (near) return showEntity(near.id);
  s.select({ type: 'point', at });
  useCamera.getState().requestCamera({ kind: 'frameSelection' });
}

export function EventLog() {
  const events = useStore((s) => s.events);
  const keyEvents = useStore((s) => s.keyEvents);
  const quakeFilter = useStore((s) => s.quakeFilter);
  const clockTime = useStore((s) => s.clock?.time ?? 0);
  const dropped = useStore((s) => s.droppedEvents);
  const world = useStore((s) => s.world);
  const replayInfo = useStore((s) => s.replayInfo);
  const [filter, setFilter] = useState(0);
  const rows = useMemo(() => {
    if (filter === 0) return keyEventRows(keyEvents, 200);
    if (filter === 1) {
      const quakes = events.filter((e): e is Extract<SimEvent, { kind: 'seismic' }> => e.kind === 'seismic');
      return collapse(filterQuakes(quakes, Math.max(simNow(), clockTime), quakeFilter), 200);
    }
    return collapse(events.filter(FILTERS[filter][2]), 200);
  }, [events, keyEvents, filter, quakeFilter, clockTime]);
  const names = useMemo(() => new Map(world?.volcanoes.map((v) => [v.id, v.name]) ?? []), [world]);
  const canJump = (replayInfo?.keyframes.length ?? 0) > 0;
  return (
    <div className="flex flex-col gap-3">
      <Tabs value={filter} onValueChange={(v) => setFilter(Number(v))}>
        <TabsList className="w-full" aria-label="Event filter">
          {FILTERS.map(([name, title], k) => (
            <Tip key={name} content={title}>
              <TabsTrigger value={k}>{name}</TabsTrigger>
            </Tip>
          ))}
        </TabsList>
      </Tabs>
      {dropped > 0 && <Hint>{dropped} minor events were dropped to keep up.</Hint>}
      <ul className="flex flex-col">
        {rows.length === 0 && <Hint>{filter === 0 ? 'No key events yet — eruptions, status changes, dikes and new vents appear here.' : 'Nothing yet — events appear here as the volcano changes.'}</Hint>}
        {rows.map(({ event: e, count, firstTime }, k) => {
          const place = placeOf(e);
          return (
            <li key={k} className="group flex items-start gap-2 border-b py-1.5 text-sm last:border-0">
              <time className="w-24 shrink-0 pt-0.5 text-xs text-muted-foreground tabular-nums" title={count > 1 ? `${count} events from ${formatSimTime(firstTime)} to ${formatSimTime(e.time)}` : undefined}>
                {formatSimTime(e.time)}
              </time>
              <span className="min-w-0 flex-1">
                {'volcanoId' in e && (world?.volcanoes.length ?? 0) > 1 && <b className="mr-1">{names.get(e.volcanoId) ?? e.volcanoId}</b>}
                {filter === 0 ? describeRow({ event: e, count, firstTime }) : describeEvent(e)}
                {count > 1 && filter !== 0 && <em className="text-muted-foreground"> ×{count}</em>}
              </span>
              <span className="flex shrink-0 gap-0.5 opacity-60 group-hover:opacity-100">
                {place && (
                  <Tip content="Show where on the map">
                    <Button variant="ghost" size="icon-xs" aria-label="Show on map" onClick={() => locateEvent(e)}>
                      <MapPin />
                    </Button>
                  </Tip>
                )}
                {canJump && (
                  <Tip content="Replay from here">
                    <Button variant="ghost" size="icon-xs" aria-label="Replay from here" onClick={() => jumpTo(e.time)}>
                      <History />
                    </Button>
                  </Tip>
                )}
              </span>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
