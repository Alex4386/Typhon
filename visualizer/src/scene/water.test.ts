import * as THREE from 'three';
import { describe, expect, it } from 'vitest';
import { compactWater, groundZ, seaSurfaceZ } from './waterIndex';

/** A t×t-cell grid indexed like the terrain overlays (two triangles per cell). */
function grid(t: number): THREE.BufferGeometry {
  const n = t + 1;
  const g = new THREE.BufferGeometry();
  const idx = new Uint32Array(t * t * 6);
  let o = 0;
  for (let b = 0; b < t; b++) {
    for (let a = 0; a < t; a++) {
      const v = b * n + a;
      idx.set([v, v + 1, v + n, v + 1, v + n + 1, v + n], o);
      o += 6;
    }
  }
  g.setIndex(new THREE.BufferAttribute(idx, 1));
  return g;
}

/** Number of triangles the water overlay draws for a marking. */
function drawn(t: number, mark: number[]): number {
  const geo = grid(t);
  compactWater(geo, Uint8Array.from(mark), t + 1);
  return geo.drawRange.count / 3;
}

describe('water overlay triangles', () => {
  it('draws a sea triangle when any vertex is under the sea (no holes along the coast)', () => {
    // 2×2 cells; only the centre vertex is sea: all 6 triangles touching it are drawn
    expect(drawn(2, [0, 0, 0, 0, 2, 0, 0, 0, 0])).toBe(6);
  });

  it('keeps submerged ground under the displayed sea with exaggerated uplift', () => {
    // 12.4 m of real uplift, shown ×20 deformation and ×2 vertical: a flank 3 m under the sea
    // must still display under the water surface, 3 m × vExag below it
    for (const [elev, up] of [
      [-3, 12.4],
      [-0.5, 12.4],
      [-40, -2],
    ] as const) {
      const ground = groundZ(elev, up, 20, 2);
      const sea = seaSurfaceZ(0, up, 20, 2);
      expect(sea).toBeGreaterThan(ground);
      expect(sea - ground).toBeCloseTo(-elev * 2);
    }
  });

  it('draws a pond triangle only when all three vertices are pond', () => {
    expect(drawn(1, [1, 1, 1, 0])).toBe(1);
    expect(drawn(1, [1, 0, 1, 0])).toBe(0);
  });
});
