/**
 * Terrain beyond the simulated domain ("far field"), so the world does not look like a slab floating
 * in the sky: a coarse, non-interactive ground that continues the domain's edges out to the fog.
 *
 * Heights come from a context-terrain provider when one is registered (coarse real terrain around
 * the domain, served by the sim-server once that API lands), else from extrapolating the domain's
 * edge heights outward, relaxing towards the edges' median level with distance.
 */

/** Coarse terrain around the domain: elevation (m, not exaggerated) at world (x, y), or undefined where unknown. */
export interface ContextTerrain {
  sample(x: number, y: number): number | undefined;
  /** Bumped whenever the data changes (new tiles arrived), so the far field is rebuilt. */
  version: number;
}

let provider: ContextTerrain | null = null;
const listeners = new Set<() => void>();

/** Registers (or clears) the context-terrain source; the far field rebuilds from it. */
export function setContextTerrain(p: ContextTerrain | null): void {
  provider = p;
  for (const l of listeners) l();
}

export function contextTerrain(): ContextTerrain | null {
  return provider;
}

export function onContextTerrain(l: () => void): () => void {
  listeners.add(l);
  return () => listeners.delete(l);
}

/** The rectangle of displayed terrain vertices (cell centres of the outermost cells). */
export interface Domain {
  minX: number;
  minY: number;
  maxX: number;
  maxY: number;
}

/** Distance (m) from (x, y) to the domain rectangle (0 inside). */
export function distanceOutside(d: Domain, x: number, y: number): number {
  const dx = Math.max(d.minX - x, 0, x - d.maxX);
  const dy = Math.max(d.minY - y, 0, y - d.maxY);
  return Math.hypot(dx, dy);
}

function smoothstep(e0: number, e1: number, x: number): number {
  const t = Math.min(1, Math.max(0, (x - e0) / (e1 - e0)));
  return t * t * (3 - 2 * t);
}

/** Smooth value noise in [−1, 1] (no allocation), for low hills that break up a flat far field. */
export function valueNoise(x: number, y: number): number {
  const xi = Math.floor(x);
  const yi = Math.floor(y);
  const fx = x - xi;
  const fy = y - yi;
  const h = (i: number, j: number) => {
    const s = Math.sin(i * 127.1 + j * 311.7) * 43758.5453;
    return (s - Math.floor(s)) * 2 - 1;
  };
  const ux = fx * fx * (3 - 2 * fx);
  const uy = fy * fy * (3 - 2 * fy);
  const a = h(xi, yi) + (h(xi + 1, yi) - h(xi, yi)) * ux;
  const b = h(xi, yi + 1) + (h(xi + 1, yi + 1) - h(xi, yi + 1)) * ux;
  return a + (b - a) * uy;
}

export interface Extrapolation {
  /** Domain-edge elevation (m) at the edge point nearest to (x, y). */
  edge: (x: number, y: number) => number;
  /** Level the far field relaxes to (m): the median of the edge heights. */
  base: number;
  /** Distance over which edge heights relax to the base (m). */
  falloff: number;
  /**
   * Highest an extrapolated height may reach (m): just under sea level for a sea world whose edge
   * lies under water, so guessed far terrain never turns open ocean into land; +∞ otherwise.
   * Server context terrain is not capped.
   */
  ceiling?: number;
  /** Amplitude of gentle far hills (m). */
  hills: number;
  /** Horizontal scale of the hills (m). */
  hillScale: number;
}

/**
 * Elevation (m) of the far field at world (x, y) outside the domain: context terrain where known,
 * blended in over the first `falloff` metres so the seam with the domain stays closed; else the
 * extrapolated edge.
 */
export function farFieldElevation(d: Domain, x: number, y: number, ex: Extrapolation, ctx: ContextTerrain | null): number {
  const cx = Math.min(d.maxX, Math.max(d.minX, x));
  const cy = Math.min(d.maxY, Math.max(d.minY, y));
  const dist = distanceOutside(d, x, y);
  const edge = ex.edge(cx, cy);
  const k = smoothstep(0, ex.falloff, dist);
  const known = ctx?.sample(x, y);
  if (known !== undefined && Number.isFinite(known)) return edge + (known - edge) * smoothstep(0, ex.falloff * 0.25, dist);
  const hills = ex.hills * valueNoise(x / ex.hillScale, y / ex.hillScale) * k;
  return Math.min(ex.ceiling ?? Infinity, edge + (ex.base - edge) * k + hills);
}

/** Median of a list (0 for none). */
export function median(values: number[]): number {
  if (values.length === 0) return 0;
  const s = [...values].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

/**
 * Grid coordinate for u ∈ [−1, 1] along one axis: the inner quarter spans the domain (half-size
 * `half` around `centre`), the rest stretches quadratically out to `radius`, so cells are small
 * next to the domain and large near the horizon.
 */
export function stretch(u: number, centre: number, half: number, radius: number): number {
  const a = Math.abs(u);
  const s = Math.sign(u);
  if (a <= 0.25) return centre + s * (a / 0.25) * half;
  const t = (a - 0.25) / 0.75;
  return centre + s * (half + t * t * (radius - half));
}

/** A regular grid of cells (one pyramid level): value of cell (i, j), or undefined where not loaded. */
export interface LevelGrid {
  origin: [number, number];
  cellSize: number;
  cell(i: number, j: number): number | undefined;
}

/**
 * Bilinear sample between the cell centres of a level grid at world (x, y); undefined unless all
 * four surrounding cells are loaded.
 */
export function sampleLevel(g: LevelGrid, x: number, y: number): number | undefined {
  const fi = (x - g.origin[0]) / g.cellSize - 0.5;
  const fj = (y - g.origin[1]) / g.cellSize - 0.5;
  const i = Math.floor(fi);
  const j = Math.floor(fj);
  const a = g.cell(i, j);
  const b = g.cell(i + 1, j);
  const c = g.cell(i, j + 1);
  const d = g.cell(i + 1, j + 1);
  if (a === undefined || b === undefined || c === undefined || d === undefined) return undefined;
  const u = fi - i;
  const v = fj - j;
  return (a * (1 - u) + b * u) * (1 - v) + (c * (1 - u) + d * u) * v;
}

/** Context terrain from a stack of level grids, finest first: the finest level covering a point wins. */
export function stackedContext(levels: LevelGrid[], version: () => number): ContextTerrain {
  return {
    sample(x, y) {
      for (const g of levels) {
        const v = sampleLevel(g, x, y);
        if (v !== undefined) return v;
      }
      return undefined;
    },
    get version() {
      return version();
    },
  };
}
