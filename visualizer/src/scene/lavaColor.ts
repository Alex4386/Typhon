import { BLACKBODY, ramp, type RGB } from '../util/color';

/** Cooled basalt crust (sRGB), the colour of fresh lava deposits on the ground. */
export const CRUST_RGB: RGB = [0.23, 0.2, 0.2];

/** Sun direction (scene coordinates, normalised) used to shade the unlit lava overlay like the lit ground. */
const SUN = (() => {
  const v = [-0.6, 0.55, -0.45];
  const l = Math.hypot(v[0], v[1], v[2]);
  return [v[0] / l, v[1] / l, v[2] / l];
})();

/**
 * Diffuse light on a surface with scene-space normal (nx, ny, nz), roughly matching how the lit ground
 * material is shaded by the hemisphere + key light, so crust on the (unlit) lava overlay meets the
 * ground without a seam.
 */
export function crustLight(nx: number, ny: number, nz: number): number {
  const d = Math.max(0, nx * SUN[0] + ny * SUN[1] + nz * SUN[2]);
  return 0.55 + 0.75 * d;
}

/**
 * How much of the surface colour is the lava's own glow: none below ~500 °C (a crust that only glows
 * dull red in the dark), all of it above ~850 °C (orange-yellow incandescence).
 */
export function incandescence(tempC: number): number {
  const x = Math.min(1, Math.max(0, (tempC - 500) / 350));
  return x * x * (3 - 2 * x);
}

/**
 * sRGB colour of a lava surface at `tempC`: lit crust (`crust` × `light`) blended into black-body
 * emission. Cool or unknown temperatures give crust, never black — molten lava is always skinned
 * by a dark grey crust at its margins, not a void.
 */
export function lavaSurfaceColor(tempC: number, crust: RGB, light: number, out: RGB, crack = 1): RGB {
  // a cooling flow is skinned by crust broken by glowing cracks: below ~1050 °C only the cracks glow
  // fully, above it the open channel glows everywhere
  const known = Number.isFinite(tempC);
  const open = known ? Math.min(1, Math.max(0, (tempC - 950) / 150)) : 0;
  const k = (known ? incandescence(tempC) : 0) * (open + (1 - open) * crack);
  const r0 = Math.min(1, crust[0] * light);
  const g0 = Math.min(1, crust[1] * light);
  const b0 = Math.min(1, crust[2] * light);
  if (k <= 0) {
    out[0] = r0;
    out[1] = g0;
    out[2] = b0;
    return out;
  }
  // glow adds to the crust: a dull red glow never makes the surface darker than its crust
  ramp(BLACKBODY, tempC, out);
  out[0] = Math.max(r0, r0 + (out[0] - r0) * k);
  out[1] = Math.max(g0, g0 + (out[1] - g0) * k);
  out[2] = Math.max(b0, b0 + (out[2] - b0) * k);
  return out;
}

/**
 * Thickness-weighted temperature: smoothing spreads the lava sheet a little beyond the cells that
 * hold lava (whose temperature field is 0), so the temperature is averaged with the same kernel,
 * weighted by thickness. `tdSmoothed` is the smoothed product T·d, `dSmoothed` the smoothed thickness.
 */
export function weightedTemperature(tdSmoothed: number, dSmoothed: number): number {
  return dSmoothed > 1e-6 ? tdSmoothed / dSmoothed : 0;
}

/**
 * Crack pattern at a map point (m), in [0.15, 1]: cell-scale value noise sharpened into a network of
 * narrow bright seams, so cooling crust reads as broken plates rather than smooth bands.
 */
export function crackPattern(x: number, y: number): number {
  const n = valueNoise(x / 37, y / 37) * 0.65 + valueNoise(x / 13, y / 13) * 0.35;
  const seam = 1 - Math.min(1, Math.abs(n - 0.5) * 6);
  return 0.15 + 0.85 * seam * seam;
}

function hash2(i: number, j: number): number {
  const x = Math.sin(i * 127.1 + j * 311.7) * 43758.5453;
  return x - Math.floor(x);
}

function valueNoise(x: number, y: number): number {
  const i = Math.floor(x);
  const j = Math.floor(y);
  const fx = x - i;
  const fy = y - j;
  const sx = fx * fx * (3 - 2 * fx);
  const sy = fy * fy * (3 - 2 * fy);
  const a = hash2(i, j);
  const b = hash2(i + 1, j);
  const c = hash2(i, j + 1);
  const d = hash2(i + 1, j + 1);
  return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy;
}
