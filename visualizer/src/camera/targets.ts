import { Field } from '../protocol/fields';
import type { StateMessage, VolcanoInfo, WorldInfo, XY } from '../protocol/messages';
import { displayZ } from '../scene/Terrain';
import { tileStore } from '../store/store';
import { columnOf, sampleColumn, worldExtent } from '../util/world';
import { frameDistance, orbitPose, type Bookmark, type CameraPose, type FollowTarget } from './math';
import { volcanoAnchor } from '../store/worldVents';

/** Everything the camera needs to know about the scene to compute targets. */
export interface SceneInfo {
  world: WorldInfo;
  state: StateMessage | null;
  volcanoId: string | null;
  vExag: number;
  dExag: number;
  sectionPolyline: XY[];
  fov: number;
  aspect: number;
}

export function sceneSpan(world: WorldInfo): number {
  const ext = worldExtent(world);
  return Math.max(ext.maxX - ext.minX, ext.maxY - ext.minY);
}

export function volcanoOf(info: SceneInfo): VolcanoInfo | undefined {
  return info.world.volcanoes.find((v) => v.id === info.volcanoId) ?? info.world.volcanoes[0];
}

/** Whether the elevation tile under world (x, y) has arrived. */
export function groundKnown(info: SceneInfo, x: number, y: number): boolean {
  const ext = worldExtent(info.world);
  if (x < ext.minX || x >= ext.maxX || y < ext.minY || y >= ext.maxY) return true;
  const [i, j] = columnOf(info.world, x, y);
  return Number.isFinite(sampleColumn(info.world, Field.SurfaceElevation, i, j, Number.NaN));
}

/**
 * Ground height (scene units) at world (x, y): sea level outside the world, and an upper-middle
 * elevation of the world's range where the tile has not streamed in yet (so a camera placed before
 * the terrain arrives is not put inside the mountain).
 */
export function groundAt(info: SceneInfo, x: number, y: number): number {
  const ext = worldExtent(info.world);
  if (x < ext.minX || x >= ext.maxX || y < ext.minY || y >= ext.maxY) return info.world.seaLevel * info.vExag;
  if (!groundKnown(info, x, y)) {
    const [lo, hi] = info.world.elevationRange;
    return (lo + 0.85 * (hi - lo)) * info.vExag;
  }
  return displayZ(info.world, x, y, info.vExag, info.dExag);
}

/** Scene point of the active volcano's main vent at the ground. */
export function ventPoint(info: SceneInfo): [number, number, number] {
  const v = volcanoOf(info);
  const ext = worldExtent(info.world);
  const at = v ? volcanoAnchor(v) : [(ext.minX + ext.maxX) / 2, (ext.minY + ext.maxY) / 2];
  const g = groundAt(info, at[0], at[1]);
  return [at[0], g, -at[1]];
}

/** Scene point of the plume top above the active volcano, or null without a plume. */
export function plumeTop(info: SceneInfo): [number, number, number] | null {
  const v = volcanoOf(info);
  const plume = v ? info.state?.volcanoes[v.id]?.plume : undefined;
  if (!plume || !(plume.topZ > 0)) return null;
  const vent = ventPoint(info);
  return [vent[0], Math.max(vent[1], plume.topZ * info.vExag), vent[2]];
}

/**
 * Scene point of the lava flow front: the molten cell farthest from the active volcano's vent
 * (loaded tiles only), or null without lava.
 */
export function lavaFront(info: SceneInfo): [number, number, number] | null {
  const tiles = tileStore.get(Field.LavaDepth);
  if (!tiles) return null;
  const vent = ventPoint(info);
  const vx = vent[0];
  const vy = -vent[2];
  const { cellSize, tileSize, origin } = info.world;
  let best = -1;
  let bx = 0;
  let by = 0;
  for (const tile of tiles.values()) {
    const v = tile.values;
    const w = tile.width;
    for (let k = 0; k < v.length; k++) {
      if (!(v[k] > 0.1)) continue;
      const x = origin[0] + (tile.tileX * tileSize + (k % w) + 0.5) * cellSize;
      const y = origin[1] + (tile.tileY * tileSize + Math.floor(k / w) + 0.5) * cellSize;
      const d = (x - vx) * (x - vx) + (y - vy) * (y - vy);
      if (d > best) {
        best = d;
        bx = x;
        by = y;
      }
    }
  }
  if (best < 0) return null;
  return [bx, groundAt(info, bx, by), -by];
}

