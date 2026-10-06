import { describe, expect, it } from 'vitest';
import type { ParamSpec, SchemaMessage, VolcanoState } from '../protocol/messages';
import type { EntityMap, EntityView } from '../store/entities';
import { contextActions, injectFieldsFor, selectionVolcano, supplyParams } from './actions';

const ent = (id: string, kind: string, volcanoId?: string): EntityView => ({ id, kind, volcanoId, label: id, at: [0, 0, 0], props: {}, createdAt: 0, updatedAt: 0, seenAt: 0, fresh: false });
const entities: EntityMap = Object.fromEntries(
  [ent('c2', 'chamber', 'b'), ent('v1', 'vent', 'a'), ent('d1', 'dike', 'a'), ent('f1', 'feature', 'a')].map((e) => [e.id, e]),
);
const vs = (rate: number) => ({ chamber: { eruptionRate: rate }, alert: { level: rate > 0 ? 'ERUPTING' : 'DORMANT' } }) as unknown as VolcanoState;

describe('inspector actions', () => {
  it('offers a chamber its own volcano’s magma, eruption, dike and supply actions', () => {
    const acts = contextActions({ type: 'entity', id: 'c2' }, entities, { a: vs(0), b: vs(0) });
    expect(acts.map((a) => a.id)).toEqual(['inject', 'startEruption', 'forceDike', 'supply', 'section']);
    expect(acts.filter((a) => 'volcanoId' in a).every((a) => 'volcanoId' in a && a.volcanoId === 'b')).toBe(true);
    expect(contextActions({ type: 'entity', id: 'c2' }, entities, { b: vs(5) })[1].id).toBe('stopEruption');
  });

  it('maps vents, dikes, points and quakes', () => {
    expect(contextActions({ type: 'entity', id: 'v1' }, entities, { a: vs(3) }).map((a) => a.id)).toEqual(['stopEruption', 'frame', 'section']);
    expect(contextActions({ type: 'entity', id: 'd1' }, entities, {}).map((a) => a.id)).toEqual(['section', 'frame']);
    expect(contextActions({ type: 'point', at: [1, 2] }, entities, {}).map((a) => a.id)).toEqual(['water', 'dig', 'section']);
    expect(contextActions({ type: 'entity', id: 'f1' }, entities, {}).map((a) => a.id)).toEqual(['frame', 'section']);
    expect(contextActions({ type: 'entity', id: 'gone' }, entities, {})).toEqual([]);
    expect(contextActions(null, entities, {})).toEqual([]);
  });

  it('knows which volcano a selection belongs to', () => {
    expect(selectionVolcano({ type: 'entity', id: 'c2' }, entities)).toBe('b');
    expect(selectionVolcano({ type: 'point', at: [0, 0] }, entities)).toBeNull();
  });

  it('uses the volcano’s own injection defaults and supply settings', () => {
    const f = (id: string, extra: Partial<ParamSpec> = {}): ParamSpec => ({ id, label: id, group: 'g', type: 'number', apply: 'hot', ...extra });
    const schema = {
      commands: { injectMagma: [f('volumeM3'), f('temperatureC', { default: 1150 })], 'injectMagma@b': [f('volumeM3'), f('temperatureC', { default: 900 })] },
      params: [f('b:magma.chamber.supplyRate', { volcanoId: 'b' }), f('a:magma.chamber.supplyRate', { volcanoId: 'a' }), f('b:magma.chamber.rechargeSilicaWt', { volcanoId: 'b' }), f('b:dikes.x', { volcanoId: 'b' })],
    } as unknown as SchemaMessage;
    expect(injectFieldsFor(schema, 'b')?.[1].default).toBe(900);
    expect(injectFieldsFor(schema, 'a')?.[1].default).toBe(1150);
    expect(supplyParams(schema, 'b').map((p) => p.id)).toEqual(['b:magma.chamber.supplyRate', 'b:magma.chamber.rechargeSilicaWt']);
  });
});
