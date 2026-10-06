import { describe, expect, it } from 'vitest';
import type { WorldInfo } from '../protocol/messages';
import { detailCovers, detailLevels, detailMismatch, levelRect, overlappingDetailTiles, refinement, wantsDetail } from './detail';

// Kīlauea-like: 20 m columns in 64-column tiles, crater detail at level −2 (5 m) over tiles 12…19
const world = {
  origin: [-5120, -5120],
  cellSize: 20,
  tileSize: 64,
  tiles: { minTx: 0, minTy: 0, maxTx: 7, maxTy: 7 },
  lod: {
    extent: [-15000, -15000, 15000, 15000],
    levels: [
      { level: -2, cellSize: 5, tiles: { minTx: 12, minTy: 12, maxTx: 19, maxTy: 19 }, fields: [1], kind: 'detail' },
      { level: 1, cellSize: 40, tiles: { minTx: -2, minTy: -2, maxTx: 5, maxTy: 5 }, fields: [1], kind: 'context' },
    ],
  },
} as unknown as WorldInfo;

describe('crater detail levels', () => {
  const levels = detailLevels(world);

  it('finds the detail levels and their refinement and extent', () => {
    expect(levels.map((l) => l.level)).toEqual([-2]);
    expect(refinement(world, levels[0])).toBe(4);
    expect(levelRect(world, levels[0])).toEqual({ minX: -1280, minY: -1280, maxX: 1280, maxY: 1280 });
  });

  it('covers a column only where its detail tile is loaded', () => {
    const loaded = new Set(['-2:12,12']);
    const has = (l: number, tx: number, ty: number) => loaded.has(`${l}:${tx},${ty}`);
    // column 192 → detail cells 768…771 → detail tile 12
    expect(detailCovers(world, levels, has, 192, 192)).toBe(true);
    expect(detailCovers(world, levels, has, 208, 192)).toBe(false); // tile 13: not loaded
    expect(detailCovers(world, levels, has, 100, 100)).toBe(false); // outside the region
  });

  it('lists the detail tiles over a core tile', () => {
    expect(overlappingDetailTiles(world, levels, 3, 3)).toEqual([
      [-2, 12, 12], [-2, 13, 12], [-2, 14, 12], [-2, 15, 12],
      [-2, 12, 13], [-2, 13, 13], [-2, 14, 13], [-2, 15, 13],
      [-2, 12, 14], [-2, 13, 14], [-2, 14, 14], [-2, 15, 14],
      [-2, 12, 15], [-2, 13, 15], [-2, 14, 15], [-2, 15, 15],
    ]);
    expect(overlappingDetailTiles(world, levels, 0, 0)).toEqual([]);
  });

  it('wants detail near the crater, with hysteresis', () => {
    const rect = levelRect(world, levels[0]);
    expect(wantsDetail(rect, 0, 0, 500, 1)).toBe(true);
    expect(wantsDetail(rect, 0, 0, 20000, 1)).toBe(false); // high above
    expect(wantsDetail(rect, 6000, 0, 500, 1)).toBe(false); // 4.7 km off the region, reach 3.84 km
    expect(wantsDetail(rect, 6000, 0, 500, 1.3)).toBe(true);
  });

  it('measures how far a detail tile is from the columns it refines', () => {
    const t = 8;
    const r = 4;
    // two columns per row; each column's 4×4 cells average to 100 + column index, with a crater dip inside
    const values = new Float32Array(t * t).map((_, k) => {
      const a = k % t;
      const b = Math.floor(k / t);
      const col = Math.floor(a / r) + 2 * Math.floor(b / r);
      return 100 + col + ((a % r) + (b % r) * r === 5 ? -8 : (a % r) + (b % r) * r === 6 ? 8 : 0);
    });
    const core = (i: number, j: number) => 100 + (i - 0) + 2 * j;
    expect(detailMismatch(values, t, r, 0, 0, core)).toBeCloseTo(0);
    expect(detailMismatch(values, t, r, 0, 0, (i, j) => core(i, j) + 40)).toBeCloseTo(40);
    expect(detailMismatch(values, t, r, 0, 0, () => undefined)).toBeNaN();
  });
});
