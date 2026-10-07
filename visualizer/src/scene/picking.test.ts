import { describe, expect, it } from 'vitest';
import type { EntityMap, EntityView } from '../store/entities';
import { frameDistance, nearestDikeOnScreen, nearestSurfaceEntity, pickRadius, sceneToWorld, sectionThrough, segmentDistance, selectionAnchor, toScene } from './picking';

describe('dike picking on screen', () => {
  // a top-down "screen": x px = map x / 10, y px = map y / 10; points below −5000 m count as behind the camera
  const toScreen = (q: readonly number[]): [number, number] | null => (q[2] < -5000 ? null : [q[0] / 10, q[1] / 10]);
  const dikes: EntityMap = {
    dike: view('dike', 'dike', [300, 100, -500], { path: [[100, 100, -3000], [300, 100, -1500], [600, 100, -500]] }),
    gone: view('gone', 'dike', [0, 0, 0], { path: [[0, 0, -100], [1000, 1000, -100]], removedAt: 1 }),
  };

  it('measures the distance to a segment, clamped at its ends', () => {
    expect(segmentDistance([5, 3], [0, 0], [10, 0])).toBeCloseTo(3);
    expect(segmentDistance([-4, 3], [0, 0], [10, 0])).toBeCloseTo(5);
  });

  it('picks a dike within a few pixels of its drawn path, not further away or removed ones', () => {
    expect(nearestDikeOnScreen(dikes, toScreen, [40, 18])?.id).toBe('dike'); // 8 px off the path
    expect(nearestDikeOnScreen(dikes, toScreen, [40, 40])).toBeNull(); // 30 px off
    expect(nearestDikeOnScreen(dikes, toScreen, [50, 50])).toBeNull(); // only the removed dike runs here
  });

  it('picks a pathway between two chambers between them, the chambers at its ends', () => {
    const plumbing: EntityMap = {
      a: view('a', 'chamber', [0, 500, -2000], { props: { radiusM: 300 } }),
      b: view('b', 'chamber', [2000, 500, -4000], { props: { radiusM: 300 } }),
      link: view('link', 'connection', [1000, 500, -3000], { path: [[0, 500, -2000], [2000, 500, -4000]] }),
    };
    expect(nearestDikeOnScreen(plumbing, toScreen, [100, 52])?.id).toBe('link'); // halfway, 2 px off
    expect(nearestDikeOnScreen(plumbing, toScreen, [1, 50])).toBeNull(); // inside chamber a: the chamber's
  });
});

function view(id: string, kind: string, at: [number, number, number], extra: Partial<EntityView> = {}): EntityView {
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
    // at least 30 m: the drawn (smoothed) ground can sit tens of metres off a vent's true position
    expect(pickRadius(0)).toBe(30);
    expect(pickRadius(4000)).toBe(160);
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
