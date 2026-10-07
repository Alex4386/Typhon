import { describe, expect, it } from 'vitest';
import type { ParamSpec, SchemaMessage, VolcanoState } from '../protocol/messages';
import type { EntityMap, EntityView } from '../store/entities';
import { contextActions, contextToggles, dikeNumber, injectFieldsFor, selectionVolcano, ventLifecycle } from './actions';

const ent = (id: string, kind: string, volcanoId?: string): EntityView => ({ id, kind, volcanoId, label: id, at: [0, 0, 0], props: {}, createdAt: 0, updatedAt: 0, seenAt: 0, fresh: false });
const entities: EntityMap = Object.fromEntries(
  [ent('c2', 'chamber', 'b'), ent('v1', 'vent', 'a'), ent('d1', 'dike', 'a'), ent('f1', 'feature', 'a')].map((e) => [e.id, e]),
);
const vs = (rate: number) => ({ chamber: { eruptionRate: rate }, alert: { level: rate > 0 ? 'ERUPTING' : 'DORMANT' } }) as unknown as VolcanoState;

describe('inspector actions', () => {
  it('offers a chamber its own volcano’s magma, eruption and dike actions', () => {
    const acts = contextActions({ type: 'entity', id: 'c2' }, entities, { a: vs(0), b: vs(0) });
    expect(acts.map((a) => a.id)).toEqual(['inject', 'startEruption', 'forceDike', 'section', 'removeVolcano']);
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

  it('uses the volcano’s own injection defaults', () => {
    const f = (id: string, extra: Partial<ParamSpec> = {}): ParamSpec => ({ id, label: id, group: 'g', type: 'number', apply: 'live', ...extra });
    const schema = {
      commands: { injectMagma: [f('volumeM3'), f('temperatureC', { default: 1150 })], 'injectMagma@b': [f('volumeM3'), f('temperatureC', { default: 900 })] },
      params: [f('b:magma.chamber.supplyRate', { volcanoId: 'b' }), f('a:magma.chamber.supplyRate', { volcanoId: 'a' }), f('b:magma.chamber.rechargeSilicaWt', { volcanoId: 'b' }), f('b:dikes.x', { volcanoId: 'b' })],
    } as unknown as SchemaMessage;
    expect(injectFieldsFor(schema, 'b')?.[1].default).toBe(900);
    expect(injectFieldsFor(schema, 'a')?.[1].default).toBe(1150);
  });

  it('removes vents and dikes; seals and dike blocking are checkboxes', () => {
    const withProps = (e: EntityView, props: Record<string, unknown>): EntityView => ({ ...e, props: props as EntityView['props'] });
    const map: EntityMap = {
      summit: withProps(ent('vent:a:summit', 'vent', 'a'), { ventId: 'summit', state: 'active', sealed: false }),
      fis: withProps(ent('vent:a:f1', 'fissure', 'a'), { ventId: 'f1', state: 'sealed', sealed: true }),
      dike: ent('dike:a:7', 'dike', 'a'),
      ch: withProps(ent('chamber:a', 'chamber', 'a'), { dikesBlocked: true }),
    };
    const ids = (id: string) => contextActions({ type: 'entity', id }, map, {}).map((a) => a.id);
    expect(ids('summit')).toEqual(['startEruption', 'frame', 'section']); // a summit vent cannot be removed
    expect(ids('fis')).toEqual(['startEruption', 'removeVent', 'frame', 'section']);
    expect(ids('dike')).toEqual(['section', 'frame', 'removeDike']);
    expect(contextActions({ type: 'entity', id: 'dike' }, map, {}).find((a) => a.id === 'removeDike')).toMatchObject({ dikeId: 7, volcanoId: 'a' });
    const toggles = (id: string) => contextToggles({ type: 'entity', id }, map);
    expect(toggles('ch')).toMatchObject([{ id: 'blockDikes', checked: true, volcanoId: 'a' }]);
    expect(toggles('summit')).toMatchObject([{ id: 'sealVent', checked: false, ventId: 'summit' }]);
    expect(toggles('fis')).toMatchObject([{ id: 'sealVent', checked: true, ventId: 'f1' }]);
    expect(toggles('dike')).toEqual([]);
    const frozen: EntityMap = { f: withProps(ent('vent:a:f2', 'fissure', 'a'), { ventId: 'f2', state: 'frozen' }) };
    expect(contextToggles({ type: 'entity', id: 'f' }, frozen)[0]).toHaveProperty('disabled');
    expect(dikeNumber('dike:kilauea:12')).toBe(12);
    expect(ventLifecycle({ sealed: true })).toBe('sealed');
    expect(ventLifecycle({ state: 'frozen', sealed: false })).toBe('frozen');
    expect(ventLifecycle({ erupting: true })).toBe('active');
  });

  it('links a vent to the dikes feeding it and back', () => {
    const withProps = (e: EntityView, props: Record<string, unknown>): EntityView => ({ ...e, props: props as EntityView['props'] });
    const map: EntityMap = {
      'chamber:a': ent('chamber:a', 'chamber', 'a'),
      'vent:a:f1': withProps(ent('vent:a:f1', 'fissure', 'a'), { ventId: 'f1', state: 'active' }),
      'dike:a:3': withProps({ ...ent('dike:a:3', 'dike', 'a'), label: 'Dike 3' }, { fissure: 'f1' }),
      'dike:a:4': withProps(ent('dike:a:4', 'dike', 'a'), { fissure: 'other' }),
    };
    const links = (id: string) => contextActions({ type: 'entity', id }, map, {}).filter((a) => a.id === 'inspect');
    expect(links('vent:a:f1')).toEqual([
      { id: 'inspect', label: 'Inspect Dike 3', target: 'dike:a:3' },
      { id: 'inspect', label: 'Inspect chamber', target: 'chamber:a' },
    ]);
    expect(links('dike:a:3').map((a) => 'target' in a && a.target)).toEqual(['vent:a:f1', 'chamber:a']);
  });
});
