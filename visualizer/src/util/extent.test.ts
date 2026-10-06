import { describe, expect, it } from 'vitest';
import type { SimEvent, WorldExtentMessage, WorldInfo } from '../protocol/messages';
import { describeRow, isImportant, keyEventRows } from '../panels/events';
import { applyWorldExtent, simulatedOutline } from './extent';

const world: WorldInfo = {
  name: 'w',
  origin: [-1000, -1000],
  cellSize: 10,
  tileSize: 50, // tiles 500 m wide
  tiles: { minTx: 0, minTy: 0, maxTx: 3, maxTy: 3 },
  seaLevel: 0,
  elevationRange: [0, 100],
  volcanoes: [],
  materials: [],
  depositTypes: [],
  lod: { levels: [], extent: [-5000, -5000, 5000, 5000] },
};

describe('world extent', () => {
  it('applies growth without moving the origin or existing tile coordinates', () => {
    const m: WorldExtentMessage = {
      type: 'worldExtent',
      sessionId: 's1',
      tiles: { minTx: -1, minTy: 0, maxTx: 3, maxTy: 3 },
      simulated: [[-1, 1], [0, 0], [0, 1]],
      expansion: { addedTiles: 1, maxTiles: 400, enabled: true },
    };
    const grown = applyWorldExtent(world, m);
    expect(grown.origin).toEqual(world.origin);
    expect(grown.tiles.minTx).toBe(-1);
    expect(grown.simulated).toEqual(m.simulated);
    expect(grown.lod).toBe(world.lod); // kept when the message has none
    expect(grown.expansion?.addedTiles).toBe(1);
    const span = grown.tileSize * grown.cellSize;
    expect(grown.origin[0] + grown.tiles.minTx * span).toBe(-1500); // west edge of the new tiles
    expect(grown.origin[0] + (grown.tiles.maxTx + 1) * span).toBe(1000);
  });

  it('outlines the simulated tiles only where they border unsimulated ground', () => {
    // a 2×1 block of simulated tiles: 6 outer sides, the shared side is not drawn
    const two = { ...world, simulated: [[0, 0], [1, 0]] as [number, number][] };
    const segs = simulatedOutline(two);
    expect(segs).toHaveLength(6);
    expect(segs).not.toContainEqual([-500, -1000, -500, -500]);
    expect(segs).toContainEqual([-1000, -1000, -500, -1000]);
    // without a list the whole tile range is simulated: its perimeter, 4 tiles per side
    expect(simulatedOutline(world)).toHaveLength(16);
  });

  it('aggregates growth into one key-event row with the total tiles', () => {
    const grow = (time: number, tiles: number, areaKm2: number) =>
      ({ kind: 'areaExpanded', time, tiles, addedTiles: tiles, areaKm2, bbox: [0, 0, 1, 1] }) as SimEvent;
    const events = [grow(10, 3, 105), grow(20, 2, 108), grow(30, 4, 114)];
    expect(isImportant(events[0])).toBe(true);
    const rows = keyEventRows(events, 10);
    expect(rows).toHaveLength(1);
    expect(rows[0].count).toBe(3);
    expect(describeRow(rows[0])).toBe('Simulation area expanded 3 times: +9 tiles, now 114 km²');
  });
});
