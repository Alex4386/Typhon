import { describe, expect, it } from 'vitest';
import { displayUnit, formatNumber, formatQuantity } from './quantity';

const nb = (s: string) => s.replace(/ /g, ' ');

describe('quantities as people read them', () => {
  it('plain numbers: 3 significant digits, groups, powers of ten only at the extremes', () => {
    expect(formatNumber(0)).toBe('0');
    expect(formatNumber(12.345)).toBe('12.3');
    expect(formatNumber(400)).toBe('400');
    expect(nb(formatNumber(12_500))).toBe('12 500');
    expect(formatNumber(2e-12)).toBe('2 × 10⁻¹²');
    expect(formatNumber(9.996e6)).toBe('1 × 10⁷');
    expect(formatNumber(0.0123)).toBe('0.0123');
  });

  it('volumes in km³ or million m³, lengths in km, fractions in percent, SI prefixes', () => {
    expect(nb(formatQuantity(1e9, 'm³'))).toBe('1.00 km³');
    expect(nb(formatQuantity(2.5e7, 'm³'))).toBe('25.0 million m³');
    expect(nb(formatQuantity(5000, 'm³'))).toBe('5000 m³');
    expect(nb(formatQuantity(12_500, 'm'))).toBe('12.5 km');
    expect(nb(formatQuantity(620.4, 'm'))).toBe('620 m');
    expect(nb(formatQuantity(0.31, 'fraction'))).toBe('31 %');
    expect(nb(formatQuantity(2e-12, 'fraction'))).toBe('< 0.01 %');
    expect(nb(formatQuantity(2.4e6, 'W'))).toBe('2.4 MW');
    expect(nb(formatQuantity(3e-5, 'Pa'))).toBe('30 μPa');
    expect(nb(formatQuantity(24, 'MPa'))).toBe('24 MPa');
    expect(nb(formatQuantity(10, 'm³/s'))).toBe('10 m³/s');
    expect(formatQuantity(3.5)).toBe('3.5');
  });

  it('an editing scale from the range keeps the input in one unit', () => {
    expect(displayUnit('m³', 1e11)).toEqual({ unit: 'km³', factor: 1e-9 });
    expect(displayUnit('fraction', 1)).toEqual({ unit: '%', factor: 100 });
    expect(displayUnit('°C', 1200)).toEqual({ unit: '°C', factor: 1 });
  });
});
