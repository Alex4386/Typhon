import type { WorldExtentMessage, WorldInfo } from '../protocol/messages';

/** The world after a `worldExtent` message: new tile range, simulated tiles and pyramid; everything else kept. */
export function applyWorldExtent(world: WorldInfo, m: WorldExtentMessage): WorldInfo {
  return { ...world, tiles: m.tiles, simulated: m.simulated, lod: m.lod ?? world.lod, expansion: m.expansion ?? world.expansion };
}

/**
 * Outline of the simulated ground as segments [x0, y0, x1, y1] (m): every side of a simulated tile whose
 * neighbour is not simulated. Without a `simulated` list the whole tile range is simulated.
 */
export function simulatedOutline(world: WorldInfo): [number, number, number, number][] {
  const span = world.tileSize * world.cellSize;
  const [ox, oy] = world.origin;
  const tiles = world.simulated ?? rangeTiles(world.tiles);
  const set = new Set(tiles.map(([tx, ty]) => `${tx},${ty}`));
  const out: [number, number, number, number][] = [];
  for (const [tx, ty] of tiles) {
    const x0 = ox + tx * span;
    const y0 = oy + ty * span;
    const x1 = x0 + span;
    const y1 = y0 + span;
    if (!set.has(`${tx},${ty - 1}`)) out.push([x0, y0, x1, y0]);
    if (!set.has(`${tx},${ty + 1}`)) out.push([x0, y1, x1, y1]);
    if (!set.has(`${tx - 1},${ty}`)) out.push([x0, y0, x0, y1]);
    if (!set.has(`${tx + 1},${ty}`)) out.push([x1, y0, x1, y1]);
  }
  return out;
}

function rangeTiles(b: WorldInfo['tiles']): [number, number][] {
  const out: [number, number][] = [];
  for (let ty = b.minTy; ty <= b.maxTy; ty++) for (let tx = b.minTx; tx <= b.maxTx; tx++) out.push([tx, ty]);
  return out;
}
