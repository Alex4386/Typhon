/** Reader for vertex (a, b) of a tile grid. */
export type Reader = (a: number, b: number) => number;

/**
 * The vertical step of block-quantised elevations (m): the most common smallest non-zero difference
 * between neighbouring columns, 0 when the data is not stepped (or too few samples).
 */
export function elevationQuantum(values: Float32Array | undefined): number {
  if (!values || values.length < 4) return 0;
  const counts = new Map<number, number>();
  let n = 0;
  for (let k = 1; k < values.length; k++) {
    const d = Math.abs(values[k] - values[k - 1]);
    if (d < 1e-3 || d > 50) continue;
    const key = Math.round(d * 1000) / 1000;
    counts.set(key, (counts.get(key) ?? 0) + 1);
    n++;
  }
  if (n < 8) return 0;
  // the smallest difference that is common (a stepped field has most steps of exactly one quantum)
  const common = [...counts.entries()].filter(([, c]) => c >= n * 0.05).map(([d]) => d);
  if (common.length === 0) return 0;
  return Math.min(...common);
}

/**
 * Samples `r` once over vertices (a, b) ∈ [−1, n]² into an array and reads from it (clamped to that
 * range): a chain of reader closures evaluated a few times per vertex becomes one array lookup.
 */
export function bakedReader(r: Reader, n: number): { grid: Float32Array; read: Reader } {
  const m = n + 2;
  const grid = new Float32Array(m * m);
  for (let b = 0; b < m; b++) for (let a = 0; a < m; a++) grid[b * m + a] = r(a - 1, b - 1);
  return { grid, read: gridReader(grid, n) };
}

/** Reader over a grid baked by {@link bakedReader}. */
export function gridReader(grid: Float32Array, n: number): Reader {
  const m = n + 2;
  return (a, b) => {
    const ca = a < -1 ? 0 : a > n ? m - 1 : a + 1;
    const cb = b < -1 ? 0 : b > n ? m - 1 : b + 1;
    return grid[cb * m + ca];
  };
}

/** `smooth`, but never further than `q` from `raw` (edge-preserving: features larger than a step survive). */
export function clampedReader(raw: Reader, smooth: Reader, q: number): Reader {
  return (a, b) => {
    const r = raw(a, b);
    const s = smooth(a, b);
    return s > r + q ? r + q : s < r - q ? r - q : s;
  };
}

/**
 * Map point (world x, y) the camera looks at: where its view ray meets the horizontal plane at scene
 * height `planeY`; straight below the camera when it looks up or level.
 */
export function viewFocus(pos: { x: number; y: number; z: number }, dir: { x: number; y: number; z: number }, planeY: number): [number, number] {
  if (dir.y < -1e-3) {
    const t = (planeY - pos.y) / dir.y;
    if (t > 0) return [pos.x + t * dir.x, -(pos.z + t * dir.z)];
  }
  return [pos.x, -pos.z];
}

/** Keys of pending rebuilds, nearest to `focus` first (entries without a place first: they are cheap detail updates). */
export function rebuildOrder(queue: ReadonlyMap<string, { at?: [number, number] }>, focus: [number, number]): string[] {
  const d = (at?: [number, number]) => (at ? (at[0] - focus[0]) ** 2 + (at[1] - focus[1]) ** 2 : -1);
  return [...queue.entries()].sort((a, b) => d(a[1].at) - d(b[1].at)).map(([k]) => k);
}

/**
 * Where a view ray meets the ground (scene coordinates, y up), by marching from a plane below all
 * terrain: `groundY(x, z)` is the displayed ground height at scene point (x, z). Used to pick the map
 * before (or without) the tile meshes: a few fixed-point steps between the ray and the ground height.
 */
export function rayGround(
  origin: { x: number; y: number; z: number },
  dir: { x: number; y: number; z: number },
  groundY: (x: number, z: number) => number,
  startY: number,
): [number, number, number] | null {
  if (!(dir.y < -1e-6)) return null;
  let y = startY;
  let p: [number, number, number] = [origin.x, y, origin.z];
  for (let k = 0; k < 8; k++) {
    const t = (y - origin.y) / dir.y;
    if (t < 0) return null;
    p = [origin.x + t * dir.x, y, origin.z + t * dir.z];
    const g = groundY(p[0], p[2]);
    if (!Number.isFinite(g) || Math.abs(g - y) < 0.01) break;
    y = g;
  }
  return p;
}
