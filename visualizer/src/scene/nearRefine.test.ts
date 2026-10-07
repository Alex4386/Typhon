import { describe, expect, it } from 'vitest';
import { bicubicHeight, catmullRom, refinedHeights } from './nearRefine';

describe('near refinement', () => {
  it('passes through the column values and reproduces planes', () => {
    expect(catmullRom(0, 1, 5, 2, 0)).toBe(1);
    expect(catmullRom(0, 1, 5, 2, 1)).toBe(5);
    const plane = (i: number, j: number) => 3 * i - 2 * j + 7;
    expect(bicubicHeight(plane, 4.25, 9.5)).toBeCloseTo(plane(4.25, 9.5), 9);
  });
  it('is smooth across a bump: no flat facets between columns', () => {
    const bump = (i: number, j: number) => 100 * Math.exp(-((i - 5) ** 2 + (j - 5) ** 2) / 8);
    // between columns the spline follows the curve, not the straight chord
    const chord = (bump(5, 5) + bump(6, 5)) / 2;
    expect(bicubicHeight(bump, 5.5, 5)).toBeGreaterThan(chord);
  });
  it('fills the stencil from present samples where the ground ends', () => {
    const edge = (i: number) => (i > 3 ? Number.NaN : i);
    expect(bicubicHeight((i) => edge(i), 3, 0)).toBe(3);
    const grid = refinedHeights((i, j) => i + j, 0, 0, 2, 4);
    expect(grid.length).toBe((2 * 4 + 3) ** 2);
  });
});
