import type { ParamSpec, ParamValue, XY } from '../protocol/messages';

/**
 * A chamber being placed or edited in Build mode: where (x east, y north in world metres, the frame of
 * the Inspector's "E … m · N … m"), and the form's field values (depth, size, magma; server schema ids).
 * `volcanoId` null places a new volcano; otherwise the chamber is added to (or, with `chamberId`, is one
 * of) that volcano's plumbing.
 */
export interface BuildDraft {
  kind: 'chamber';
  volcanoId: string | null;
  /** Set when editing an existing chamber (`main` for a volcano's eruptive one). */
  chamberId?: string;
  at: XY;
  values: Record<string, ParamValue>;
}

/** A pathway being drawn: pick the source, then the target. */
export interface ConnectDraft {
  volcanoId: string;
  from?: string;
  to?: string;
  kind: 'conduit' | 'dike';
  values: Record<string, ParamValue>;
}

/**
 * A map click with the "place chamber" tool: sets (or moves) the chamber draft to the clicked point and
 * opens the Build panel. Without a draft it goes into the selected volcano if the world has one, else it
 * starts a new volcano (the form can switch either way).
 */
export function draftAtClick(
  s: { buildDraft: BuildDraft | null; selectedVolcano: string | null; world: { volcanoes: { id: string }[] } | null },
  xy: XY,
): { buildDraft: BuildDraft; drawer: 'build'; tool: 'orbit' } {
  const draft = s.buildDraft;
  const target = draft?.volcanoId ?? (s.world && s.world.volcanoes.length > 0 ? (s.selectedVolcano ?? s.world.volcanoes[0].id) : null);
  return { buildDraft: draft ? { ...draft, at: xy } : { kind: 'chamber', volcanoId: target, at: xy, values: {} }, drawer: 'build', tool: 'orbit' };
}

/** Radius (m) of a sphere of `volume` m³. */
export function radiusFromVolume(volume: number): number {
  return Math.cbrt((3 * volume) / (4 * Math.PI));
}

/** Volume (m³) of a sphere of `radius` m. */
export function volumeFromRadius(radius: number): number {
  return (4 / 3) * Math.PI * radius ** 3;
}

/** The value of field `id` in a draft, else the schema's default. */
export function fieldValue(values: Record<string, ParamValue>, specs: ParamSpec[] | undefined, id: string): number | undefined {
  const v = values[id];
  if (typeof v === 'number') return v;
  const d = specs?.find((p) => p.id === id)?.default;
  return typeof d === 'number' ? d : undefined;
}

/**
 * Depth below the ground a vertical drag points at: the view ray's closest approach to the vertical line
 * through the chamber at scene point (x, z), in scene coordinates (y up, exaggerated by `vExag`). Returns
 * the chamber-centre elevation (m, unexaggerated) the pointer marks on that line.
 */
export function elevationOnVertical(
  origin: { x: number; y: number; z: number },
  dir: { x: number; y: number; z: number },
  line: { x: number; z: number },
  vExag: number,
): number {
  // closest point between the ray o + t·d and the vertical line (x, s, z): minimise the horizontal distance
  const dx = origin.x - line.x;
  const dz = origin.z - line.z;
  const h = dir.x * dir.x + dir.z * dir.z;
  const t = h > 1e-12 ? -(dx * dir.x + dz * dir.z) / h : 0;
  const y = origin.y + Math.max(0, t) * dir.y;
  return y / vExag;
}

/** Readouts of a chamber position: depth below the surface and below (or above) sea level, distance from an origin. */
export function positionReadout(at: XY, depthM: number, groundZ: number, seaLevel: number | undefined, origin?: XY) {
  const centreZ = groundZ - depthM;
  const belowSea = seaLevel !== undefined && Number.isFinite(seaLevel) ? seaLevel - centreZ : undefined;
  const distance = origin ? Math.hypot(at[0] - origin[0], at[1] - origin[1]) : undefined;
  return { centreZ, belowSea, distance };
}

/**
 * One undoable builder edit: the volcano's definition before and after (`null` = it did not exist), as
 * the server's `config` trees. Undo and redo send them back through the configuration API, which
 * classifies them like any other change.
 */
export interface BuildStep {
  label: string;
  volcanoId: string;
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
}

export interface BuildHistory {
  undo: BuildStep[];
  redo: BuildStep[];
}

export const EMPTY_HISTORY: BuildHistory = { undo: [], redo: [] };

/** A new edit: it can be undone; anything undone before it can no longer be redone. */
export function pushStep(h: BuildHistory, step: BuildStep, cap = 50): BuildHistory {
  return { undo: [...h.undo, step].slice(-cap), redo: [] };
}

/** Moves the last edit to the redo stack; returns it to apply its `before`. */
export function undoStep(h: BuildHistory): { history: BuildHistory; step: BuildStep } | null {
  const step = h.undo.at(-1);
  if (!step) return null;
  return { history: { undo: h.undo.slice(0, -1), redo: [...h.redo, step] }, step };
}

/** Moves the last undone edit back; returns it to apply its `after`. */
export function redoStep(h: BuildHistory): { history: BuildHistory; step: BuildStep } | null {
  const step = h.redo.at(-1);
  if (!step) return null;
  return { history: { undo: [...h.undo, step], redo: h.redo.slice(0, -1) }, step };
}

/**
 * The configuration-API change that restores a volcano to `tree` (its magma plumbing and main chamber):
 * `null` removes the volcano; a missing one is added whole; an existing one gets its `magma` section
 * back, with absent plumbing lists cleared.
 */
export function restoreChange(tree: Record<string, unknown> | null, exists: boolean): Record<string, unknown> | null {
  if (tree === null) return null;
  if (!exists) return tree;
  const magma = { ...((tree.magma as Record<string, unknown>) ?? {}) };
  if (!('chambers' in magma)) magma.chambers = [];
  if (!('connections' in magma)) magma.connections = [];
  return { magma };
}
