import type { RGB } from '../util/color';

/**
 * Natural land colour from terrain shape: what a volcanic island looks like from the air, rather than
 * a hypsometric tint. Vegetation covers the low, gentle ground and thins with height and slope;
 * the upper cone is bare scoria, oxidised red-brown near the summit; cliffs and steep walls show
 * grey rock; a strip of sand lines gentle shores. Gullies (hollows) hold moisture and shade, ridges
 * dry out. Multi-scale value noise breaks the bands into patches, as vegetation and soils do.
 * Everything is per vertex on the CPU (no custom shader: WebGPU and WebGL2 alike). Colours sRGB 0..1.
 */

/** Shape of the ground at a vertex. */
export interface GroundShape {
  /** World position (m), for the noise. */
  x: number;
  y: number;
  /** Height above sea level (m; above the lowest ground in a world without sea). */
  above: number;
  /** Height as a share of the land's relief, 0 at the shore … 1 at the highest summit. */
  t: number;
  /** Gradient magnitude (rise over run, unexaggerated). */
  slope: number;
  /**
   * Hollowness: the mean of the four neighbours minus the vertex height, per metre of spacing, scaled
   * to a 20 m grid (> 0 in gullies and hollows, < 0 on ridges and rims).
   */
  hollow: number;
}

const SAND: RGB = [0.64, 0.59, 0.47];
const FOREST: RGB = [0.2, 0.28, 0.14];
const GRASS: RGB = [0.4, 0.44, 0.25];
const DRY: RGB = [0.5, 0.46, 0.35];
const SCORIA: RGB = [0.27, 0.25, 0.23];
const OXIDISED: RGB = [0.41, 0.3, 0.24];
const ROCK: RGB = [0.4, 0.38, 0.36];

function smoothstep(e0: number, e1: number, x: number): number {
  const u = Math.min(1, Math.max(0, (x - e0) / (e1 - e0)));
  return u * u * (3 - 2 * u);
}

function mix(c: RGB, d: RGB, k: number): void {
  c[0] += (d[0] - c[0]) * k;
  c[1] += (d[1] - c[1]) * k;
  c[2] += (d[2] - c[2]) * k;
}

function lattice(i: number, j: number): number {
  let h = Math.imul(i, 374761393) + Math.imul(j, 668265263);
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

/** Smooth value noise in 0..1 with features about `scale` metres across. */
export function valueNoise(x: number, y: number, scale: number): number {
  const fx = x / scale;
  const fy = y / scale;
  const i = Math.floor(fx);
  const j = Math.floor(fy);
  const u = fx - i;
  const v = fy - j;
  const su = u * u * (3 - 2 * u);
  const sv = v * v * (3 - 2 * v);
  const a = lattice(i, j);
  const b = lattice(i + 1, j);
  const c = lattice(i, j + 1);
  const d = lattice(i + 1, j + 1);
  return a + (b - a) * su + (c - a) * sv + (a - b - c + d) * su * sv;
}

/**
 * Natural colour of land with shape `s` into `out` (sRGB 0..1), before `groundShade`. `fine` = false
 * leaves out the small-scale patches, for coarse meshes whose vertices are farther apart than they
 * (the far field: sampled sparsely they alias into streaks).
 */
export function naturalLand(s: GroundShape, out: RGB, fine = true): RGB {
  const broad = valueNoise(s.x + 7100, s.y - 3300, 700);
  const patch = fine ? valueNoise(s.x - 1900, s.y + 5200, 160) * 0.65 + valueNoise(s.x, s.y, 55) * 0.35 : 0.5;
  const hollow = Math.max(-1, Math.min(1, s.hollow * 6));

  // bare ground: dry soil low down, dark scoria up the cone, oxidised cinders at the top in patches
  out[0] = DRY[0];
  out[1] = DRY[1];
  out[2] = DRY[2];
  mix(out, SCORIA, smoothstep(0.12, 0.55, s.t + (broad - 0.5) * 0.2));
  mix(out, OXIDISED, smoothstep(0.7, 0.95, s.t) * smoothstep(0.35, 0.75, broad) * 0.8);
  // steep walls (> ~30°) are rock outcrop, lighter grey
  mix(out, ROCK, smoothstep(0.5, 1.0, s.slope) * 0.75);

  // vegetation: up to a ragged line at ~45 % of the relief, off steep slopes, denser in hollows
  const line = 0.45 + (broad - 0.5) * 0.25 + hollow * 0.06;
  let veg = smoothstep(line + 0.12, line - 0.12, s.t) * (1 - smoothstep(0.42, 0.8, s.slope));
  veg *= Math.min(1, Math.max(0, 0.8 + (patch - 0.5) * 0.8 + hollow * 0.25));
  if (veg > 0) {
    // lush forest low and in gullies, grass and scrub higher up and on ridges
    const lush = Math.min(1, Math.max(0, 1 - s.t * 1.8 + (patch - 0.5) * 0.9 + hollow * 0.5));
    const v: RGB = [GRASS[0], GRASS[1], GRASS[2]];
    mix(v, FOREST, lush);
    mix(out, v, veg);
  }

  // beach: a strip of sand on gentle shores, a few metres up
  const beach = (1 - smoothstep(2, 7 + broad * 6, s.above)) * (1 - smoothstep(0.12, 0.3, s.slope));
  mix(out, SAND, beach);
  return out;
}

/**
 * Shading the lighting misses, over whatever covers the ground (vegetation, fresh lava, ash): hollows
 * darker (occluded), ridges and rims a little lighter, plus a fine grain so no surface is flat paint.
 */
export function groundShade(s: GroundShape, out: RGB, fine = true): RGB {
  const hollow = Math.max(-1, Math.min(1, s.hollow * 6));
  const grain = fine ? valueNoise(s.x + 300, s.y + 900, 18) * 0.6 + valueNoise(s.x - 50, s.y + 70, 7) * 0.4 : 0.5;
  const k = (1 - 0.16 * hollow) * (0.92 + 0.16 * grain);
  out[0] = Math.min(1, out[0] * k);
  out[1] = Math.min(1, out[1] * k);
  out[2] = Math.min(1, out[2] * k);
  return out;
}

/** Fills the slope and hollowness of `shape` from the heights of a vertex and its four neighbours (m). */
export function shapeFrom(shape: GroundShape, h: number, east: number, west: number, north: number, south: number, spacing: number): void {
  const gx = (east - west) / (2 * spacing);
  const gy = (north - south) / (2 * spacing);
  shape.slope = Math.hypot(gx, gy);
  shape.hollow = (((east + west + north + south) / 4 - h) / spacing) * (20 / spacing);
}
