/**
 * Height on a (t+1)×(t+1) vertex grid at fractional vertex coordinates (a, b) ∈ [0, t]², matching
 * how the terrain mesh splits each quad along the (a+1, b)–(a, b+1) diagonal.
 */
export function interpolateGrid(z: ArrayLike<number>, t: number, a: number, b: number): number {
  const n = t + 1;
  const ca = Math.min(t, Math.max(0, a));
  const cb = Math.min(t, Math.max(0, b));
  const a0 = Math.min(t - 1, Math.floor(ca));
  const b0 = Math.min(t - 1, Math.floor(cb));
  const u = ca - a0;
  const v = cb - b0;
  const z00 = z[b0 * n + a0];
  const z10 = z[b0 * n + a0 + 1];
  const z01 = z[(b0 + 1) * n + a0];
  const z11 = z[(b0 + 1) * n + a0 + 1];
  return u + v <= 1 ? z00 + (z10 - z00) * u + (z01 - z00) * v : z11 + (z01 - z11) * (1 - u) + (z10 - z11) * (1 - v);
}
