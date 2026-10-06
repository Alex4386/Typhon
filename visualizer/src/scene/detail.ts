/**
 * Crater-detail levels of the tile pyramid (protocol §5.5, levels < 0): cells of 2^level columns
 * over each volcano's crater region. Where a detail tile has arrived it replaces the core's coarser
 * ground, so craters, rims and pits narrower than a column show.
 */
import type { LodLevelInfo, WorldInfo } from '../protocol/messages';

/**
 * Displayed heights of the detail meshes (scene units), by `level:tx,ty`, as for the core tiles:
 * vertex (a, b) ∈ [0, T]² sits on detail cell (tx·T + a, ty·T + b).
 */
export const detailHeights = new Map<string, { z: Float32Array; vExag: number; dExag: number }>();

export function detailLevels(world: WorldInfo): LodLevelInfo[] {
  return (world.lod?.levels ?? []).filter((l) => l.kind === 'detail');
}

/** Detail cells per core column along one axis (4 for level −2). */
export function refinement(world: WorldInfo, l: LodLevelInfo): number {
  return Math.max(1, Math.round(world.cellSize / l.cellSize));
}

/** World rectangle covered by a level's tiles (m). */
export function levelRect(world: WorldInfo, l: LodLevelInfo): { minX: number; minY: number; maxX: number; maxY: number } {
  const span = world.tileSize * l.cellSize;
  return {
    minX: world.origin[0] + l.tiles.minTx * span,
    minY: world.origin[1] + l.tiles.minTy * span,
    maxX: world.origin[0] + (l.tiles.maxTx + 1) * span,
    maxY: world.origin[1] + (l.tiles.maxTy + 1) * span,
  };
}

/**
 * Whether core column (i, j) is drawn by loaded detail tiles of some level (`has(level, tx, ty)`
 * says whether a tile is loaded). A column's detail cells all sit in one tile when the tile size is
 * a multiple of the refinement; otherwise both corner tiles must be loaded.
 */
export function detailCovers(world: WorldInfo, levels: LodLevelInfo[], has: (level: number, tx: number, ty: number) => boolean, i: number, j: number): boolean {
  const t = world.tileSize;
  for (const l of levels) {
    const r = refinement(world, l);
    const tx0 = Math.floor((i * r) / t);
    const ty0 = Math.floor((j * r) / t);
    const tx1 = Math.floor(((i + 1) * r - 1) / t);
    const ty1 = Math.floor(((j + 1) * r - 1) / t);
    if (tx0 < l.tiles.minTx || ty0 < l.tiles.minTy || tx1 > l.tiles.maxTx || ty1 > l.tiles.maxTy) continue;
    if (has(l.level, tx0, ty0) && has(l.level, tx1, ty1)) return true;
  }
  return false;
}

/**
 * Detail tiles overlapping core tile (tx, ty), as `[level, tx, ty]` (the core tile's ground is
 * rebuilt when any of them arrives or goes).
 */
export function overlappingDetailTiles(world: WorldInfo, levels: LodLevelInfo[], tx: number, ty: number): [number, number, number][] {
  const t = world.tileSize;
  const out: [number, number, number][] = [];
  for (const l of levels) {
    const r = refinement(world, l);
    const a0 = Math.max(l.tiles.minTx, Math.floor((tx * t * r) / t));
    const a1 = Math.min(l.tiles.maxTx, Math.floor(((tx + 1) * t * r - 1) / t));
    const b0 = Math.max(l.tiles.minTy, Math.floor((ty * t * r) / t));
    const b1 = Math.min(l.tiles.maxTy, Math.floor(((ty + 1) * t * r - 1) / t));
    for (let b = b0; b <= b1; b++) for (let a = a0; a <= a1; a++) out.push([l.level, a, b]);
  }
  return out;
}

/**
 * Whether the camera is close enough to a detail region to want it: within `reach` of the
 * rectangle horizontally (reach = 1.5 × the region's size, at least 3 km) and less than twice the
 * reach above the ground; `hold` (> 1) widens both for a region already shown, so it does not
 * flicker on and off at the boundary.
 */
export function wantsDetail(
  rect: { minX: number; minY: number; maxX: number; maxY: number },
  camX: number,
  camY: number,
  heightAboveGround: number,
  hold: number,
): boolean {
  const size = Math.max(rect.maxX - rect.minX, rect.maxY - rect.minY);
  const reach = Math.max(1.5 * size, 3000) * hold;
  const dx = Math.max(rect.minX - camX, 0, camX - rect.maxX);
  const dy = Math.max(rect.minY - camY, 0, camY - rect.maxY);
  return Math.hypot(dx, dy) < reach && heightAboveGround < 2 * reach;
}
