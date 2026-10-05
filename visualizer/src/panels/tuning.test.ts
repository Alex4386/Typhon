import { describe, expect, it } from 'vitest';
import type { ParamSpec, SimEvent, VolcanoState } from '../protocol/messages';
import { formatDuration, formatFactor } from '../util/format';
import { describeEvent, toastTone } from './events';
import { MAGMA_PRESETS, fieldError, fromSlider, injectWarnings, mixPreview, toSlider } from './inject';

const chamber = (over: Partial<VolcanoState['chamber']> = {}): VolcanoState =>
  ({
    chamber: { overpressureMPa: 1, tensileStrengthMPa: 10, temperatureC: 1150, silicaWt: 50, waterWt: 0.5, crystalFraction: 0, eruptionRate: 0, regime: 'NONE', volumeM3: 9e6, ...over },
  }) as VolcanoState;

const spec = (o: Partial<ParamSpec>): ParamSpec => ({ id: 'x', label: 'x', group: 'g', type: 'number', apply: 'hot', ...o });

describe('time formatting', () => {
  it('reads like a person would say it', () => {
    expect(formatDuration(42)).toBe('42 s');
    expect(formatDuration(300)).toBe('5 min');
    expect(formatDuration(3 * 3600 + 20 * 60)).toBe('3 h 20 min');
    expect(formatDuration(12 * 86400)).toBe('12 days');
    expect(formatDuration(4.2 * 365.25 * 86400)).toBe('4.2 years');
    expect(formatFactor(20)).toBe('×20');
    expect(formatFactor(5000)).toBe('×5 000');
    expect(formatFactor(0)).toBe('—');
  });
});

describe('notifications', () => {
  it('speak plainly and only for big events', () => {
    const quake = (m: number) => ({ kind: 'seismic', time: 0, volcanoId: 'k', type: 'VT', magnitude: m, hypocenter: [0, 0, -3000], durationSeconds: 1, swarm: false }) as SimEvent;
    expect(describeEvent(quake(4.2))).toBe('M4.2 rock-breaking quake, 3.0 km below sea level');
    expect(toastTone(quake(2))).toBeNull();
    expect(toastTone(quake(4.5))).toBe('warn');
    expect(toastTone({ kind: 'eruptionStarted', time: 0, volcanoId: 'k', cause: 'OVERPRESSURE', ventIds: [] })).toBe('alert');
    expect(describeEvent({ kind: 'alertChanged', time: 0, volcanoId: 'k', previous: 'DORMANT', current: 'MINOR_ACTIVITY' })).toBe('Status: Quiet → Restless');
  });
});

describe('magma injection', () => {
  it('previews the mix by volume fraction', () => {
    const p = mixPreview(chamber(), { volumeM3: 1e6, temperatureC: 850, silicaWt: 70, waterWt: 5 })!;
    expect(p.fraction).toBeCloseTo(0.1);
    expect(p.temperatureC).toBeCloseTo(1120);
    expect(p.silicaWt).toBeCloseTo(52);
    expect(p.waterWt).toBeCloseTo(0.95);
    expect(mixPreview(chamber({ volumeM3: undefined }), { volumeM3: 1e6 })).toBeNull();
  });

  it('presets are plausible magmas, odd mixes get a note', () => {
    for (const p of MAGMA_PRESETS) expect(injectWarnings(p.values, undefined)).toEqual([]);
    expect(injectWarnings({ temperatureC: 1300, silicaWt: 74, waterWt: 6 }, undefined).length).toBeGreaterThan(0);
    expect(injectWarnings({ temperatureC: 700, silicaWt: 50, waterWt: 0.5 }, chamber()).length).toBe(2);
  });

  it('validates ranges', () => {
    const s = spec({ min: 0, max: 8, unit: 'wt%' });
    expect(fieldError(s, 4)).toBeNull();
    expect(fieldError(s, 9)).toBe('At most 8 wt%');
    expect(fieldError(s, NaN)).toBe('Enter a number');
  });
});

describe('parameter sliders', () => {
  it('round-trip linear and log scales, including log scales starting at 0', () => {
    const lin = spec({ min: 650, max: 1350 });
    expect(fromSlider(lin, toSlider(lin, 1000))).toBeCloseTo(1000);
    const log = spec({ min: 1, max: 1e7, log: true });
    expect(toSlider(log, 1e4)).toBeCloseTo(4 / 7);
    expect(fromSlider(log, 4 / 7)).toBeCloseTo(1e4, -1);
    const supply = spec({ min: 0, max: 100, log: true });
    expect(toSlider(supply, 0)).toBe(0);
    expect(fromSlider(supply, 0)).toBe(0);
    expect(fromSlider(supply, toSlider(supply, 0.1))).toBeCloseTo(0.1, 3);
  });
});
