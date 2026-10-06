import { describe, expect, it } from 'vitest';
import { billowLight } from './Billows';

describe('billow lighting', () => {
  const n = Math.hypot(0.4, 0.8, 0.3);
  const sun: [number, number, number] = [0.4 / n, 0.8 / n, 0.3 / n];
  it('the side of a cloud facing the sun is brighter than its far side', () => {
    const lit = billowLight(sun[0], sun[1], sun[2], ...sun);
    const shadow = billowLight(-sun[0], -sun[1], -sun[2], ...sun);
    const core = billowLight(0, 0, 0, ...sun);
    expect(lit).toBeGreaterThan(core);
    expect(core).toBeGreaterThan(shadow);
  });
  it('tops are brighter than undersides under a high sun', () => {
    expect(billowLight(0, 1, 0, ...sun)).toBeGreaterThan(billowLight(0, -1, 0, ...sun));
  });
});
