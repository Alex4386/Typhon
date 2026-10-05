import { describe, expect, it } from 'vitest';
import { interpolateGrid } from './grid';

describe('interpolateGrid', () => {
  // 2×2 cells, 3×3 vertices, z = 10·a + 100·b (planar: every triangulation interpolates it exactly)
  const t = 2;
  const z = new Float32Array(9);
  for (let b = 0; b <= t; b++) for (let a = 0; a <= t; a++) z[b * 3 + a] = 10 * a + 100 * b;

  it('hits vertices exactly and is exact on a plane', () => {
    expect(interpolateGrid(z, t, 0, 0)).toBeCloseTo(0);
    expect(interpolateGrid(z, t, 2, 2)).toBeCloseTo(220);
    expect(interpolateGrid(z, t, 1.25, 0.5)).toBeCloseTo(62.5);
    expect(interpolateGrid(z, t, 0.9, 0.8)).toBeCloseTo(89);
  });

  it('clamps outside the tile', () => {
    expect(interpolateGrid(z, t, -3, 0)).toBeCloseTo(0);
    expect(interpolateGrid(z, t, 5, 5)).toBeCloseTo(220);
  });

  it('follows the mesh diagonal on a non-planar quad', () => {
    // one quad, only the (1,1) corner raised: the lower-left triangle is flat
    const q = new Float32Array([0, 0, 0, 10]);
    expect(interpolateGrid(q, 1, 0.3, 0.3)).toBeCloseTo(0);
    expect(interpolateGrid(q, 1, 0.8, 0.8)).toBeCloseTo(6);
    expect(interpolateGrid(q, 1, 1, 1)).toBeCloseTo(10);
  });
});
