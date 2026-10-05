import { describe, expect, it } from 'vitest';
import {
  anglesOf,
  angleDelta,
  applyClearance,
  clipPlanes,
  compassPoint,
  decodePose,
  directionOf,
  encodePose,
  flySpeed,
  frameDistance,
  MAX_PITCH,
  orbitPose,
  parseBookmarks,
  serializeBookmarks,
  stepAllowed,
  type Bookmark,
  type CameraPose,
} from './math';

describe('directions', () => {
  it('heading 0 looks north (−z), π/2 east (+x)', () => {
    const d: number[] = [];
    directionOf(0, 0, d);
    expect(d[0]).toBeCloseTo(0);
    expect(d[2]).toBeCloseTo(-1);
    directionOf(Math.PI / 2, 0, d);
    expect(d[0]).toBeCloseTo(1);
    expect(d[2]).toBeCloseTo(0);
  });

  it('angles round-trip through a direction', () => {
    const d: number[] = [];
    const a: number[] = [];
    for (const [h, p] of [[0.3, 0.2], [2.5, -0.7], [5.9, 1.2]]) {
      directionOf(h, p, d);
      anglesOf(d[0], d[1], d[2], a);
      expect(a[0]).toBeCloseTo(h);
      expect(a[1]).toBeCloseTo(p);
    }
  });

  it('angle deltas take the short way round', () => {
    expect(angleDelta(0.1, 2 * Math.PI - 0.1)).toBeCloseTo(-0.2);
    expect(angleDelta(2 * Math.PI - 0.1, 0.1)).toBeCloseTo(0.2);
  });

  it('compass points', () => {
    expect(compassPoint(0)).toBe('N');
    expect(compassPoint(Math.PI)).toBe('S');
    expect(compassPoint((3 * Math.PI) / 2)).toBe('W');
    expect(compassPoint(Math.PI / 4)).toBe('NE');
  });
});

describe('speed and clearance', () => {
  it('fly speed grows with height above ground and is clamped near the ground', () => {
    expect(flySpeed(100, 1, false, false)).toBeGreaterThan(flySpeed(10, 1, false, false));
    expect(flySpeed(10000, 1, false, false)).toBeGreaterThan(flySpeed(100, 1, false, false) * 50);
    expect(flySpeed(0, 1, false, false)).toBe(flySpeed(5, 1, false, false));
    expect(flySpeed(-50, 1, false, false)).toBe(flySpeed(50, 1, false, false)); // underground: by depth
  });

  it('boost and slow modifiers', () => {
    const base = flySpeed(200, 1, false, false);
    expect(flySpeed(200, 1, true, false)).toBeCloseTo(base * 4);
    expect(flySpeed(200, 1, false, true)).toBeCloseTo(base * 0.2);
    expect(flySpeed(200, 2, false, false)).toBeCloseTo(base * 2);
  });

  it('clearance keeps the camera above the ground unless underground is allowed', () => {
    expect(applyClearance(100, 120, 5, false)).toBe(125);
    expect(applyClearance(200, 120, 5, false)).toBe(200);
    expect(applyClearance(100, 120, 5, true)).toBe(100);
  });

  it('slope limit blocks steep climbs but not descents', () => {
    const limit = (40 * Math.PI) / 180;
    expect(stepAllowed(0, 0.5, 1, limit)).toBe(true);
    expect(stepAllowed(0, 2, 1, limit)).toBe(false);
    expect(stepAllowed(10, 0, 1, limit)).toBe(true);
  });

  it('clip planes: small near plane at the ground, far plane covers the world', () => {
    const c: number[] = [];
    clipPlanes(2, 50, 10000, c);
    expect(c[0]).toBeLessThan(1);
    expect(c[1]).toBeGreaterThanOrEqual(60000);
    clipPlanes(20000, 30000, 10000, c);
    expect(c[0]).toBeGreaterThan(10);
    expect(c[1]).toBeGreaterThan(20000 * 30);
  });

  it('frame distance fits a 20 km column in a 38° view', () => {
    const d = frameDistance(10000, 38, 16 / 9);
    // sphere of radius 10 km subtends ≤ 38° vertically from d
    expect(Math.asin(10000 / d) * 2 * (180 / Math.PI)).toBeLessThanOrEqual(38);
    expect(frameDistance(10000, 38, 0.5)).toBeGreaterThan(d); // narrow viewport needs more distance
  });
});

describe('poses and bookmarks', () => {
  const pose: CameraPose = { mode: 'orbit', position: [1234.56, 789.01, -4567.89], heading: 3.14159, pitch: -0.5, target: [10, 20, 30] };

  it('pose URL round trip (rounded to 0.1 m / 1e-4 rad)', () => {
    const back = decodePose(encodePose(pose))!;
    expect(back.mode).toBe('orbit');
    expect(back.position[0]).toBeCloseTo(1234.6, 5);
    expect(back.position[2]).toBeCloseTo(-4567.9, 5);
    expect(back.heading).toBeCloseTo(3.1416, 4);
    expect(back.target).toEqual([10, 20, 30]);
    const fly = decodePose(encodePose({ ...pose, mode: 'fly', target: undefined }))!;
    expect(fly.mode).toBe('fly');
    expect(fly.target).toBeUndefined();
  });

  it('malformed URLs are rejected', () => {
    expect(decodePose('')).toBeNull();
    expect(decodePose('orbit,1,2')).toBeNull();
    expect(decodePose('teleport,1,2,3,4,5')).toBeNull();
    expect(decodePose('fly,1,2,x,4,5')).toBeNull();
    expect(decodePose('fly,1,2,3,0,9')!.pitch).toBeCloseTo(MAX_PITCH); // pitch clamped
  });

  it('bookmarks serialise without built-ins and survive garbage', () => {
    const list: Bookmark[] = [
      { name: 'rim', pose },
      { name: 'summit', pose, builtin: true },
    ];
    const back = parseBookmarks(serializeBookmarks(list));
    expect(back).toHaveLength(1);
    expect(back[0].name).toBe('rim');
    expect(back[0].pose.target).toEqual([10, 20, 30]);
    expect(parseBookmarks('not json')).toEqual([]);
    expect(parseBookmarks('[{"name":"x","pose":"nope"},{"name":3}]')).toEqual([]);
  });

  it('orbit pose looks at its target', () => {
    const p = orbitPose([0, 100, 0], 1000, Math.PI / 2, 0.5);
    const d: number[] = [];
    directionOf(p.heading, p.pitch, d);
    const tx = p.position[0] + d[0] * 1000;
    const ty = p.position[1] + d[1] * 1000;
    const tz = p.position[2] + d[2] * 1000;
    expect(tx).toBeCloseTo(0, 6);
    expect(ty).toBeCloseTo(100, 6);
    expect(tz).toBeCloseTo(0, 6);
    expect(p.position[1]).toBeGreaterThan(100); // elevated
  });
});
