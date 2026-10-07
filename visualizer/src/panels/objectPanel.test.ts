import { describe, expect, it } from 'vitest';
import type { ObjectPanelLayout, ParamSpec } from '../protocol/messages';
import { buildPanel, ownerEntity, pinParam, tierCounts } from './objectPanel';

const p = (id: string, owner: string, tab: string, tier: ParamSpec['tier'], extra: Partial<ParamSpec> = {}): ParamSpec => ({
  id,
  label: id.slice(id.lastIndexOf('.') + 1),
  group: 'g',
  type: 'number',
  apply: 'live',
  owner,
  tab,
  tier,
  value: 1,
  default: 1,
  ...extra,
});

const chamber: ObjectPanelLayout = {
  tabs: [
    { id: 'overview', title: 'Overview', sections: [{ title: 'Right now', fields: [{ measure: 'overpressureMPa', label: 'Overpressure', unit: 'MPa' }] }] },
    { id: 'supply', title: 'Supply', sections: [] },
    { id: 'walls', title: 'Walls', sections: [{ fields: [{ measure: 'ruptureOverpressureMPa', derived: true, pin: 'wallRuptureRatio' }] }] },
    { id: 'overrides', title: 'Overrides', sections: [] },
    { id: 'empty', title: 'Empty', sections: [{ fields: [{ measure: 'missing' }] }] },
  ],
};

const M = 'volcano.v.magma.chamber';
const DEEP = 'volcano.v.magma.chambers[deep]';
const schema = {
  objectPanels: { chamber },
  params: [
    p(`${M}.supplyRate`, M, 'supply', 'primary', { order: 0 }),
    p(`${M}.supplyVariability`, M, 'supply', 'more'),
    p(`${M}.stepPeriod`, M, 'supply', 'internals'),
    p(`${M}.rechargeSilicaWt`, M, 'magma', 'primary'),
    p(`${M}.wallRuptureRatio`, M, 'overrides', 'primary', { value: 3, default: 2 }),
    p('volcano.v.dikes.blocked', 'volcano.v.dikes', 'overrides', 'primary', { type: 'boolean', value: false, default: false }),
    p(`${DEEP}.supplyRate`, DEEP, 'supply', 'primary'),
    p('world.climate.rainfallMmPerHour', 'world', 'weather', 'primary'),
  ],
};

describe('object panels from the server spec', () => {
  it('puts each of the object’s settings on its tab, by tier, and only its own', () => {
    const panel = buildPanel(schema, 'chamber', [M, 'volcano.v.dikes'], { overpressureMPa: 3, ruptureOverpressureMPa: 10 });
    const supply = panel.tabs.find((t) => t.id === 'supply')!;
    expect(supply.params.primary.map((x) => x.id)).toEqual([`${M}.supplyRate`]);
    expect(supply.params.more.map((x) => x.id)).toEqual([`${M}.supplyVariability`]);
    expect(supply.params.internals.map((x) => x.id)).toEqual([`${M}.stepPeriod`]);
    // the deep chamber's and the world's settings are not the main chamber's
    expect(panel.tabs.flatMap((t) => [...t.params.primary, ...t.params.more]).some((x) => x.owner === DEEP || x.owner === 'world')).toBe(false);
    expect(tierCounts(panel)).toEqual({ primary: 4, more: 1, internals: 1 });
  });

  it('a further chamber shows its own settings', () => {
    const panel = buildPanel(schema, 'chamber', [DEEP], {});
    expect(panel.tabs.map((t) => t.id)).toEqual(['supply']);
    expect(panel.tabs[0].params.primary[0].id).toBe(`${DEEP}.supplyRate`);
  });

  it('a setting on a tab the layout lacks lands on "Other", tabs with nothing to show are left out', () => {
    const panel = buildPanel(schema, 'chamber', [M], { overpressureMPa: 3 });
    const ids = panel.tabs.map((t) => t.id);
    expect(ids).toContain('other');
    expect(ids).not.toContain('empty');
    expect(ids).not.toContain('walls'); // its only value is not in the props
    expect(panel.tabs.find((t) => t.id === 'other')!.params.primary[0].id).toBe(`${M}.rechargeSilicaWt`);
  });

  it('lists overrides in effect, and finds the setting that pins a derived value', () => {
    const panel = buildPanel(schema, 'chamber', [M, 'volcano.v.dikes'], {});
    expect(panel.overrides.map((x) => x.id)).toEqual([`${M}.wallRuptureRatio`]);
    const f = chamber.tabs[2].sections[0].fields[0];
    expect(pinParam(schema, [M], f)?.id).toBe(`${M}.wallRuptureRatio`);
    expect(pinParam(schema, [DEEP], f)).toBeUndefined();
  });

  it('no schema: no tabs', () => {
    expect(buildPanel(null, 'chamber', [M]).tabs).toEqual([]);
  });

  it('finds the object whose Inspector shows a setting', () => {
    const e = (id: string, owners: string[]) => ({ id, kind: 'chamber', label: id, at: [0, 0, 0] as [number, number, number], props: {}, createdAt: 0, updatedAt: 0, seenAt: 0, fresh: false, paramOwners: owners });
    const entities = { 'chamber:v': e('chamber:v', [M]), 'chamber:v:deep': e('chamber:v:deep', [DEEP]) };
    expect(ownerEntity(entities, { owner: DEEP })).toBe('chamber:v:deep');
    expect(ownerEntity(entities, { owner: 'nope' })).toBeNull();
  });
});
