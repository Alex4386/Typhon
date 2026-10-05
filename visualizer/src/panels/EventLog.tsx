import { useMemo, useState } from 'react';
import { send } from '../net/connection';
import type { SimEvent } from '../protocol/messages';
import { useStore } from '../store/store';
import { formatSimTime } from '../util/world';
import { collapse, describeEvent, isImportant } from './events';

const FILTERS: [string, string, (e: SimEvent) => boolean][] = [
  ['Key events', 'Eruptions, status changes, new vents and big quakes', isImportant],
  ['Quakes', 'Every earthquake and tremor', (e) => e.kind === 'seismic'],
  ['Hot springs etc.', 'Geothermal features and lava reaching the sea', (e) => e.kind === 'geothermalFeature' || e.kind === 'oceanEntry'],
  ['Everything', 'All events', () => true],
];

/** Jumps the view back to `time` (enters replay; the server picks the nearest saved moment before it). */
export function jumpTo(time: number): void {
  const clock = useStore.getState().clock;
  if (!clock?.replay) send({ type: 'replay', action: 'enter' });
  send({ type: 'seek', time });
}

export function EventLog() {
  const events = useStore((s) => s.events);
  const dropped = useStore((s) => s.droppedEvents);
  const world = useStore((s) => s.world);
  const replayInfo = useStore((s) => s.replayInfo);
  const [filter, setFilter] = useState(0);
  const rows = useMemo(() => collapse(events.filter(FILTERS[filter][2]), 200), [events, filter]);
  const names = useMemo(() => new Map(world?.volcanoes.map((v) => [v.id, v.name]) ?? []), [world]);
  const canJump = (replayInfo?.keyframes.length ?? 0) > 0;
  return (
    <div className="eventlog">
      <div className="seg" role="tablist" aria-label="Event filter">
        {FILTERS.map(([name, title], k) => (
          <button key={name} role="tab" aria-selected={filter === k} className={filter === k ? 'on' : ''} title={title} onClick={() => setFilter(k)}>
            {name}
          </button>
        ))}
      </div>
      {dropped > 0 && <div className="muted small">{dropped} minor events were dropped to keep up.</div>}
      <ul>
        {rows.length === 0 && <li className="muted">Nothing yet — events appear here as the volcano changes.</li>}
        {rows.map(({ event: e, count, firstTime }, k) => (
          <li
            key={k}
            className={`ev-${e.kind}${canJump ? ' jumpable' : ''}`}
            title={(count > 1 ? `${count} events from ${formatSimTime(firstTime)} to ${formatSimTime(e.time)}. ` : '') + (canJump ? 'Click to replay from here.' : '')}
            onClick={canJump ? () => jumpTo(e.time) : undefined}
          >
            <time>{formatSimTime(e.time)}</time>
            {'volcanoId' in e && (world?.volcanoes.length ?? 0) > 1 && <b>{names.get(e.volcanoId) ?? e.volcanoId}</b>}
            <span>
              {describeEvent(e)}
              {count > 1 && <em className="count"> ×{count}</em>}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