/** Point the follow mode tracks, or null if the target does not exist right now. */
export function followPoint(info: SceneInfo, target: FollowTarget): [number, number, number] | null {
  switch (target) {
    case 'vent':
      return ventPoint(info);
    case 'volcano': {
      const v = ventPoint(info);
      // a little above the vent so the whole edifice stays in view
      return [v[0], v[1] + 0.015 * sceneSpan(info.world), v[2]];
    }
    case 'plumeTop':
      return plumeTop(info);
    case 'lavaFront':
      return lavaFront(info);
  }
}

/** Oblique view of the active volcano's summit from the south-south-east (the default framing). */
export function summitPose(info: SceneInfo): CameraPose {
  const span = sceneSpan(info.world);
  const vent = ventPoint(info);
  // looking north-north-east, i.e. the camera sits south-south-west of the vent
  return orbitPose(vent, span * 0.42, (20 * Math.PI) / 180, (32 * Math.PI) / 180);
}

/** Whole world from high above, looking north. */
export function overviewPose(info: SceneInfo): CameraPose {
  const ext = worldExtent(info.world);
  const span = sceneSpan(info.world);
  const cx = (ext.minX + ext.maxX) / 2;
  const cy = (ext.minY + ext.maxY) / 2;
  const mid = ((info.world.elevationRange[0] + info.world.elevationRange[1]) / 2) * info.vExag;
  // a square seen from 58° is foreshortened; half the span fits it with a small margin
  const d = frameDistance(span * 0.5, info.fov, info.aspect, 1.0);
  return orbitPose([cx, mid, -cy], d, 0, (58 * Math.PI) / 180);
}

/**
 * The eruption column from the side so that ground to top fits (a 20 km Plinian column too);
 * falls back to the summit view without a plume.
 */
export function plumePose(info: SceneInfo): CameraPose {
  const top = plumeTop(info);
  if (!top) return summitPose(info);
  const vent = ventPoint(info);
  const h = top[1] - vent[1];
  const center: [number, number, number] = [vent[0], vent[1] + h / 2, vent[2]];
  const d = frameDistance(Math.max(h / 2, 500), info.fov, info.aspect, 1.2);
  return orbitPose(center, d, 0, (6 * Math.PI) / 180);
}

/** Looks across the cross-section line from its left side, fitting the line. */
export function sectionPose(info: SceneInfo): CameraPose | null {
  const line = info.sectionPolyline;
  if (line.length < 2) return null;
  const a = line[0];
  const b = line[line.length - 1];
  const mx = (a[0] + b[0]) / 2;
  const my = (a[1] + b[1]) / 2;
  const len = Math.hypot(b[0] - a[0], b[1] - a[1]);
  const along = Math.atan2(b[0] - a[0], b[1] - a[1]); // compass bearing of the line
  const g = groundAt(info, mx, my);
  const d = frameDistance(Math.max(len / 2, 300), info.fov, info.aspect, 1.2);
  // look perpendicular to the line (to its right), i.e. from its left side
  return orbitPose([mx, g, -my], d, along + Math.PI / 2, (25 * Math.PI) / 180);
}

/** Built-in bookmarks for the current world. */
export function builtinBookmarks(info: SceneInfo): Bookmark[] {
  const list: Bookmark[] = [
    { name: 'Summit', pose: summitPose(info), builtin: true },
    { name: 'Overview', pose: overviewPose(info), builtin: true },
  ];
  if (plumeTop(info)) list.push({ name: 'Plume', pose: plumePose(info), builtin: true });
  const s = sectionPose(info);
  if (s) list.push({ name: 'Section line', pose: s, builtin: true });
  return list;
}
