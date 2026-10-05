import { describe, expect, it } from 'vitest';
import type { EntitiesMessage, Entity } from '../protocol/messages';
import { FADE_OUT_MS, applyEntities, entityOpacity, listEntities, pruneEntities, pulsePhase } from './entities';

function ent(id: string, kind = 'feature', extra: Partial<Entity> = {}): Entity {
  return { id, kind, label: id, at: [0, 0, 0], props: {}, createdAt: 1, updatedAt: 1, ...extra };
}
function msg(upsert: Entity[], remove: string[] = [], replace = false): EntitiesMessage {
  return { type: 'entities', time: 0, replace, upsert, remove };
}

describe('entity store', () => {
  it('a full set is not "new"; later arrivals are', () => {
    const a = applyEntities({}, msg([ent('a')], [], true), 1000);
    expect(a.added).toEqual([]);
    expect(a.entities.a.fresh).toBe(false);
    const b = applyEntities(a.entities, msg([ent('b')]), 2000);
    expect(b.added.map((e) => e.id)).toEqual(['b']);
    expect(b.entities.b.fresh).toBe(true);
    expect(b.entities.b.seenAt).toBe(2000);
  });

  it('updates keep when the client first saw the entity', () => {
    const a = applyEntities({}, msg([ent('a')]), 1000);
    const b = applyEntities(a.entities, msg([ent('a', 'feature', { props: { groundTemperatureC: 90 }, updatedAt: 5 })]), 3000);
    expect(b.added).toEqual([]);
    expect(b.entities.a.seenAt).toBe(1000);
    expect(b.entities.a.props.groundTemperatureC).toBe(90);
  });

  it('removal fades out, then the entity is pruned', () => {
    const a = applyEntities({}, msg([ent('a'), ent('b')]), 0);
    const b = applyEntities(a.entities, msg([], ['a']), 1000);
    expect(b.removed).toEqual(['a']);
    expect(b.entities.a.removedAt).toBe(1000);
    expect(entityOpacity(b.entities.a, 1000 + FADE_OUT_MS / 2)).toBeCloseTo(0.5);
    expect(pruneEntities(b.entities, 1000 + FADE_OUT_MS - 1)).toBe(b.entities);
    expect(Object.keys(pruneEntities(b.entities, 1000 + FADE_OUT_MS))).toEqual(['b']);
  });

  it('a replace removes what is missing and revives what returns', () => {
    const a = applyEntities({}, msg([ent('a'), ent('b')], [], true), 0);
    const b = applyEntities(a.entities, msg([ent('b')], [], true), 100);
    expect(b.removed).toEqual(['a']);
    const c = applyEntities(b.entities, msg([ent('a')]), 200);
    expect(c.entities.a.removedAt).toBeUndefined();
    expect(c.added.map((e) => e.id)).toEqual(['a']);
  });

  it('pulses only while new', () => {
    const e = { seenAt: 0, fresh: true, removedAt: undefined };
    expect(pulsePhase(e, 400)).toBeGreaterThan(0);
    expect(pulsePhase(e, 60_000)).toBe(0);
    expect(pulsePhase({ ...e, fresh: false }, 400)).toBe(0);
  });

  it('lists by kind, newest first, filtered', () => {
    const { entities } = applyEntities(
      {},
      msg([
        ent('f1', 'feature', { createdAt: 5, label: 'Hot spring', props: { feature: 'HOT_SPRING' } }),
        ent('f2', 'feature', { createdAt: 9, label: 'Fumarole', volcanoId: 'b' }),
        ent('d1', 'dike'),
        ent('h', 'feature', { hidden: true }),
      ]),
      0,
    );
    expect(listEntities(entities, ['feature']).map((e) => e.id)).toEqual(['f2', 'f1']);
    expect(listEntities(entities, ['feature'], { text: 'spring' }).map((e) => e.id)).toEqual(['f1']);
    expect(listEntities(entities, ['feature'], { volcanoId: 'a' }).map((e) => e.id)).toEqual(['f1']);
  });
});
