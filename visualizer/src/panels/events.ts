import type { SimEvent } from '../protocol/messages';

/** Plain-language names of alert levels (the badge text). */
export const ALERT_LABEL: Record<string, string> = {
  EXTINCT: 'Extinct',
  DORMANT: 'Quiet',
  MINOR_ACTIVITY: 'Restless',
  MAJOR_ACTIVITY: 'Unrest',
  ERUPTION_IMMINENT: 'Eruption likely',
  ERUPTING: 'Erupting',
};

/** What the conduit is doing, in words. */
export const REGIME_LABEL: Record<string, string> = {
  NONE: 'no eruption',
  FOUNTAINING: 'lava fountaining',
  OPEN_VENT: 'open vent, gas bursts',
  EFFUSIVE: 'lava flowing out',
  DOME: 'lava dome growing',
  EXPLOSIVE: 'explosive column',
  SURTSEYAN: 'steam-blast (water + magma)',
};

/** Eruption styles, in words. */
export const STYLE_LABEL: Record<string, string> = {
  HAWAIIAN: 'Hawaiian (lava fountains and flows)',
  STROMBOLIAN: 'Strombolian (rhythmic bursts)',
  VULCANIAN: 'Vulcanian (short violent blasts)',
  PELEAN: 'Pelean (dome collapse, hot avalanches)',
  PLINIAN: 'Plinian (towering ash column)',
  LAVA_DOME: 'Lava dome (slow extrusion)',
  SUBPLINIAN: 'Sub-Plinian (sustained ash column)',
  SURTSEYAN: 'Surtseyan (magma meets water)',
  PHREATIC: 'Phreatic (steam blasts)',
  MIXED: 'Mixed (several styles at once)',
};

const STALL_LABEL: Record<string, string> = {
  INSUFFICIENT_PRESSURE: 'not enough pressure to go on',
  FROZE: 'the magma froze',
};

const SEISMIC_LABEL: Record<string, string> = {
  VT: 'rock-breaking quake',
  LP: 'fluid quake (long-period)',
  TREMOR: 'volcanic tremor',
  EXPLOSION: 'explosion quake',
};

export function describeEvent(e: SimEvent): string {
  switch (e.kind) {
    case 'seismic':
      return `M${e.magnitude.toFixed(1)} ${SEISMIC_LABEL[e.type] ?? e.type}, ${(Math.abs(e.hypocenter[2]) / 1000).toFixed(1)} km ${e.hypocenter[2] < 0 ? 'below' : 'above'} sea level${e.swarm ? ' (swarm)' : ''}`;
    case 'eruptionStarted':
      return `Eruption started${e.cause ? ` (${e.cause.toLowerCase()})` : ''}`;
    case 'eruptionEnded':
      return `Eruption ended — ${(e.eruptedVolumeM3 / 1e6).toFixed(2)} million m³ erupted`;
    case 'alertChanged':
      return `Status: ${ALERT_LABEL[e.previous ?? ''] ?? '—'} → ${ALERT_LABEL[e.current] ?? e.current}`;
    case 'regimeChanged':
      return `Now: ${REGIME_LABEL[e.regime] ?? e.regime}`;
    case 'styleEstimated':
      return `${e.forecast ? 'Forecast style' : 'Eruption style'}: ${STYLE_LABEL[e.current] ?? e.current}${e.vei > 0 ? ` · VEI ${e.vei}` : ''}${e.previous && e.previous !== e.current ? ` (was ${(STYLE_LABEL[e.previous] ?? e.previous).split(' (')[0]})` : ''}`;
    case 'dikeStarted':
      return `Magma intrusion (dike ${e.dikeId}) started rising from the chamber`;
    case 'dikeStalled':
      return `Dike ${e.dikeId} stalled ${(e.depthM / 1000).toFixed(2)} km down — ${STALL_LABEL[e.reason] ?? e.reason.toLowerCase().replaceAll('_', ' ')}`;
    case 'dikeAdvanced':
      return `Magma intrusion (dike) rising, tip ${(e.path[e.path.length - 1][2] / 1000).toFixed(2)} km`;
    case 'fissureOpened':
      return `New fissure vent opened (${e.vent.id})`;
    case 'bombLaunched':
      return `Volcanic bomb thrown (${e.flightSeconds.toFixed(0)} s flight)`;
    case 'plume':
      return `Ash column ${(e.topZ / 1000).toFixed(1)} km high`;
    case 'lightning':
      return 'Volcanic lightning';
    case 'massFlowFront':
      return `${e.flow === 'PDC' ? 'Pyroclastic flow' : 'Mudflow (lahar)'} moving at ${e.speed.toFixed(0)} m/s`;
    case 'geothermalFeature':
      return `New ${e.feature.toLowerCase().replaceAll('_', ' ')}`;
    case 'oceanEntry':
      return `Lava entering the sea (${e.powerMW.toFixed(0)} MW)${e.littoralExplosion ? ' — steam explosion' : ''}`;
    case 'message':
      return e.text;
    default:
      return (e as { kind: string }).kind;
  }
}

