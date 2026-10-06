import { describe, expect, it } from 'vitest';
import { distanceOutside, farFieldElevation, median, stretch, type Extrapolation } from './farField';
import { clampedReader, elevationQuantum } from './terrainMath';

describe('display smoothing', () => {
  it('finds the block step of stepped elevations, none for continuous ones', () => {
    const stepped = new Float32Array(64 * 64).map((_, k) => Math.floor((k % 64) / 5) + Math.floor(k / 64 / 7));
    expect(elevationQuantum(stepped)).toBe(1);
    const smooth = new Float32Array(64 * 64).map((_, k) => Math.sin(k * 0.37) * 3.1 + k * 0.0137);
    expect(elevationQuantum(smooth)).toBe(0);
    expect(elevationQuantum(undefined)).toBe(0);
  });

  it('keeps a crater: smoothing never moves a vertex more than one step', () => {
    // a 40 m deep crater floor next to its rim
    const raw = (a: number) => (a < 5 ? 100 : 60);
    const blurred = (a: number) => (a < 3 ? 100 : a > 7 ? 60 : 100 - ((a - 3) / 4) * 40);
    const shown = clampedReader(raw, blurred, 1);
    expect(shown(4, 0)).toBe(99);
    expect(shown(5, 0)).toBe(61);
    expect(shown(0, 0)).toBe(100);
  });
});

describe('far field', () => {
  const d = { minX: 0, minY: 0, maxX: 1000, maxY: 1000 };
  const ex: Extrapolation = { edge: (x) => 100 + x * 0.1, base: 50, falloff: 2000, hills: 0, hillScale: 500 };

  it('continues the edge at the seam and relaxes to the base far away', () => {
    expect(farFieldElevation(d, 1000, 500, ex, null)).toBeCloseTo(200);
    expect(farFieldElevation(d, -1, 500, ex, null)).toBeCloseTo(100, 0);
    expect(farFieldElevation(d, 5000, 500, ex, null)).toBeCloseTo(50);
    expect(distanceOutside(d, 500, 500)).toBe(0);
    expect(distanceOutside(d, 1300, 1400)).toBeCloseTo(500);
  });

  it('uses context terrain where known, blended in from the edge', () => {
    const ctx = { version: 1, sample: (x: number) => (x > 2000 ? 777 : undefined) };
    expect(farFieldElevation(d, 3000, 500, ex, ctx)).toBeCloseTo(777);
    expect(farFieldElevation(d, 1500, 500, ex, ctx)).toBeLessThan(200);
  });

  it('stretches the grid: domain in the inner quarter, out to the radius', () => {
    expect(stretch(0.25, 500, 500, 20000)).toBe(1000);
    expect(stretch(-1, 500, 500, 20000)).toBe(-19500);
    expect(stretch(1, 500, 500, 20000)).toBe(20500);
    expect(stretch(0, 500, 500, 20000)).toBe(500);
    expect(median([3, 1, 2])).toBe(2);
  });
});
