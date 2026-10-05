import type { SimEvent } from '../protocol/messages';

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

