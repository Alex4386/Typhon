import { describe, expect, it } from 'vitest';
import { DEFAULT_QUAKE_FILTER, filterQuakes, quakeFade, sanitize, type QuakeFilter } from './quakeFilter';

const quakes = Array.from({ length: 200 }, (_, k) => ({ id: k, time: k * 10, magnitude: (k % 5) * 0.5 + 0.5 }));
const f = (o: Partial<QuakeFilter>): QuakeFilter => ({ ...DEFAULT_QUAKE_FILTER, ...o });

describe('earthquake filter', () => {
  it('defaults to the last 50 as of now', () => {
    const out = filterQuakes(quakes, 1990, DEFAULT_QUAKE_FILTER);
    expect(DEFAULT_QUAKE_FILTER.count).toBe(50);
    expect(out).toHaveLength(50);
    expect(out[out.length - 1].id).toBe(199);
    expect(out[0].id).toBe(150);
  });

  it('never shows quakes after the current time (replay cursor moved back)', () => {
    const out = filterQuakes(quakes, 500, f({ count: 10 }));
    expect(out.map((q) => q.id)).toEqual([41, 42, 43, 44, 45, 46, 47, 48, 49, 50]);
    expect(out.every((q) => q.time <= 500)).toBe(true);
  });

  it('applies the optional time window and minimum magnitude', () => {
    expect(filterQuakes(quakes, 1990, f({ count: 1000, windowS: 95 })).map((q) => q.id)).toEqual([190, 191, 192, 193, 194, 195, 196, 197, 198, 199]);
    const big = filterQuakes(quakes, 1990, f({ count: 5, minMagnitude: 2.5 }));
    expect(big).toHaveLength(5);
    expect(big.every((q) => q.magnitude >= 2.5)).toBe(true);
    expect(big[4].id).toBe(199);
  });

  it('shows nothing with a count of 0, and copes with no quakes', () => {
    expect(filterQuakes(quakes, 1990, f({ count: 0 }))).toEqual([]);
    expect(filterQuakes([], 1990, DEFAULT_QUAKE_FILTER)).toEqual([]);
  });

  it('fades by rank, or by age with a window; not at all by default', () => {
    expect(quakeFade(DEFAULT_QUAKE_FILTER, 1000, 0, 50)).toBe(1);
    expect(quakeFade(f({ fade: true }), 0, 49, 50)).toBe(1);
    expect(quakeFade(f({ fade: true }), 0, 0, 50)).toBeCloseTo(0.25);
    expect(quakeFade(f({ fade: true, windowS: 100 }), 50, 0, 50)).toBeCloseTo(0.5);
  });

  it('sanitizes stored settings', () => {
    expect(sanitize({ count: -3, windowS: 0, minMagnitude: 'x', fade: 1 })).toEqual({ count: 0, windowS: null, minMagnitude: null, fade: false });
    expect(sanitize(null)).toEqual(DEFAULT_QUAKE_FILTER);
    expect(sanitize({ count: 1e9 }).count).toBe(2000);
  });
});
