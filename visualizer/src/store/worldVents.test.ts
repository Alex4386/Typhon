import { describe, expect, it } from 'vitest';
import type { SimEvent, VentInfo, WorldInfo } from '../protocol/messages';
import { volcanoAnchor, withOpenedFissures } from './worldVents';

const crater: VentInfo = { id: 'summit', kind: 'crater', at: [0, 0], z: 100, radius: 40 };
const fissure: VentInfo = { id: 'f1', kind: 'fissure', at: [500, 0], z: 50, radius: 2, line: [[400, 0], [600, 0]] };
const world = { volcanoes: [{ id: 'v', name: 'V', vents: [crater], chamber: { center: [0, 0, -3000], radius: 500 } }] } as unknown as WorldInfo;
const opened = { kind: 'fissureOpened', time: 10, volcanoId: 'v', vent: fissure } as SimEvent;

describe('withOpenedFissures', () => {
  it('adds a fissure opened after the world was sent', () => {
    const w = withOpenedFissures(world, [opened])!;
    expect(w.volcanoes[0].vents.map((x) => x.id)).toEqual(['summit', 'f1']);
    expect(world.volcanoes[0].vents).toHaveLength(1);
  });
  it('replaces a vent it already knows and ignores other events and volcanoes', () => {
    const twice = withOpenedFissures(withOpenedFissures(world, [opened]), [opened])!;
    expect(twice.volcanoes[0].vents).toHaveLength(2);
    const other = { ...opened, volcanoId: 'w' } as SimEvent;
    expect(withOpenedFissures(world, [other, { kind: 'message', time: 1, text: 'x' } as SimEvent])).toBe(world);
  });
  it('adds the vent a fissure localised into', () => {
    const vent: VentInfo = { id: 'f1-vent-3', kind: 'crater', at: [450, 0], z: 50, radius: 1 };
    const formed = { kind: 'ventFormed', time: 20, volcanoId: 'v', vent, fissureId: 'f1' } as SimEvent;
    const w = withOpenedFissures(world, [opened, formed])!;
    expect(w.volcanoes[0].vents.map((x) => x.id)).toEqual(['summit', 'f1', 'f1-vent-3']);
  });
});

describe('volcanoAnchor', () => {
  it('is the first vent, or above the chamber while there is none', () => {
    expect(volcanoAnchor(world.volcanoes[0])).toEqual([0, 0]);
    const placed = { ...world.volcanoes[0], vents: [], chamber: { center: [120, -80, -3000], radius: 500 } } as WorldInfo['volcanoes'][0];
    expect(volcanoAnchor(placed)).toEqual([120, -80]);
  });
});
