import type * as THREE from 'three';

/** Vertex marks of the water overlay. */
export const POND = 1;
export const SEA = 2;

/**
 * Water triangles: a sea triangle is drawn if ANY vertex is under the sea (all its vertices sit at
 * sea level, so it is a flat piece of the sea surface and the ground's depth test cuts the coastline
 * cleanly); a pond triangle only if all three vertices are wet (ponds have their own heights).
 */
export function compactWater(geo: THREE.BufferGeometry, mark: Uint8Array, n: number): void {
  const index = geo.getIndex()!;
  const idx = index.array as Uint32Array;
  let o = 0;
  const tri = (p: number, q: number, r: number) => {
    const sea = mark[p] === SEA || mark[q] === SEA || mark[r] === SEA;
    const pond = mark[p] === POND && mark[q] === POND && mark[r] === POND;
    if (sea || pond) {
      idx[o++] = p;
      idx[o++] = q;
      idx[o++] = r;
    }
  };
  for (let b = 0; b < n - 1; b++) {
    for (let a = 0; a < n - 1; a++) {
      const v = b * n + a;
      tri(v, v + 1, v + n);
      tri(v + 1, v + n + 1, v + n);
    }
  }
  idx.fill(0, o);
  index.needsUpdate = true;
  geo.setDrawRange(0, o);
}

/**
 * Displayed height of the sea surface over a column: sea level shifted by the same exaggerated
 * deformation as the ground (ground shows at (elev + uplift·(dExag − 1))·vExag), so the displayed
 * water depth stays seaLevel − elev and a submerged flank never shows above the water.
 */
export function seaSurfaceZ(seaLevel: number, uplift: number, dExag: number, vExag: number): number {
  return (seaLevel + uplift * (dExag - 1)) * vExag;
}

/** Displayed ground height of a column (the terrain's own formula). */
export function groundZ(elev: number, uplift: number, dExag: number, vExag: number): number {
  return (elev + uplift * (dExag - 1)) * vExag;
}

