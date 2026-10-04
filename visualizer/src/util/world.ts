import type { FieldId } from '../protocol/fields';
import type { WorldInfo, XY } from '../protocol/messages';
import { getTile } from '../store/store';

/** Value of `field` at global column (i, j), or `fallback` if the tile is not loaded. */
export function sampleColumn(world: WorldInfo, field: FieldId, i: number, j: number, fallback = 0): number {
  const t = world.tileSize;
  const tx = Math.floor(i / t);
  const ty = Math.floor(j / t);
  const tile = getTile(field, tx, ty);
  if (!tile) return fallback;
  return tile.values[(j - ty * t) * tile.width + (i - tx * t)];
}

/** Global column containing world point (x, y). */
export function columnOf(world: WorldInfo, x: number, y: number): [number, number] {
  return [Math.floor((x - world.origin[0]) / world.cellSize), Math.floor((y - world.origin[1]) / world.cellSize)];
}

export function sampleAt(world: WorldInfo, field: FieldId, x: number, y: number, fallback = 0): number {
  const [i, j] = columnOf(world, x, y);
  return sampleColumn(world, field, i, j, fallback);
}

/** World (x east, y north, z up) → three.js scene (x, y up, z south). */
export function toScene(x: number, y: number, z: number, vExag: number): [number, number, number] {
  return [x, z * vExag, -y];
}

export function fromScene(sx: number, sz: number): XY {
  return [sx, -sz];
}

export function worldExtent(world: WorldInfo): { minX: number; minY: number; maxX: number; maxY: number } {
  const span = world.tileSize * world.cellSize;
  return {
    minX: world.origin[0] + world.tiles.minTx * span,
    minY: world.origin[1] + world.tiles.minTy * span,
    maxX: world.origin[0] + (world.tiles.maxTx + 1) * span,
    maxY: world.origin[1] + (world.tiles.maxTy + 1) * span,
  };
}

export function formatSimTime(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const hms = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
  return d > 0 ? `${d}d ${hms}` : hms;
}
