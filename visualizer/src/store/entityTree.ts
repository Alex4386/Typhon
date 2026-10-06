import type { Entity } from '../protocol/messages';
import { isDeposit, type EntityMap, type EntityView } from './entities';

/** Top-level tabs of the Entities panel. */
export type EntityTab = 'volcano' | 'hydro' | 'monitoring' | 'quakes';

/** `short` fits the tab row of a narrow drawer; `label` is the full name (tooltip, headings). */
export const ENTITY_TABS: { key: EntityTab; label: string; short: string }[] = [
  { key: 'volcano', label: 'Volcano', short: 'Volcano' },
  { key: 'hydro', label: 'Hydrothermal & ground', short: 'Hydrothermal' },
  { key: 'monitoring', label: 'Monitoring', short: 'Monitoring' },
  { key: 'quakes', label: 'Earthquakes', short: 'Quakes' },
];

export interface EntityCategory {
  key: string;
  label: string;
  tab: EntityTab;
  /** Sub-heading inside a tab (e.g. openings vs ground deposits). */
  section?: string;
  match: (e: Pick<Entity, 'kind' | 'props'>) => boolean;
  /** Drawn in the 3D view (lava fields and chambers have their own renderers). */
  drawn: boolean;
}

const feature = (name: string) => (e: Pick<Entity, 'kind' | 'props'>) => e.kind === 'feature' && e.props.feature === name;
const kind = (k: string) => (e: Pick<Entity, 'kind' | 'props'>) => e.kind === k;

/**
 * Every entity belongs to exactly one category (the first that matches). Ground deposits — sulfur,
 * altered ground, sinter, cinnabar — each get their own category so they can be hidden separately
 * from the openings (fumaroles, springs, geysers) that produce them.
 */
export const CATEGORIES: EntityCategory[] = [
  { key: 'chambers', label: 'Magma chambers', tab: 'volcano', match: kind('chamber'), drawn: false },
  { key: 'pathways', label: 'Magma pathways', tab: 'volcano', match: kind('connection'), drawn: false },
  { key: 'vents', label: 'Vents', tab: 'volcano', match: kind('vent'), drawn: true },
  { key: 'fissures', label: 'Fissures', tab: 'volcano', match: kind('fissure'), drawn: true },
  { key: 'dikes', label: 'Dikes', tab: 'volcano', match: kind('dike'), drawn: true },
  { key: 'lavaFronts', label: 'Lava fronts', tab: 'volcano', match: kind('lavaFront'), drawn: true },
  { key: 'lavaFields', label: 'Lava fields', tab: 'volcano', match: kind('lavaField'), drawn: false },
  { key: 'pdcs', label: 'Pyroclastic flows', tab: 'volcano', match: kind('pdc'), drawn: true },
  { key: 'lahars', label: 'Lahars', tab: 'volcano', match: kind('lahar'), drawn: true },
  { key: 'plumes', label: 'Eruption columns', tab: 'volcano', match: kind('plume'), drawn: true },
  { key: 'fumaroles', label: 'Fumaroles', tab: 'hydro', section: 'Openings', match: feature('FUMAROLE'), drawn: true },
  { key: 'geysers', label: 'Geysers', tab: 'hydro', section: 'Openings', match: feature('GEYSER'), drawn: true },
  { key: 'hotSprings', label: 'Hot springs', tab: 'hydro', section: 'Openings', match: feature('HOT_SPRING'), drawn: true },
  { key: 'sulfurSprings', label: 'Sulfur springs', tab: 'hydro', section: 'Openings', match: feature('SULFUR_SPRING'), drawn: true },
  { key: 'mudPots', label: 'Mud pots', tab: 'hydro', section: 'Openings', match: feature('MUD_POT'), drawn: true },
  { key: 'submarineVents', label: 'Submarine vents', tab: 'hydro', section: 'Openings', match: feature('SUBMARINE_VENT'), drawn: true },
  { key: 'sulfurDeposits', label: 'Sulfur deposits', tab: 'hydro', section: 'Ground deposits', match: feature('SULFUR_DEPOSIT'), drawn: true },
  { key: 'alteredGround', label: 'Altered ground', tab: 'hydro', section: 'Ground deposits', match: feature('ACID_ALTERATION'), drawn: true },
  { key: 'sinter', label: 'Sinter', tab: 'hydro', section: 'Ground deposits', match: feature('SINTER'), drawn: true },
  { key: 'cinnabar', label: 'Cinnabar', tab: 'hydro', section: 'Ground deposits', match: feature('CINNABAR'), drawn: true },
  { key: 'otherDeposits', label: 'Other deposits', tab: 'hydro', section: 'Ground deposits', match: (e) => isDeposit(e), drawn: true },
  { key: 'otherFeatures', label: 'Other features', tab: 'hydro', section: 'Openings', match: kind('feature'), drawn: true },
  { key: 'stations', label: 'GNSS stations', tab: 'monitoring', match: kind('station'), drawn: true },
  { key: 'quakes', label: 'Notable earthquakes', tab: 'quakes', match: kind('quake'), drawn: true },
];

const BY_KEY = new Map(CATEGORIES.map((c) => [c.key, c]));

