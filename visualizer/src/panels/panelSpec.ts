import type { EntityProp, PanelField, PanelSection, ParamSpec, SchemaMessage } from '../protocol/messages';
import type { EntityMap } from '../store/entities';
import { atRest } from './paramState';

/** The settings of one Inspector tab, by tier: dials, a collapsed "More", collapsed "Solver internals". */
export interface TabParams {
  primary: ParamSpec[];
  more: ParamSpec[];
  internals: ParamSpec[];
}

/** One tab of an object's panel: the server's sections plus the settings placed on it. */
export interface BuiltTab {
  id: string;
  title: string;
  sections: PanelSection[];
  params: TabParams;
}

export interface BuiltPanel {
  tabs: BuiltTab[];
  /** Overrides of the physics currently in effect (settings of an "overrides" tab off their default). */
  overrides: ParamSpec[];
}

const byOrder = (a: ParamSpec, b: ParamSpec) => (a.order ?? 50) - (b.order ?? 50) || a.label.localeCompare(b.label);

/** Whether a field has something to show for these props (a widget always does). */
export function fieldShown(f: PanelField, props: Record<string, EntityProp> | undefined): boolean {
  if (f.widget) return true;
  if (!f.measure) return false;
  const v = props?.[f.measure];
  return v !== undefined && v !== null;
}

/**
 * An object's Inspector panel from the server's spec: the tabs of its kind's layout, each with the
 * settings whose owner is one of the object's `owners` and whose tab is that tab, split by tier.
 * Settings on a tab the layout lacks land on an "Other" tab, so nothing is unreachable. Tabs with
 * nothing to show are left out. The client decides nothing about which setting goes where.
 */
export function buildPanel(schema: Pick<SchemaMessage, 'params' | 'objectPanels'> | null | undefined, kind: string, owners: string[], props?: Record<string, EntityProp>): BuiltPanel {
  const layout = schema?.objectPanels?.[kind];
  const own = new Set(owners);
  const params = (schema?.params ?? []).filter((p) => p.owner !== undefined && own.has(p.owner));
  const tabs: BuiltTab[] = (layout?.tabs ?? []).map((t) => ({ id: t.id, title: t.title, sections: t.sections, params: { primary: [], more: [], internals: [] } }));
  const byId = new Map(tabs.map((t) => [t.id, t]));
  for (const p of params) {
    let t = byId.get(p.tab ?? '');
    if (!t) {
      t = byId.get('other');
      if (!t) {
        t = { id: 'other', title: 'Other', sections: [], params: { primary: [], more: [], internals: [] } };
        tabs.push(t);
        byId.set('other', t);
      }
    }
    t.params[p.tier ?? 'more'].push(p);
  }
  for (const t of tabs) {
    t.params.primary.sort(byOrder);
    t.params.more.sort(byOrder);
    t.params.internals.sort(byOrder);
  }
  const shown = tabs.filter(
    (t) => t.params.primary.length + t.params.more.length + t.params.internals.length > 0 || t.sections.some((s) => s.fields.some((f) => fieldShown(f, props))),
  );
  const overrides = params.filter((p) => p.tab === 'overrides' && !atRest(p, undefined));
  return { tabs: shown, overrides };
}

/** The setting a derived field can be pinned with: `<first owner>.<pin>`, if the schema has it. */
export function pinParam(schema: Pick<SchemaMessage, 'params'> | null | undefined, owners: string[], f: PanelField): ParamSpec | undefined {
  if (!f.pin || owners.length === 0) return undefined;
  const id = `${owners[0]}.${f.pin}`;
  return schema?.params.find((p) => p.id === id);
}

/** Counts per tier, for a quick summary ("5 dials · 7 more"). */
export function tierCounts(panel: BuiltPanel): Record<keyof TabParams, number> {
  const c = { primary: 0, more: 0, internals: 0 };
  for (const t of panel.tabs) {
    c.primary += t.params.primary.length;
    c.more += t.params.more.length;
    c.internals += t.params.internals.length;
  }
  return c;
}

/** The entity whose Inspector shows a setting (the first that lists its owner), if any. */
export function ownerEntity(entities: EntityMap, p: Pick<ParamSpec, 'owner'>): string | null {
  if (!p.owner) return null;
  for (const e of Object.values(entities)) if (e.paramOwners?.includes(p.owner)) return e.id;
  return null;
}
