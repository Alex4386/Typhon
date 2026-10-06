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

