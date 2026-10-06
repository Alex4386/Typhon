import { describe, expect, it } from 'vitest';
import type { EntityView } from './entities';
import { buildTree, CATEGORIES, categoryOf, hiddenByCategory, sortEntities, tabCounts } from './entityTree';

let n = 0;
function ent(kind: string, props: Record<string, string | number | boolean> = {}, extra: Partial<EntityView> = {}): EntityView {
  n++;
  return { id: `${kind}-${n}`, kind, label: `${kind} ${n}`, at: [0, 0, 0], props, createdAt: n, updatedAt: n, seenAt: 0, fresh: false, ...extra };
}

describe('entity tree', () => {
  it('puts every kind and feature type in the right tab and category', () => {
    expect(categoryOf(ent('vent'))).toBe('vents');
    expect(categoryOf(ent('chamber'))).toBe('chambers');
    expect(categoryOf(ent('dike'))).toBe('dikes');
    expect(categoryOf(ent('feature', { feature: 'FUMAROLE' }))).toBe('fumaroles');
    expect(categoryOf(ent('feature', { feature: 'SULFUR_DEPOSIT' }))).toBe('sulfurDeposits');
    expect(categoryOf(ent('feature', { feature: 'ACID_ALTERATION' }))).toBe('alteredGround');
    expect(categoryOf(ent('feature', { feature: 'SINTER' }))).toBe('sinter');
    expect(categoryOf(ent('feature', { feature: 'CINNABAR' }))).toBe('cinnabar');
    expect(categoryOf(ent('feature', { feature: 'SOMETHING_NEW' }))).toBe('otherDeposits');
    expect(categoryOf(ent('station'))).toBe('stations');
    expect(categoryOf(ent('quake', { magnitude: 3 }))).toBe('quakes');
    expect(categoryOf(ent('mystery'))).toBe('other');
    const tabs = new Map(CATEGORIES.map((c) => [c.key, c.tab]));
    expect(tabs.get('plumes')).toBe('volcano');
    expect(tabs.get('sinter')).toBe('hydro');
    expect(tabs.get('stations')).toBe('monitoring');
    expect(tabs.get('quakes')).toBe('quakes');
  });

  it('builds a tab as sections of non-empty categories, filtered by text', () => {
    const all = [ent('feature', { feature: 'GEYSER' }), ent('feature', { feature: 'SINTER' }), ent('feature', { feature: 'SINTER' }), ent('vent'), ent('feature', { feature: 'HOT_SPRING' }, { hidden: true })];
    const tree = buildTree(all, 'hydro');
    expect(tree.map((s) => s.title)).toEqual(['Openings', 'Ground deposits']);
    expect(tree[0].groups.map((g) => g.category.key)).toEqual(['geysers']);
    expect(tree[1].groups[0].rows).toHaveLength(2);
    expect(buildTree(all, 'hydro', { text: 'geyser' }).flatMap((s) => s.groups.map((g) => g.category.key))).toEqual(['geysers']);
    expect(buildTree(all, 'monitoring')).toEqual([]);
  });

  it('splits categories by volcano when the world has several, unless one is chosen', () => {
    const all = [ent('vent', {}, { volcanoId: 'b' }), ent('vent', {}, { volcanoId: 'a' }), ent('vent', {}, { volcanoId: 'a' })];
    const g = buildTree(all, 'volcano', { volcanoes: ['a', 'b'] })[0].groups[0];
    expect(g.byVolcano?.map((v) => [v.volcanoId, v.rows.length])).toEqual([
      ['a', 2],
      ['b', 1],
    ]);
    const only = buildTree(all, 'volcano', { volcanoes: ['a', 'b'], volcanoId: 'b' })[0].groups[0];
    expect(only.byVolcano).toBeNull();
    expect(only.rows).toHaveLength(1);
  });

  it('sorts by time, temperature or name', () => {
    const a = ent('feature', { feature: 'FUMAROLE', groundTemperatureC: 95 }, { label: 'Fumarole 10', createdAt: 5 });
    const b = ent('feature', { feature: 'FUMAROLE', groundTemperatureC: 300 }, { label: 'Fumarole 2', createdAt: 1 });
    const c = ent('feature', { feature: 'FUMAROLE' }, { label: 'Fumarole 1', createdAt: 9 });
    expect(sortEntities([a, b, c], 'time').map((e) => e.label)).toEqual(['Fumarole 1', 'Fumarole 10', 'Fumarole 2']);
    expect(sortEntities([a, b, c], 'temperature').map((e) => e.label)).toEqual(['Fumarole 2', 'Fumarole 10', 'Fumarole 1']);
    expect(sortEntities([a, b, c], 'name').map((e) => e.label)).toEqual(['Fumarole 1', 'Fumarole 2', 'Fumarole 10']);
  });

  it('counts per tab and hides by category', () => {
    const all = [ent('vent'), ent('feature', { feature: 'SINTER' }), ent('station'), ent('station'), ent('quake', {}, { removedAt: 1 })];
    expect(tabCounts(Object.fromEntries(all.map((e) => [e.id, e])))).toEqual({ volcano: 1, hydro: 1, monitoring: 2, quakes: 0 });
    expect(hiddenByCategory(all[1], { sinter: true })).toBe(true);
    expect(hiddenByCategory(all[0], { sinter: true })).toBe(false);
  });
});