/** Events worth a pop-up notification, and how loud. */
export function toastTone(e: SimEvent): 'info' | 'warn' | 'alert' | null {
  switch (e.kind) {
    case 'eruptionStarted':
      return 'alert';
    case 'eruptionEnded':
      return 'warn';
    case 'alertChanged':
      return e.current === 'ERUPTION_IMMINENT' ? 'warn' : null;
    case 'seismic':
      return e.magnitude >= 4 ? 'warn' : null;
    case 'oceanEntry':
      return e.littoralExplosion ? 'info' : null;
    default:
      return null;
  }
}

/** Geothermal features that mark a regime change worth surfacing among the important events. */
export const NOTABLE_FEATURES = new Set(['GEYSER', 'HOT_SPRING', 'SULFUR_SPRING', 'SUBMARINE_VENT']);

/** Changes in what the volcano is doing: always their own row among the key events. */
const MILESTONE_KINDS = new Set(['eruptionStarted', 'eruptionEnded', 'alertChanged', 'regimeChanged', 'styleEstimated', 'dikeStarted', 'dikeStalled', 'fissureOpened', 'message']);

/** Notable bursts: quakes from this magnitude are key events (aggregated per hour). */
export const NOTABLE_QUAKE_M = 3;

/**
 * Whether an event can appear among the key events: milestones, plus notable quakes, explosive bursts
 * (bombs), new geysers/springs and lava reaching the sea, which are aggregated (see {@link keyEventRows}).
 */
export function isImportant(e: SimEvent): boolean {
  if (MILESTONE_KINDS.has(e.kind)) return true;
  switch (e.kind) {
    case 'geothermalFeature':
      return NOTABLE_FEATURES.has(e.feature);
    case 'oceanEntry':
    case 'bombLaunched':
      return true;
    case 'seismic':
      return e.magnitude >= NOTABLE_QUAKE_M;
    default:
      return false;
  }
}

/** Aggregation window of repeated key events (s of simulated time). */
const AGGREGATE_S = 3600;

/** What a repeated key event aggregates by (null: a milestone, its own row). */
function aggregateKey(e: SimEvent): string | null {
  const v = 'volcanoId' in e ? e.volcanoId : '';
  switch (e.kind) {
    case 'bombLaunched':
      return `bursts:${v}`;
    case 'seismic':
      return `quakes:${v}`;
    case 'geothermalFeature':
      return `feature:${v}:${e.feature}`;
    case 'oceanEntry':
      return 'ocean';
    default:
      return null;
  }
}

/**
 * Key-event rows, newest first: milestones one by one; notable quakes, bursts, new features and
 * ocean entries merged per kind (and volcano) within an hour of simulated time — except the first
 * ocean entry, which is a milestone of its own ("lava reached the sea").
 */
