import type { ParamSpec, ParamValue, VolcanoState } from '../protocol/messages';

/** Injection fields used when the server sends no schema (older servers): volume only. */
export const FALLBACK_INJECT_FIELDS: ParamSpec[] = [
  { id: 'volumeM3', label: 'Volume', unit: 'm³', group: 'Batch', type: 'number', min: 1e3, max: 1e10, log: true, default: 5e6, apply: 'live' },
];

/** Typical magmas (Wilson 1989; Sparks et al. 1998): composition and eruption temperature. */
export const MAGMA_PRESETS: { name: string; help: string; values: Record<string, number> }[] = [
  { name: 'Hot basalt', help: 'Dry mantle-derived basalt, as under Hawaiʻi: runny lava, fountains', values: { temperatureC: 1200, silicaWt: 49, waterWt: 0.4 } },
  { name: 'Wet basalt', help: 'Arc basalt with more water: lava plus Strombolian bursts', values: { temperatureC: 1120, silicaWt: 51, waterWt: 2.5 } },
  { name: 'Andesite', help: 'Typical subduction-zone magma: sticky, gas-rich, explosive', values: { temperatureC: 1000, silicaWt: 60, waterWt: 4 } },
  { name: 'Dacite', help: 'Viscous, water-rich: domes and violent explosions (Mt St Helens 1980)', values: { temperatureC: 900, silicaWt: 66, waterWt: 5 } },
  { name: 'Rhyolite', help: 'The stickiest magma: Plinian eruptions and obsidian', values: { temperatureC: 800, silicaWt: 74, waterWt: 6 } },
];

export interface MixPreview {
  /** Fraction of the mixed chamber that is new magma. */
  fraction: number;
  temperatureC: number;
  silicaWt: number;
  waterWt: number;
}

/**
 * Chamber properties after the batch mixes in completely, by volume fraction (densities of the
 * two magmas differ by a few per cent, latent heat is ignored): a rough guide, the engine
 * mixes over time.
 */
export function mixPreview(vs: VolcanoState, values: Record<string, ParamValue>): MixPreview | null {
  const vc = vs.chamber.volumeM3;
  const vi = Number(values.volumeM3);
  if (!vc || !(vc > 0) || !(vi > 0)) return null;
  const f = vi / (vc + vi);
  const mix = (cur: number, key: string) => {
    const v = values[key];
    return typeof v === 'number' && Number.isFinite(v) ? cur * (1 - f) + v * f : cur;
  };
  return {
    fraction: f,
    temperatureC: mix(vs.chamber.temperatureC, 'temperatureC'),
    silicaWt: mix(vs.chamber.silicaWt, 'silicaWt'),
    waterWt: mix(vs.chamber.waterWt, 'waterWt'),
  };
}

/** Problems with a field value (out of range, not a number); null when fine. */
export function fieldError(spec: ParamSpec, v: ParamValue | null | undefined): string | null {
  if (spec.type !== 'number') return null;
  if (typeof v !== 'number' || !Number.isFinite(v)) return 'Enter a number';
  if (spec.min !== undefined && v < spec.min) return `At least ${spec.min}${spec.unit ? ' ' + spec.unit : ''}`;
  if (spec.max !== undefined && v > spec.max) return `At most ${spec.max}${spec.unit ? ' ' + spec.unit : ''}`;
  return null;
}

/** Physically unusual combinations worth a note (not errors). */
export function injectWarnings(values: Record<string, ParamValue>, vs: VolcanoState | undefined): string[] {
  const out: string[] = [];
  const t = Number(values.temperatureC);
  const si = Number(values.silicaWt);
  const w = Number(values.waterWt);
  if (Number.isFinite(t) && Number.isFinite(si)) {
    // rough liquidus band: basalt ~1150–1250 °C, rhyolite ~700–900 °C (wet) to ~1000 °C (dry)
    const hottest = 1300 - (si - 48) * 9;
    const coolest = 1050 - (si - 48) * 12 - (Number.isFinite(w) ? w * 20 : 0);
    if (t > hottest) out.push('Unusually hot for this silica content.');
    if (t < coolest) out.push('Cold for this composition: it will be mostly crystals and may barely move.');
  }
  if (Number.isFinite(w) && w > 6) out.push('Very water-rich: expect strongly explosive behaviour.');
  if (vs && Number.isFinite(t) && t < vs.chamber.temperatureC - 250) out.push('Much cooler than the chamber: it will chill and stiffen the mix.');
  return out;
}

/** Compact number for inputs: 5000000 → "5e6"-style handled by the input; this is for labels. */
export function formatVolume(m3: number): string {
  if (m3 >= 1e9) return `${(m3 / 1e9).toPrecision(3)} km³`;
  if (m3 >= 1e6) return `${(m3 / 1e6).toPrecision(3)} million m³`;
  return `${Math.round(m3).toLocaleString('en-US')} m³`;
}

/** Lower end of a log slider: the minimum, or five decades below the maximum when the minimum is 0. */
function logFloor(spec: ParamSpec): number {
  const lo = spec.min ?? 0;
  return lo > 0 ? lo : (spec.max ?? 1) * 1e-5;
}

/** Slider position 0..1 ↔ value, logarithmic for `log` specs (position 0 is exactly the minimum). */
export function toSlider(spec: ParamSpec, v: number): number {
  const lo = spec.min ?? 0;
  const hi = spec.max ?? 1;
  if (spec.log) {
    const f = logFloor(spec);
    if (v <= f) return 0;
    return (Math.log(v) - Math.log(f)) / (Math.log(hi) - Math.log(f));
  }
  return (v - lo) / (hi - lo || 1);
}

export function fromSlider(spec: ParamSpec, p: number): number {
  const lo = spec.min ?? 0;
  const hi = spec.max ?? 1;
  if (p <= 0) return lo;
  const v = spec.log ? Math.exp(Math.log(logFloor(spec)) + p * (Math.log(hi) - Math.log(logFloor(spec)))) : lo + p * (hi - lo);
  if (spec.step) return Math.round(v / spec.step) * spec.step;
  return Number(v.toPrecision(3));
}
