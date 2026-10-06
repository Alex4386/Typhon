import { describe, expect, it } from 'vitest';
import type { RGB } from '../util/color';
import { CRUST_RGB, crustLight, incandescence, lavaSurfaceColor, weightedTemperature } from './lavaColor';

const lum = (c: RGB) => 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];

describe('lava surface colour', () => {
  it('is lit crust, never black, when cool or of unknown temperature', () => {
    for (const t of [0, 20, 300, 450, Number.NaN]) {
      const c = lavaSurfaceColor(t, CRUST_RGB, 1, [0, 0, 0]);
      expect(lum(c)).toBeGreaterThan(0.15);
      expect(c[0]).toBeCloseTo(CRUST_RGB[0]);
    }
  });

  it('brightens monotonically into incandescence with temperature', () => {
    let prev = -1;
    for (let t = 400; t <= 1250; t += 50) {
      const l = lum(lavaSurfaceColor(t, CRUST_RGB, 1, [0, 0, 0]));
      expect(l).toBeGreaterThanOrEqual(prev - 0.02);
      prev = Math.max(prev, l);
    }
    const hot = lavaSurfaceColor(1150, CRUST_RGB, 1, [0, 0, 0]);
    expect(hot[0]).toBeGreaterThan(0.95);
    expect(hot[1]).toBeGreaterThan(0.6);
  });

  it('glows only above ~500 °C and fully above ~850 °C', () => {
    expect(incandescence(450)).toBe(0);
    expect(incandescence(900)).toBe(1);
    expect(incandescence(675)).toBeGreaterThan(0.3);
  });

  it('shades crust like the lit ground: sun-facing slopes lighter', () => {
    const flat = crustLight(0, 1, 0);
    const towardSun = crustLight(-0.5, 0.8, -0.3);
    const away = crustLight(0.6, 0.7, 0.4);
    expect(towardSun).toBeGreaterThan(flat);
    expect(away).toBeLessThan(flat);
    expect(away).toBeGreaterThan(0.5);
  });

  it('gives cells the smoothing spreads lava into their neighbours’ temperature', () => {
    // a margin cell: no lava of its own (T = 0), smoothed thickness from a 1100 °C neighbour
    const d = 0.25 * 4;
    const td = 0.25 * 4 * 1100;
    expect(weightedTemperature(td, d)).toBeCloseTo(1100);
    expect(weightedTemperature(0, 0)).toBe(0);
  });
});
