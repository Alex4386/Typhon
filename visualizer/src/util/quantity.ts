/**
 * Numbers as people read them: three significant digits, SI prefixes or a larger unit where the
 * magnitude calls for it (1.00 km³, 2.4 MW, 31 %), powers of ten only where nothing else fits.
 */

const SUPERSCRIPT: Record<string, string> = { '-': '⁻', '0': '⁰', '1': '¹', '2': '²', '3': '³', '4': '⁴', '5': '⁵', '6': '⁶', '7': '⁷', '8': '⁸', '9': '⁹' };

/** Thin no-break space between digit groups and before units. */
const GROUP = ' ';

/** A plain number: 3 significant digits, digit groups from 10 000, × 10ⁿ outside 0.001 … 1 000 000. */
export function formatNumber(v: number, digits = 3): string {
  if (!Number.isFinite(v)) return Number.isNaN(v) ? '—' : v > 0 ? '∞' : '−∞';
  if (v === 0) return '0';
  const a = Math.abs(v);
  if (a >= 1e6 || a < 1e-3) {
    const exp = Math.floor(Math.log10(a));
    let mant = v / 10 ** exp;
    let e = exp;
    if (Math.abs(Number(mant.toPrecision(digits))) >= 10) {
      mant /= 10;
      e += 1;
    }
    const m = Number(mant.toPrecision(digits)).toString();
    return `${m} × 10${String(e).replace(/./g, (c) => SUPERSCRIPT[c] ?? c)}`;
  }
  const rounded = a >= 10 ** digits ? Math.round(v) : Number(v.toPrecision(digits));
  if (Math.abs(rounded) >= 10_000) return Math.round(rounded).toLocaleString('en-US').replace(/,/g, GROUP);
  return String(rounded);
}

const SI: [number, string][] = [
  [1e12, 'T'],
  [1e9, 'G'],
  [1e6, 'M'],
  [1e3, 'k'],
  [1, ''],
  [1e-3, 'm'],
  [1e-6, 'μ'],
  [1e-9, 'n'],
];

/** Units that take an SI prefix as they are. */
const PREFIXABLE = new Set(['W', 'Pa', 'J', 'N', 'g']);

/** A display unit for a magnitude: the unit to show and the factor values are multiplied by. */
export interface DisplayUnit {
  unit: string;
  factor: number;
}

/**
 * The unit to show a quantity of `unit` around magnitude `ref` in. Volumes go to million m³ or km³,
 * lengths to km, fractions to percent, W/Pa/J take SI prefixes; anything else stays as it is.
 */
export function displayUnit(unit: string | undefined, ref: number): DisplayUnit {
  const a = Math.abs(ref);
  if (!unit) return { unit: '', factor: 1 };
  if (unit === 'fraction') return { unit: '%', factor: 100 };
  if (unit === 'm³') {
    if (a >= 1e8) return { unit: 'km³', factor: 1e-9 };
    if (a >= 1e6) return { unit: 'million m³', factor: 1e-6 };
    return { unit, factor: 1 };
  }
  if (unit === 'm' && a >= 10_000) return { unit: 'km', factor: 1e-3 };
  if (PREFIXABLE.has(unit) && a > 0) {
    for (const [scale, prefix] of SI) if (a >= scale) return { unit: prefix + unit, factor: 1 / scale };
    return { unit: `n${unit}`, factor: 1e9 };
  }
  return { unit, factor: 1 };
}

/** A value with its unit, scaled for reading ("1.00 km³", "2.4 MW", "< 0.01 %"). */
export function formatQuantity(v: number, unit?: string, digits = 3): string {
  const d = displayUnit(unit, v);
  const x = v * d.factor;
  if (d.unit === '%' && x !== 0 && Math.abs(x) < 0.01) return `${x < 0 ? '> −' : '< '}0.01${GROUP}%`;
  // a scaled volume keeps its significant zeros ("1.00 km³"): the scale says it is a measured amount
  const scaledVolume = (d.unit === 'km³' || d.unit === 'million m³') && Math.abs(x) >= 1e-3 && Math.abs(x) < 10 ** digits;
  const text = scaledVolume ? x.toPrecision(digits) : formatNumber(x, digits);
  if (!d.unit) return text;
  return `${text}${d.unit === '%' || d.unit === '°' ? (d.unit === '%' ? `${GROUP}%` : '°') : `${GROUP}${d.unit}`}`;
}
