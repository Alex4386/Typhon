import { describe, expect, it } from 'vitest';
import type { ParamSpec } from '../protocol/messages';
import { atRest, editApplied, isComputed, overrideSeed, shownValue } from './paramState';

const spec = (o: Partial<ParamSpec>): ParamSpec => ({ id: 'x', label: 'x', group: 'g', type: 'number', apply: 'live', ...o });

describe('auto parameters', () => {
  const wall = spec({ id: 'volcano:magma.chamber.wallRuptureRatio', auto: true, computed: 2, value: null, min: 1, max: 10 });

  it('show the computed value while the engine computes them', () => {
    expect(isComputed(wall, undefined)).toBe(true);
    expect(shownValue(wall, undefined)).toBe(2);
    expect(atRest(wall, undefined)).toBe(true);
  });

  it('switch to an override seeded with the computed value, and back with null', () => {
    const seed = overrideSeed(wall);
    expect(seed).toBe(2);
    expect(isComputed(wall, seed)).toBe(false);
    expect(shownValue(wall, 3.5)).toBe(3.5);
    expect(atRest(wall, 3.5)).toBe(false);
    expect(isComputed(wall, null)).toBe(true);
  });

  it('know when the server applied an edit', () => {
    const overridden = { ...wall, value: 3.5 };
    expect(editApplied(overridden, 3.5)).toBe(true);
    expect(editApplied(overridden, null)).toBe(false); // still overridden
    expect(editApplied(wall, null)).toBe(true); // computed again
    expect(isComputed(overridden, undefined)).toBe(false);
  });

  it('never yield null for the editor, even without a computed value', () => {
    const unknown = spec({ auto: true, computed: null, value: null, min: 0, max: 1 });
    expect(shownValue(unknown, undefined)).toBeUndefined();
    expect(overrideSeed(unknown)).toBe(0.5);
  });
});

describe('plain parameters', () => {
  const rain = spec({ default: 5, value: 8 });
  it('reset to the default with null', () => {
    expect(shownValue(rain, undefined)).toBe(8);
    expect(shownValue(rain, null)).toBe(5);
    expect(atRest(rain, null)).toBe(true);
    expect(atRest(rain, undefined)).toBe(false);
    expect(editApplied({ ...rain, value: 5 }, null)).toBe(true);
  });

  it('do not crash on a null value', () => {
    const odd = spec({ default: 1, value: null });
    expect(shownValue(odd, undefined)).toBeUndefined();
    expect(isComputed(odd, undefined)).toBe(false);
  });
});
