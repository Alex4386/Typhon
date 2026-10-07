/**
 * Refinement of a core terrain tile for the ground near the camera: the tile's drawn vertex heights
 * (one per column centre) are interpolated with a Catmull-Rom spline — a smooth surface through every
 * column value, not a mesh of flat 10 m facets — and its vertex colours bilinearly, at `k` vertices per
 * column. The data stays the columns'; only what is drawn between them is smooth.
 */

/** Height (drawn, exaggerated) at global column-centre vertex (i, j), or NaN where nothing is loaded. */
export type HeightAt = (i: number, j: number) => number;

/** Catmull-Rom weights at u ∈ [0, 1] between p1 and p2 (p0, p1, p2, p3). */
export function catmullRom(p0: number, p1: number, p2: number, p3: number, u: number): number {
  const u2 = u * u;
  const u3 = u2 * u;
  return 0.5 * (2 * p1 + (p2 - p0) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u2 + (3 * p1 - p0 + p3 - 3 * p2) * u3);
}

/**
 * Bicubic height at fractional column coordinates (fi, fj): missing samples (NaN, past the loaded
 * ground) repeat the nearest present one along the row, so the surface never reaches into gaps.
 */
export function bicubicHeight(h: HeightAt, fi: number, fj: number): number {
  const i = Math.floor(fi);
  const j = Math.floor(fj);
  const u = fi - i;
  const v = fj - j;
  const rows: number[] = [];
  for (let dj = -1; dj <= 2; dj++) {
    const p = [h(i - 1, j + dj), h(i, j + dj), h(i + 1, j + dj), h(i + 2, j + dj)];
    fill(p);
    rows.push(Number.isNaN(p[1]) ? Number.NaN : catmullRom(p[0], p[1], p[2], p[3], u));
  }
  fill(rows);
  return Number.isNaN(rows[1]) ? Number.NaN : catmullRom(rows[0], rows[1], rows[2], rows[3], v);
}

/** Replaces NaN entries of a 4-sample stencil by their nearest present neighbour (in place). */
function fill(p: number[]): void {
  if (Number.isNaN(p[1])) p[1] = p[2];
  if (Number.isNaN(p[2])) p[2] = p[1];
  if (Number.isNaN(p[0])) p[0] = p[1];
  if (Number.isNaN(p[3])) p[3] = p[2];
}

/**
 * The refined grid of tile (tx, ty) of `t` columns: `(t·k + 1)²` heights, plus one ring around for
 * normals (row-major, `(t·k + 3)²`, index `(b + 1)·(m + 2) + (a + 1)` for refined vertex (a, b) with
 * m = t·k + 1).
 */
export function refinedHeights(h: HeightAt, tx: number, ty: number, t: number, k: number): Float32Array {
  const m = t * k + 1;
  const w = m + 2;
  const out = new Float32Array(w * w);
  for (let b = -1; b <= m; b++) {
    for (let a = -1; a <= m; a++) {
      out[(b + 1) * w + (a + 1)] = bicubicHeight(h, tx * t + a / k, ty * t + b / k);
    }
  }
  return out;
}
