import { useMemo, useState } from 'react';
import type { SimEvent } from '../protocol/messages';
import { useStore } from '../store/store';
import { formatSimTime } from '../util/world';
import { collapse, isImportant } from './events';

const FILTERS: [string, (e: SimEvent) => boolean][] = [
  ['important', isImportant],
  ['seismic', (e) => e.kind === 'seismic'],
  ['features', (e) => e.kind === 'geothermalFeature' || e.kind === 'oceanEntry'],
  ['all', () => true],
];

function describe(e: SimEvent): string {
  switch (e.kind) {
    case 'seismic':
      return `${e.type} M${e.magnitude.toFixed(1)} at ${(e.hypocenter[2] / 1000).toFixed(1)} km${e.swarm ? ' (swarm)' : ''}`;
    case 'eruptionStarted':
      return `Eruption started (${e.cause.toLowerCase()})`;
    case 'eruptionEnded':
      return `Eruption ended — ${(e.eruptedVolumeM3 / 1e6).toFixed(2)} Mm³ erupted`;
    case 'alertChanged':
      return `Alert ${e.previous ?? '—'} → ${e.current}`;
    case 'regimeChanged':
      return `Regime → ${e.regime}`;
    case 'dikeAdvanced':
      return `Dike #${e.dikeId} tip at ${(e.path[e.path.length - 1][2] / 1000).toFixed(2)} km`;
    case 'fissureOpened':
      return `Fissure opened: ${e.vent.id}`;
    case 'bombLaunched':
      return `Bomb launched (${e.flightSeconds.toFixed(0)} s flight)`;
    case 'plume':
      return `Eruption column to ${(e.topZ / 1000).toFixed(1)} km`;
    case 'lightning':
      return 'Volcanic lightning';
    case 'massFlowFront':
      return `${e.flow} front (${e.cells.length} cells, ${e.speed.toFixed(0)} m/s)`;
    case 'geothermalFeature':
      return `New ${e.feature.toLowerCase().replaceAll('_', ' ')}`;
    case 'oceanEntry':
      return `Ocean entry ${e.powerMW.toFixed(0)} MW${e.littoralExplosion ? ' — littoral explosion' : ''}`;
    case 'message':
      return e.text;
    default:
      return (e as { kind: string }).kind;
  }
}

export function EventLog() {
  const events = useStore((s) => s.events);
  const dropped = useStore((s) => s.droppedEvents);
  const [filter, setFilter] = useState(0);
  const rows = useMemo(() => collapse(events.filter(FILTERS[filter][1]), 200), [events, filter]);
  return (
    <div className="panel eventlog">
      <div className="panel-head">
        <strong>Events</strong>
        <div className="tabs">
          {FILTERS.map(([name], k) => (
            <button key={name} className={filter === k ? 'on' : ''} onClick={() => setFilter(k)}>
              {name}
            </button>
          ))}
        </div>
        {dropped > 0 && <span className="muted">{dropped} dropped</span>}
      </div>
      <ul>
        {rows.length === 0 && <li className="muted">No events yet</li>}
        {rows.map(({ event: e, count, firstTime }, k) => (
          <li
            key={k}
            className={`ev-${e.kind}`}
            title={count > 1 ? `${count} events from ${formatSimTime(firstTime)} to ${formatSimTime(e.time)}` : undefined}
          >
            <time>{formatSimTime(e.time)}</time>
            {'volcanoId' in e && <b>{e.volcanoId}</b>}
            <span>
              {describe(e)}
              {count > 1 && <em className="count"> ×{count}</em>}
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