export function keyEventRows(events: SimEvent[], limit: number): Row[] {
  const rows: Row[] = [];
  const open = new Map<string, Row>();
  let firstOcean: SimEvent | null = null;
  for (const e of events) {
    if (e.kind === 'oceanEntry' && !firstOcean) {
      firstOcean = e;
      rows.push({ event: e, count: 1, firstTime: e.time });
      continue;
    }
    if (!isImportant(e)) continue;
    const key = aggregateKey(e);
    if (key === null) {
      rows.push({ event: e, count: 1, firstTime: e.time });
      continue;
    }
    const row = open.get(key);
    if (row && e.time - row.firstTime < AGGREGATE_S) {
      row.count++;
      // the row shows the newest; keep the strongest quake as its representative
      if (e.kind !== 'seismic' || row.event.kind !== 'seismic' || e.magnitude >= row.event.magnitude) row.event = e;
      row.lastTime = e.time;
      continue;
    }
    const r: Row = { event: e, count: 1, firstTime: e.time, lastTime: e.time };
    open.set(key, r);
    rows.push(r);
  }
  rows.sort((a, b) => (b.lastTime ?? b.event.time) - (a.lastTime ?? a.event.time));
  return rows.slice(0, limit);
}

/** Text of an aggregated key-event row. */
export function describeRow(r: Row): string {
  const e = r.event;
  if (r.count <= 1) return e.kind === 'oceanEntry' ? `Lava reached the sea (${e.powerMW.toFixed(0)} MW)` : describeEvent(e);
  switch (e.kind) {
    case 'bombLaunched':
      return `${r.count} explosive bursts (volcanic bombs thrown)`;
    case 'seismic':
      return `${r.count} earthquakes of M${NOTABLE_QUAKE_M}+ (largest M${e.magnitude.toFixed(1)})`;
    case 'geothermalFeature':
      return `${r.count} new ${e.feature.toLowerCase().replaceAll('_', ' ')}s`;
    case 'oceanEntry':
      return `Lava entering the sea (${r.count} entries, latest ${e.powerMW.toFixed(0)} MW)`;
    default:
      return describeEvent(e);
  }
}

/** Repetitive events collapse into one row: same kind, volcano and (for features) feature. */
function groupKey(e: SimEvent): string | null {
  switch (e.kind) {
    case 'geothermalFeature':
      return `f:${e.volcanoId}:${e.feature}`;
    case 'bombLaunched':
    case 'plume':
    case 'lightning':
    case 'massFlowFront':
    case 'oceanEntry':
    case 'dikeAdvanced':
      return `${e.kind}:${'volcanoId' in e ? e.volcanoId : ''}`;
    default:
      return null;
  }
}

export interface Row {
  event: SimEvent;
  count: number;
  firstTime: number;
  /** Time of the newest event merged into the row (aggregated key events). */
  lastTime?: number;
}

/** Newest first, consecutive repeats collapsed (keeps the newest event of each run). */
export function collapse(events: SimEvent[], limit: number): Row[] {
  const rows: Row[] = [];
  let lastKey: string | null = null;
  for (let i = events.length - 1; i >= 0 && rows.length < limit; i--) {
    const e = events[i];
    const key = groupKey(e);
    if (key !== null && key === lastKey) {
      const row = rows[rows.length - 1];
      row.count++;
      row.firstTime = e.time;
      continue;
    }
    rows.push({ event: e, count: 1, firstTime: e.time });
    lastKey = key;
  }
  return rows;
}


/**
 * Appends `add` to the time-ordered `prev`, dropping events already present (a re-attach backlog
 * repeats what this client has seen: same kind and time).
 */
export function mergeByTime(prev: SimEvent[], add: SimEvent[]): SimEvent[] {
  const lastT = prev.length ? prev[prev.length - 1].time : Number.NEGATIVE_INFINITY;
  const recent = new Set(prev.filter((e) => e.time >= (add[0]?.time ?? 0)).map((e) => `${e.kind}@${e.time}`));
  const fresh = add.filter((e) => !recent.has(`${e.kind}@${e.time}`));
  const out = prev.concat(fresh);
  if (fresh.some((e) => e.time < lastT)) out.sort((a, b) => a.time - b.time);
  return out;
}
