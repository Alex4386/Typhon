import { describe, expect, it } from 'vitest';
import { formatProp, propLabel } from './props';

describe('entity property formatting', () => {
  it('labels known and unknown keys', () => {
    expect(propLabel('overpressureMPa')).toBe('Overpressure');
    expect(propLabel('steamFluxKgPerSm2')).toBe('Steam flux');
    expect(propLabel('someNewThingM')).toBe('Some new thing');
  });

  it('formats values with units', () => {
    expect(formatProp('overpressureMPa', 12.345)).toBe('12.3 MPa');
    expect(formatProp('groundTemperatureC', 96)).toBe('96 °C');
    expect(formatProp('volumeM3', 2.5e7)).toBe('25 million m³');
    expect(formatProp('tipDepthM', 12_500)).toBe('12.5 km');
    expect(formatProp('crystalFraction', 0.31)).toBe('31 %');
    expect(formatProp('erupting', true)).toBe('yes');
    expect(formatProp('feature', 'HOT_SPRING')).toBe('Hot spring');
    expect(formatProp('magnitude', 3.14)).toBe('M 3.1');
  });
});
