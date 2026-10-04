export type RGB = [number, number, number];

function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t;
}

/** Piecewise-linear colour ramp; stops are [t, r, g, b] with t ascending and rgb in 0..1. */
export function ramp(stops: [number, number, number, number][], t: number, out: RGB = [0, 0, 0]): RGB {
  if (t <= stops[0][0]) {
    out[0] = stops[0][1];
    out[1] = stops[0][2];
    out[2] = stops[0][3];
    return out;
  }
  for (let i = 1; i < stops.length; i++) {
    if (t <= stops[i][0]) {
      const a = stops[i - 1];
      const b = stops[i];
      const u = (t - a[0]) / (b[0] - a[0] || 1);
      out[0] = lerp(a[1], b[1], u);
      out[1] = lerp(a[2], b[2], u);
      out[2] = lerp(a[3], b[3], u);
      return out;
    }
  }
  const l = stops[stops.length - 1];
  out[0] = l[1];
  out[1] = l[2];
  out[2] = l[3];
  return out;
}

/** Land hypsometric tint, t = 0 at sea level → 1 at the highest summit. */
export const HYPSO: [number, number, number, number][] = [
  [0, 0.42, 0.52, 0.33],
  [0.12, 0.47, 0.56, 0.34],
  [0.3, 0.55, 0.53, 0.38],
  [0.55, 0.52, 0.45, 0.38],
  [0.8, 0.45, 0.4, 0.37],
  [1, 0.6, 0.58, 0.56],
];

/** Sea floor, t = 0 at sea level → 1 at the deepest point. */
export const BATHY: [number, number, number, number][] = [
  [0, 0.55, 0.58, 0.52],
  [1, 0.25, 0.3, 0.33],
];

/** Approximate incandescence of lava by temperature (°C). */
export const BLACKBODY: [number, number, number, number][] = [
  [400, 0.12, 0.06, 0.05],
  [650, 0.45, 0.05, 0.02],
  [800, 0.85, 0.15, 0.02],
  [950, 1, 0.42, 0.05],
  [1100, 1, 0.72, 0.25],
  [1250, 1, 0.92, 0.65],
];

/** Generic temperature ramp for fields (°C). */
export const THERMAL: [number, number, number, number][] = [
  [0, 0.12, 0.16, 0.42],
  [25, 0.2, 0.45, 0.7],
  [60, 0.35, 0.72, 0.55],
  [100, 0.95, 0.85, 0.3],
  [250, 0.95, 0.45, 0.15],
  [600, 0.8, 0.1, 0.1],
  [1100, 1, 0.95, 0.85],
];

/** Diverging ramp for signed values normalised to −1..1. */
export const DIVERGING: [number, number, number, number][] = [
  [-1, 0.2, 0.35, 0.8],
  [0, 0.92, 0.92, 0.9],
  [1, 0.8, 0.25, 0.15],
];

export function hexToRgb(hex: string): RGB {
  const h = hex.replace('#', '');
  const n = parseInt(h.length >= 6 ? h.slice(0, 6) : h, 16);
  return [((n >> 16) & 255) / 255, ((n >> 8) & 255) / 255, (n & 255) / 255];
}

export function rgbCss([r, g, b]: RGB, a = 1): string {
  return `rgba(${Math.round(r * 255)},${Math.round(g * 255)},${Math.round(b * 255)},${a})`;
}

/** Deterministic pastel-ish colour for an integer id, offset from a base colour. */
export function shadeFor(base: RGB, id: number): RGB {
  const k = ((id * 2654435761) >>> 0) / 4294967296;
  const f = 0.75 + k * 0.5;
  return [Math.min(1, base[0] * f), Math.min(1, base[1] * f), Math.min(1, base[2] * f)];
}

export const ALERT_COLORS: Record<string, string> = {
  EXTINCT: '#555b66',
  DORMANT: '#3f8f4f',
  MINOR_ACTIVITY: '#c9b53a',
  MAJOR_ACTIVITY: '#e08a2c',
  ERUPTION_IMMINENT: '#e0532c',
  ERUPTING: '#c4202a',
};

export const FEATURE_COLORS: Record<string, string> = {
  FUMAROLE: '#f2f2f2',
  GEYSER: '#6fd3ff',
  HOT_SPRING: '#46b8c9',
  SULFUR_SPRING: '#e5dc4a',
  MUD_POT: '#8a6a48',
  SULFUR_DEPOSIT: '#f1e53b',
  SUBMARINE_VENT: '#3a76c9',
};
