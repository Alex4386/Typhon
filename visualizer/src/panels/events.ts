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
    case 'fissureOpened':
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
const NOTABLE_FEATURES = new Set(['GEYSER', 'HOT_SPRING', 'SULFUR_SPRING', 'SUBMARINE_VENT']);

/** Milestones: changes in what the volcano is doing, not its continuous output. */
export function isImportant(e: SimEvent): boolean {
  switch (e.kind) {
    case 'eruptionStarted':
    case 'eruptionEnded':
    case 'alertChanged':
    case 'regimeChanged':
    case 'fissureOpened':
    case 'message':
      return true;
    case 'geothermalFeature':
      return NOTABLE_FEATURES.has(e.feature);
    case 'oceanEntry':
      return e.littoralExplosion;
    case 'seismic':
      return e.magnitude >= 3;
    default:
      return false;
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

