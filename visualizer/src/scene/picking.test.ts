import { describe, expect, it } from 'vitest';
import type { Entity } from '../protocol/messages';
import type { EntityMap, EntityView } from '../store/entities';
import { frameDistance, nearestSurfaceEntity, pickRadius, sceneToWorld, sectionThrough, selectionAnchor, toScene } from './picking';

function view(id: string, kind: string, at: [number, number, number], extra: Partial<Entity> = {}): EntityView {
  return { id, kind, label: id, at, props: {}, createdAt: 0, updatedAt: 0, seenAt: 0, fresh: false, ...extra };
}

const map: EntityMap = {
  spring: view('spring', 'feature', [100, 100, 50]),
  vent: view('vent', 'vent', [400, 100, 80]),
  chamber: view('chamber', 'chamber', [100, 100, -3000], { props: { radiusM: 800 } }),
  dike: view('dike', 'dike', [300, 100, -500], { path: [[100, 100, -3000], [300, 100, -500]] }),
  fissure: view('fissure', 'fissure', [0, 0, 10], { props: { strikeDeg: 90, lengthM: 400 } }),
  gone: view('gone', 'feature', [105, 100, 50], { removedAt: 1 }),
};

describe('picking', () => {
  it('maps between protocol and scene coordinates', () => {
    expect(toScene([10, 20, 30], 2)).toEqual([10, 60, -20]);
    expect(toScene([10, 20, 30], 2, 99)).toEqual([10, 99, -20]);
    expect(sceneToWorld({ x: 10, z: -20 })).toEqual([10, 20]);
  });

  it('pick tolerance grows with distance but stays bounded', () => {
    expect(pickRadius(0)).toBe(10);
    expect(pickRadius(4000)).toBe(100);
    expect(pickRadius(1e6)).toBe(400);
  });

  it('a click next to a surface marker selects it; underground and removed ones are skipped', () => {
    expect(nearestSurfaceEntity(map, [110, 100], 30)?.id).toBe('spring');
    expect(nearestSurfaceEntity(map, [390, 95], 30)?.id).toBe('vent');
    expect(nearestSurfaceEntity(map, [250, 100], 30)).toBeNull();
    // the chamber sits right below the spring but is not a surface entity
    expect(nearestSurfaceEntity({ chamber: map.chamber, gone: map.gone }, [100, 100], 30)).toBeNull();
  });

  it('anchors dikes at their middle and points at the ground', () => {
    expect(selectionAnchor({ type: 'entity', id: 'dike' }, map)).toEqual([200, 100, -1750]);
    expect(selectionAnchor({ type: 'point', at: [1, 2] }, map, () => 7)).toEqual([1, 2, 7]);
    expect(selectionAnchor({ type: 'entity', id: 'missing' }, map)).toBeNull();
  });

  it('frames big things from further away', () => {
    expect(frameDistance({ type: 'entity', id: 'chamber' }, map)).toBeGreaterThan(frameDistance({ type: 'entity', id: 'spring' }, map));
  });

  it('cuts sections along dikes and fissures', () => {
    const d = sectionThrough({ type: 'entity', id: 'dike' }, map, 1000)!;
    expect(d[0][1]).toBeCloseTo(100);
    expect(d[1][0] - d[0][0]).toBeCloseTo(1000);
    // strike 90° = towards +Z in the engine = south on the map
    const f = sectionThrough({ type: 'entity', id: 'fissure' }, map, 400)!;
    expect(f[0][0]).toBeCloseTo(0);
    expect(f[1][1]).toBeCloseTo(-200);
    const p = sectionThrough({ type: 'point', at: [5, 5] }, map, 100)!;
    expect(p).toEqual([
      [-45, 5],
      [55, 5],
    ]);
  });
});