export function categoryByKey(key: string): EntityCategory | undefined {
  return BY_KEY.get(key);
}

/** Category key of an entity ('other' when nothing matches, e.g. a kind added later). */
export function categoryOf(e: Pick<Entity, 'kind' | 'props'>): string {
  for (const c of CATEGORIES) if (c.match(e)) return c.key;
  return 'other';
}

export type EntitySort = 'time' | 'temperature' | 'name';

/** A temperature to sort by (°C), from whichever property the kind reports; −∞ when none. */
export function temperatureOf(e: Pick<Entity, 'props'>): number {
  const p = e.props;
  for (const k of ['groundTemperatureC', 'temperatureC', 'ventTemperatureC']) {
    const v = p[k];
    if (typeof v === 'number' && Number.isFinite(v)) return v;
  }
  return Number.NEGATIVE_INFINITY;
}

export function sortEntities<T extends EntityView>(rows: T[], sort: EntitySort): T[] {
  const byTime = (a: T, b: T) => b.createdAt - a.createdAt || a.id.localeCompare(b.id);
  switch (sort) {
    case 'name':
      return rows.sort((a, b) => a.label.localeCompare(b.label, undefined, { numeric: true }) || a.id.localeCompare(b.id));
    case 'temperature':
      return rows.sort((a, b) => temperatureOf(b) - temperatureOf(a) || byTime(a, b));
    default:
      return rows.sort(byTime);
  }
}

export interface TreeGroup {
  category: EntityCategory;
  rows: EntityView[];
  /** Rows split by volcano when the world has several (volcano id → rows; '' = none). */
  byVolcano: { volcanoId: string; rows: EntityView[] }[] | null;
}

export interface TreeSection {
  title: string | null;
  groups: TreeGroup[];
}

export interface TreeOptions {
  text?: string;
  volcanoId?: string | null;
  sort?: EntitySort;
  /** Volcano ids of the world; with more than one, groups are split by volcano. */
  volcanoes?: string[];
}

function matchesText(e: EntityView, text: string): boolean {
  if (!text) return true;
  return e.label.toLowerCase().includes(text) || e.id.toLowerCase().includes(text) || String(e.props.feature ?? '').toLowerCase().replace(/_/g, ' ').includes(text);
}

/**
 * The tree of one tab: sections (sub-headings) of categories with their rows, filtered by text and
 * volcano, sorted. Empty categories are left out.
 */
export function buildTree(entities: EntityMap | EntityView[], tab: EntityTab, opts: TreeOptions = {}): TreeSection[] {
  const text = opts.text?.trim().toLowerCase() ?? '';
  const rowsBy = new Map<string, EntityView[]>();
  for (const e of Array.isArray(entities) ? entities : Object.values(entities)) {
    if (e.hidden) continue;
    if (opts.volcanoId && e.volcanoId && e.volcanoId !== opts.volcanoId) continue;
    const c = categoryOf(e);
    if (categoryByKey(c)?.tab !== tab || !matchesText(e, text)) continue;
    let arr = rowsBy.get(c);
    if (!arr) rowsBy.set(c, (arr = []));
    arr.push(e);
  }
  const split = (opts.volcanoes?.length ?? 0) > 1 && !opts.volcanoId;
  const sections: TreeSection[] = [];
  for (const c of CATEGORIES) {
    if (c.tab !== tab) continue;
    const rows = rowsBy.get(c.key);
    if (!rows || rows.length === 0) continue;
    sortEntities(rows, opts.sort ?? 'time');
    let byVolcano: TreeGroup['byVolcano'] = null;
    if (split) {
      const m = new Map<string, EntityView[]>();
      for (const r of rows) {
        const k = r.volcanoId ?? '';
        let arr = m.get(k);
        if (!arr) m.set(k, (arr = []));
        arr.push(r);
      }
      if (m.size > 1) byVolcano = [...m.entries()].sort((a, b) => order(opts.volcanoes!, a[0]) - order(opts.volcanoes!, b[0])).map(([volcanoId, rows]) => ({ volcanoId, rows }));
    }
    const title = c.section ?? null;
    let sec = sections[sections.length - 1];
    if (!sec || sec.title !== title) sections.push((sec = { title, groups: [] }));
    sec.groups.push({ category: c, rows, byVolcano });
  }
  return sections;
}

function order(ids: string[], id: string): number {
  const i = ids.indexOf(id);
  return i < 0 ? ids.length : i;
}

/** Entity counts per tab (for the tab badges), ignoring hidden entities. */
export function tabCounts(entities: EntityMap): Record<EntityTab, number> {
  const out: Record<EntityTab, number> = { volcano: 0, hydro: 0, monitoring: 0, quakes: 0 };
  for (const e of Object.values(entities)) {
    if (e.hidden || e.removedAt !== undefined) continue;
    const tab = categoryByKey(categoryOf(e))?.tab;
    if (tab) out[tab]++;
  }
  return out;
}

/** Whether an entity is hidden in the 3D view by its category's visibility toggle. */
export function hiddenByCategory(e: Pick<Entity, 'kind' | 'props'>, hidden: Record<string, boolean>): boolean {
  return hidden[categoryOf(e)] === true;
}
