import type { EntityProp } from '../protocol/messages';

/** Unit suffixes used in entity property names, longest first. */
const UNITS: [string, string, number?][] = [
  ['M3PerS', 'm³/s'],
  ['KgPerSm2', 'kg/s·m²'],
  ['MPerS', 'm/s'],
  ['KgS', 'kg/s'],
  ['MPa', 'MPa'],
  ['M3', 'm³'],
  ['Wt', 'wt%'],
  ['Deg', '°'],
  ['C', '°C'],
  ['M', 'm'],
];

const LABELS: Record<string, string> = {
  overpressureMPa: 'Overpressure',
  tensileStrengthMPa: 'Rock strength',
  temperatureC: 'Temperature',
  groundTemperatureC: 'Ground temperature',
  maxTemperatureC: 'Hottest',
  silicaWt: 'Silica',
  waterWt: 'Water',
  crystalFraction: 'Crystals',
  volumeM3: 'Volume',
  depthM: 'Depth',
  eruptionRateM3PerS: 'Eruption rate',
  regime: 'Regime',
  styleEstimate: 'Style (estimate)',
  vei: 'VEI',
  radiusM: 'Radius (drawn)',
  ventId: 'Vent',
  shape: 'Shape',
  craterRadiusM: 'Crater radius',
  lengthM: 'Length',
  strikeDeg: 'Strike',
  erupting: 'Erupting',
  status: 'Status',
  startedAt: 'Started',
  tipDepthM: 'Tip depth',
  heightM: 'Height',
  openingM: 'Opening',
  strikeLengthM: 'Length along strike',
  speedMPerS: 'Speed',
  maxSpeedMPerS: 'Fastest',
  fissure: 'Fed fissure',
  feature: 'Type',
  level: 'Level',
  topZ: 'Top elevation',
  massRateKgS: 'Mass eruption rate',
  station: 'Station',
  activeCells: 'Active cells',
  moltenVolumeM3: 'Molten volume',
  emittedM3: 'Erupted so far',
  solidifiedM3: 'Solidified',
  runoutM: 'Run-out',
  sedimentFraction: 'Sediment',
  magnitude: 'Magnitude',
  type: 'Type',
  time: 'Time',
  durationSeconds: 'Duration',
  swarm: 'Part of a swarm',
};

/** Human label of a property key ("overpressureMPa" → "Overpressure"). */
export function propLabel(key: string): string {
  if (LABELS[key]) return LABELS[key];
  let base = key;
  for (const [suffix] of UNITS) {
    if (key.endsWith(suffix) && key.length > suffix.length) {
      base = key.slice(0, -suffix.length);
      break;
    }
  }
  const words = base.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function num(v: number): string {
  const a = Math.abs(v);
  if (a === 0) return '0';
  if (a >= 1e6 || a < 1e-3) return v.toExponential(2);
  if (a >= 100) return Math.round(v).toLocaleString('en-US');
  return String(Number(v.toPrecision(3)));
}

/** Value of a property with its unit ("12.3 MPa", "1 200 °C", "yes"). */
export function formatProp(key: string, v: EntityProp): string {
  if (v === null || v === undefined) return '—';
  if (typeof v === 'boolean') return v ? 'yes' : 'no';
  if (typeof v === 'string') return v.replace(/_/g, ' ').toLowerCase().replace(/^./, (c) => c.toUpperCase());
  if (key === 'crystalFraction' || key === 'sedimentFraction') return `${Math.round(v * 100)} %`;
  if (key === 'durationSeconds') return `${num(v)} s`;
  if (key === 'magnitude') return `M ${v.toFixed(1)}`;
  for (const [suffix, unit] of UNITS) {
    if (key.endsWith(suffix) && key.length > suffix.length) {
      if (unit === 'm³' && Math.abs(v) >= 1e6) return `${num(v / 1e6)} million m³`;
      if (unit === 'm' && Math.abs(v) >= 10_000) return `${num(v / 1000)} km`;
      return `${num(v)} ${unit}`;
    }
  }
  return num(v);
}

/** Keys shown elsewhere (header, times) rather than in the property table. */
export const HIDDEN_PROPS = new Set(['startedAt', 'time', 'configPath']);
