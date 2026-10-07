import { describe, expect, it } from 'vitest';
import type { VentInfo, VolcanoState, WorldInfo } from '../protocol/messages';
import { fountainSources } from './fountains';

const crater: VentInfo = { id: 'summit', kind: 'crater', at: [0, 0], z: 0, radius: 40 };
const fissure: VentInfo = { id: 'f1', kind: 'fissure', at: [500, 0], z: 0, radius: 2, line: [[100, 0], [900, 0]] };
const world = { volcanoes: [{ id: 'v', name: 'V', vents: [crater, fissure], chamber: { center: [0, 0, 0], radius: 1 } }] } as unknown as WorldInfo;
const state = (regime: string, rate: number, activeVents?: string[]) =>
  ({ v: { chamber: { eruptionRate: rate, regime }, activeVents } as unknown as VolcanoState });

describe('fountainSources', () => {
  it('throws no fire from a vent under the sea', () => {
    const sea = { ...world, seaLevel: 50, hasSea: true } as unknown as WorldInfo;
    expect(fountainSources(sea, state('FOUNTAINING', 100))).toEqual([]);
  });
  it('keeps an erupting fissure curtain while effusing, lower than when fountaining', () => {
    const eff = fountainSources(world, state('EFFUSIVE', 100, ['f1']));
    expect(eff.map((f) => f.vent.id)).toEqual(['f1']);
    const fount = fountainSources(world, state('FOUNTAINING', 100, ['f1']));
    expect(fount[0].heightM).toBeGreaterThan(eff[0].heightM);
    expect(fount[0].weight).toBe(8); // 800 m of fissure
  });
  it('draws only the vents that erupt, and nothing between eruptions or when explosive', () => {
    expect(fountainSources(world, state('FOUNTAINING', 100, ['summit'])).map((f) => f.vent.id)).toEqual(['summit']);
    expect(fountainSources(world, state('FOUNTAINING', 0, []))).toEqual([]);
    expect(fountainSources(world, state('EXPLOSIVE', 100, ['f1', 'summit']))).toEqual([]);
  });
});
