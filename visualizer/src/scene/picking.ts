import type { Entity, XY } from '../protocol/messages';
import type { EntityMap, Selection } from '../store/entities';

/** Kinds that sit on the ground surface (drawn draped; picked by a click near them on the terrain). */
export const SURFACE_KINDS = new Set(['vent', 'fissure', 'feature', 'station', 'lavaFront', 'pdc', 'lahar']);

/** Protocol metres [x, y, z] → scene coordinates (y up, north = −z, heights exaggerated). */
export function toScene(at: readonly number[], vExag: number, groundZ?: number): [number, number, number] {
  return [at[0], groundZ ?? at[2] * vExag, -at[1]];
}

/** Scene point → protocol (x, y). */
export function sceneToWorld(p: { x: number; z: number }): XY {
  return [p.x, -p.z];
}

/**
 * Horizontal pick tolerance (m) for a click on the terrain seen from `cameraDistance` metres away:
 * about 2.5 % of the distance (a few pixels at usual fields of view), at least 10 m.
 */
export function pickRadius(cameraDistance: number): number {
  return Math.max(10, Math.min(400, cameraDistance * 0.025));
}

/**
 * The surface entity nearest to a terrain click at `xy`, if one is within `radius` metres;
 * markers are small, so a click next to one selects it rather than the ground.
 */
export function nearestSurfaceEntity(entities: EntityMap | Entity[], xy: XY, radius: number): Entity | null {
  let best: Entity | null = null;
  let bestD = radius;
  for (const e of Array.isArray(entities) ? entities : Object.values(entities)) {
    if (e.hidden || !SURFACE_KINDS.has(e.kind) || ('removedAt' in e && e.removedAt !== undefined)) continue;
    const d = Math.hypot(e.at[0] - xy[0], e.at[1] - xy[1]);
    if (d <= bestD) {
      best = e;
      bestD = d;
    }
  }
  return best;
}

/** The world point [x, y, z] a selection refers to (z without exaggeration), or null if gone. */
export function selectionAnchor(sel: Selection | null, entities: EntityMap, groundAt?: (xy: XY) => number): [number, number, number] | null {
  if (!sel) return null;
  if (sel.type === 'point') return [sel.at[0], sel.at[1], groundAt ? groundAt(sel.at) : 0];
  if (sel.type === 'quake') return sel.event.hypocenter;
  const e = entities[sel.id];
  if (!e) return null;
  if (e.kind === 'plume' && typeof e.props.topZ === 'number') return [e.at[0], e.at[1], (e.at[2] + e.props.topZ) / 2];
  if (e.kind === 'dike' && e.path && e.path.length > 1) {
    const [a, b] = [e.path[0], e.path[e.path.length - 1]];
    return [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2];
  }
  return e.at;
}

/** How far the camera should stand from a selection to frame it (m). */
export function frameDistance(sel: Selection | null, entities: EntityMap): number {
  if (!sel || sel.type === 'point') return 600;
  if (sel.type === 'quake') return Math.max(800, -sel.event.hypocenter[2] * 0.3 + 800);
  const e = entities[sel.id];
  if (!e) return 600;
  const p = e.props;
  switch (e.kind) {
    case 'chamber':
      return Math.max(1500, Number(p.radiusM ?? 500) * 6);
    case 'dike': {
      const path = e.path ?? [];
      const span = path.length > 1 ? Math.hypot(...path[0].map((v, k) => v - path[path.length - 1][k])) : 500;
      return Math.max(800, span * 2);
    }
    case 'plume':
      return Math.max(2000, Number(p.heightM ?? 1000) * 2.5);
    case 'fissure':
      return Math.max(500, Number(p.lengthM ?? 200) * 2.5);
    default:
      return 800;
  }
}

/**
 * A section line through the selection: along a dike's or fissure's strike, otherwise west–east,
 * `length` metres long and centred on it.
 */
export function sectionThrough(sel: Selection | null, entities: EntityMap, length: number): XY[] | null {
  const a = selectionAnchor(sel, entities);
  if (!a || !sel) return null;
  let dir: XY = [1, 0];
  if (sel.type === 'entity') {
    const e = entities[sel.id];
    if (e?.kind === 'dike' && e.path && e.path.length > 1) {
      const p0 = e.path[0];
      const p1 = e.path[e.path.length - 1];
      const h = Math.hypot(p1[0] - p0[0], p1[1] - p0[1]);
      if (h > 1) dir = [(p1[0] - p0[0]) / h, (p1[1] - p0[1]) / h];
    } else if (e?.kind === 'fissure' && typeof e.props.strikeDeg === 'number') {
      // the engine's fissure angle runs from +X towards +Z (south): clockwise from east on the map
      const r = (e.props.strikeDeg * Math.PI) / 180;
      dir = [Math.cos(r), -Math.sin(r)];
    }
  }
  const h = length / 2;
  return [
    [a[0] - dir[0] * h, a[1] - dir[1] * h],
    [a[0] + dir[0] * h, a[1] + dir[1] * h],
  ];
}
