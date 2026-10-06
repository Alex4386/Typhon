import { describe, expect, it } from 'vitest';
import type { EntityView } from '../store/entities';
import { entitiesAnimating, nextDpr } from './frameMath';

describe('adaptive resolution', () => {
  it('steps the pixel ratio down after sustained slow frames, never below 0.6', () => {
    expect(nextDpr(1.5, 1.5, 10, 30, 3, 0)).toBeCloseTo(1.3);
    expect(nextDpr(1.5, 1.5, 10, 30, 2, 0)).toBe(1.5);
    expect(nextDpr(0.7, 1.5, 1, 30, 5, 0)).toBe(0.6);
  });

  it('steps back up with headroom, to the preset maximum', () => {
    expect(nextDpr(1, 1.5, 30, 30, 0, 6)).toBeCloseTo(1.1);
    expect(nextDpr(1.5, 1.5, 30, 30, 0, 10)).toBe(1.5);
    expect(nextDpr(1, 1.5, 30, 30, 0, 3)).toBe(1);
  });

  it('does nothing while idle (no frames asked for)', () => {
    expect(nextDpr(1.2, 1.5, 0, 0, 10, 10)).toBe(1.2);
  });
});

describe('ambient animation of markers', () => {
  const base = { seenAt: 0, fresh: false } as Pick<EntityView, 'seenAt' | 'fresh' | 'removedAt'>;
  it('animates while a marker fades in, pulses as new, or fades out', () => {
    expect(entitiesAnimating([{ ...base, seenAt: 9800 } as EntityView], 10000)).toBe(true);
    expect(entitiesAnimating([{ ...base, fresh: true, seenAt: 5000 } as EntityView], 10000)).toBe(true);
    expect(entitiesAnimating([{ ...base, removedAt: 9000 } as EntityView], 10000)).toBe(true);
  });
  it('is idle once markers settled', () => {
    expect(entitiesAnimating([{ ...base } as EntityView, { ...base, fresh: true, seenAt: 0 } as EntityView], 60000)).toBe(false);
  });
});
