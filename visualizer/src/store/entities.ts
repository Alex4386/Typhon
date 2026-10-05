import type { EntitiesMessage, Entity, SimEvent, XY } from '../protocol/messages';

/** How long a removed entity stays around (fading out) before it is forgotten (ms). */
export const FADE_OUT_MS = 2500;
/** How long a newly appeared entity pulses (ms). */
export const PULSE_MS = 8000;

/** An entity as the client holds it: the server's record plus when this client saw it change. */
export interface EntityView extends Entity {
  /** performance.now() when this client first saw it. */
  seenAt: number;
  /** It appeared while watching (not in the initial set): draw the "new" pulse. */
  fresh: boolean;
  /** performance.now() when the server removed it; it fades out, then is dropped. */
  removedAt?: number;
}

export type EntityMap = Record<string, EntityView>;

export interface EntityUpdate {
  entities: EntityMap;
  /** Entities that appeared in this delta (not on a full replace). */
  added: EntityView[];
  /** Ids removed in this delta. */
  removed: string[];
}

/**
 * Applies an `entities` message. Removed entities (and, on a `replace`, those missing from the new
 * set) are kept with `removedAt` so the scene can fade them out; `prune` drops them later.
 */
export function applyEntities(prev: EntityMap, m: EntitiesMessage, now: number): EntityUpdate {
  const next: EntityMap = {};
  const added: EntityView[] = [];
  const removed: string[] = [];
  const upserted = new Set<string>();
  for (const [id, e] of Object.entries(prev)) {
    if (e.removedAt === undefined || now - e.removedAt < FADE_OUT_MS) next[id] = e;
  }
  for (const u of m.upsert) {
    upserted.add(u.id);
    const old = next[u.id];
    if (old && old.removedAt === undefined) {
      next[u.id] = { ...u, seenAt: old.seenAt, fresh: old.fresh };
    } else {
      const v: EntityView = { ...u, seenAt: now, fresh: !m.replace };
      next[u.id] = v;
      if (!m.replace) added.push(v);
    }
  }
  const gone = m.replace ? Object.keys(next).filter((id) => !upserted.has(id) && next[id].removedAt === undefined) : m.remove;
  for (const id of gone) {
    const e = next[id];
    if (!e || e.removedAt !== undefined) continue;
    next[id] = { ...e, removedAt: now };
    removed.push(id);
  }
  return { entities: next, added, removed };
}

/** Drops entities whose fade-out finished; returns `prev` itself when nothing changed. */
export function pruneEntities(prev: EntityMap, now: number): EntityMap {
  let changed = false;
  const next: EntityMap = {};
  for (const [id, e] of Object.entries(prev)) {
    if (e.removedAt !== undefined && now - e.removedAt >= FADE_OUT_MS) changed = true;
    else next[id] = e;
  }
  return changed ? next : prev;
}

/** Opacity of a marker: fades in over 0.6 s, out over FADE_OUT_MS. */
export function entityOpacity(e: Pick<EntityView, 'seenAt' | 'removedAt'>, now: number): number {
  const fadeIn = Math.min(1, Math.max(0, (now - e.seenAt) / 600));
  const fadeOut = e.removedAt === undefined ? 1 : Math.max(0, 1 - (now - e.removedAt) / FADE_OUT_MS);
  return fadeIn * fadeOut;
}

/** Scale of the "new" pulse ring (0 = none): expands and repeats while the entity is new. */
export function pulsePhase(e: Pick<EntityView, 'seenAt' | 'fresh' | 'removedAt'>, now: number): number {
  if (!e.fresh || e.removedAt !== undefined) return 0;
  const age = now - e.seenAt;
  if (age < 0 || age > PULSE_MS) return 0;
  return (age % 1600) / 1600;
}

/** Display grouping for the Entities panel. */
export const KIND_GROUPS: { key: string; label: string; kinds: string[] }[] = [
  { key: 'vents', label: 'Vents and fissures', kinds: ['vent', 'fissure'] },
  { key: 'dikes', label: 'Dikes', kinds: ['dike'] },
  { key: 'features', label: 'Hot springs, fumaroles, geysers', kinds: ['feature'] },
  { key: 'flows', label: 'Lava, pyroclastic flows, lahars', kinds: ['lavaFront', 'pdc', 'lahar'] },
  { key: 'plumes', label: 'Eruption columns', kinds: ['plume'] },
  { key: 'chambers', label: 'Magma chambers', kinds: ['chamber'] },
  { key: 'stations', label: 'GNSS stations', kinds: ['station'] },
  { key: 'quakes', label: 'Notable earthquakes', kinds: ['quake'] },
];

export const KIND_LABEL: Record<string, string> = {
  vent: 'Vent',
  fissure: 'Fissure',
  dike: 'Dike',
  feature: 'Geothermal feature',
  lavaFront: 'Lava front',
  lavaField: 'Lava field',
  pdc: 'Pyroclastic flow',
  lahar: 'Lahar',
  plume: 'Eruption column',
  chamber: 'Magma chamber',
  station: 'GNSS station',
  quake: 'Earthquake',
};

/** Visible entities of a group, newest first; filtered by text and volcano. */
export function listEntities(entities: EntityMap, kinds: string[], opts: { text?: string; volcanoId?: string | null } = {}): EntityView[] {
  const text = opts.text?.trim().toLowerCase() ?? '';
  return Object.values(entities)
    .filter((e) => !e.hidden && kinds.includes(e.kind))
    .filter((e) => !opts.volcanoId || !e.volcanoId || e.volcanoId === opts.volcanoId)
    .filter((e) => !text || e.label.toLowerCase().includes(text) || e.id.toLowerCase().includes(text) || String(e.props.feature ?? '').toLowerCase().includes(text))
    .sort((a, b) => b.createdAt - a.createdAt || a.id.localeCompare(b.id));
}

/** Where an entity is, as "E 1 234 m · N 567 m". */
export function formatPlace(at: XY | [number, number, number]): string {
  const f = (v: number) => Math.round(v).toLocaleString('en-US').replace(/,/g, ' ');
  return `E ${f(at[0])} m · N ${f(at[1])} m`;
}

/** What a selection points at. */
export type Selection = { type: 'entity'; id: string } | { type: 'point'; at: XY } | { type: 'quake'; event: Extract<SimEvent, { kind: 'seismic' }> };

/** Marker colour of an entity (features by type). */
export function entityColor(e: Pick<Entity, 'kind' | 'props'>, featureColors: Record<string, string>): string {
  switch (e.kind) {
    case 'feature':
      return featureColors[String(e.props.feature)] ?? '#ffffff';
    case 'vent':
      return e.props.erupting ? '#ff5a1f' : '#ffb347';
    case 'fissure':
      return e.props.erupting ? '#ff3b1f' : '#ff8c42';
    case 'dike':
      return e.props.status === 'PROPAGATING' ? '#ff3b6b' : e.props.status === 'ERUPTED' ? '#ff8c42' : '#b05a7a';
    case 'chamber':
      return '#ff7b39';
    case 'plume':
      return '#c8c8d0';
    case 'station':
      return '#e6e6ff';
    case 'quake':
      return '#ffd166';
    case 'lavaFront':
      return '#ff6a00';
    case 'pdc':
      return '#d7a86e';
    case 'lahar':
      return '#8a6a48';
    default:
      return '#ffffff';
  }
}
