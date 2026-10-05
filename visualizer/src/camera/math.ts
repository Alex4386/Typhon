/**
 * Camera math shared by the controllers: pure functions, no three.js objects, no allocations in
 * the per-frame helpers (they write into caller-owned arrays).
 *
 * Scene axes (see util/world.ts toScene): x east, y up, z south. Headings are compass bearings
 * (0 = north, π/2 = east); pitch is positive looking up.
 */

export type CameraMode = 'orbit' | 'fly' | 'walk' | 'follow' | 'tour';

export const CAMERA_MODES: readonly CameraMode[] = ['orbit', 'fly', 'walk', 'follow', 'tour'];

export type FollowTarget = 'vent' | 'lavaFront' | 'plumeTop' | 'volcano';

export const FOLLOW_TARGETS: readonly FollowTarget[] = ['vent', 'lavaFront', 'plumeTop', 'volcano'];

/** A camera pose in scene coordinates. `target` is the orbit pivot (orbit/follow/tour modes). */
export interface CameraPose {
  mode: CameraMode;
  position: [number, number, number];
  /** Compass heading of the view direction (rad). */
  heading: number;
  /** Pitch of the view direction (rad, + up). */
  pitch: number;
  target?: [number, number, number];
}

export interface Bookmark {
  name: string;
  pose: CameraPose;
  /** Built-in bookmarks are recomputed per world and not persisted. */
  builtin?: boolean;
}

// ── Direction helpers ──

/** Unit view direction for a heading/pitch, written into `out` (scene axes). */
export function directionOf(heading: number, pitch: number, out: number[]): number[] {
  const c = Math.cos(pitch);
  out[0] = Math.sin(heading) * c; // east
  out[1] = Math.sin(pitch); // up
  out[2] = -Math.cos(heading) * c; // north is −z
  return out;
}

/** Heading/pitch of a (not necessarily unit) scene direction, written into `out` = [heading, pitch]. */
export function anglesOf(dx: number, dy: number, dz: number, out: number[]): number[] {
  const h = Math.hypot(dx, dz);
  out[0] = wrapAngle(Math.atan2(dx, -dz));
  out[1] = Math.atan2(dy, h);
  return out;
}

/** Wraps an angle into [0, 2π). */
export function wrapAngle(a: number): number {
  const t = a % (2 * Math.PI);
  return t < 0 ? t + 2 * Math.PI : t;
}

/** Pitch limit for free-look modes: never quite vertical, so the view never flips. */
export const MAX_PITCH = (89 * Math.PI) / 180;

export function clampPitch(p: number): number {
  return Math.max(-MAX_PITCH, Math.min(MAX_PITCH, p));
}

// ── Speeds and clearance ──

/**
 * Fly speed (scene units/s) at a height above ground: proportional to the height, so moving near
 * the ground is precise and crossing a 10 km world from altitude takes seconds. `multiplier` is the
 * user's wheel/[ ] setting; boost and slow modifiers apply on top.
 */
export function flySpeed(heightAboveGround: number, multiplier: number, boost: boolean, slow: boolean): number {
  const h = Math.max(5, Math.min(40000, Math.abs(heightAboveGround)));
  let v = 0.8 * h * multiplier;
  if (boost) v *= 4;
  if (slow) v *= 0.2;
  return Math.max(0.5, v);
}

/** Walking speed (m/s before vertical exaggeration is irrelevant: walking is horizontal). */
export function walkSpeed(run: boolean, slow: boolean): number {
  return run ? 12 : slow ? 1.4 : 4;
}

/**
 * Keeps a camera height above the ground plus a clearance, unless going underground is allowed.
 * Returns the corrected height.
 */
export function applyClearance(y: number, ground: number, clearance: number, allowUnderground: boolean): number {
  if (allowUnderground) return y;
  const min = ground + clearance;
  return y < min ? min : y;
}

/**
 * Whether a walking step from ground height `from` to `to` over horizontal distance `dist` is
 * allowed by the slope limit (rise/run ≤ tan(maxSlope)); descending is always allowed.
 */
export function stepAllowed(from: number, to: number, dist: number, maxSlopeRad: number): boolean {
  if (to <= from) return true;
  if (dist <= 1e-9) return to - from < 0.01;
  return (to - from) / dist <= Math.tan(maxSlopeRad);
}

/** Exponential smoothing factor for a half-life (s) over a frame of dt (s): frame-rate independent. */
export function dampFactor(halfLife: number, dt: number): number {
  if (halfLife <= 0) return 1;
  return 1 - Math.pow(0.5, dt / halfLife);
}

/** Camera distance at which a sphere of `radius` fills a vertical field of view `fovDeg` (with margin). */
export function frameDistance(radius: number, fovDeg: number, aspect: number, margin = 1.15): number {
  const vHalf = (fovDeg * Math.PI) / 360;
  const hHalf = Math.atan(Math.tan(vHalf) * Math.max(0.1, aspect));
  const half = Math.min(vHalf, hHalf);
  return (radius * margin) / Math.sin(half);
}

/**
 * Near/far planes for the current height above ground and scene size: a small near plane close
 * to the ground (crater rims at 2 m) and a far plane that reaches the whole world from altitude.
 * Writes [near, far] into `out`.
 */
export function clipPlanes(heightAboveGround: number, distanceToTarget: number, sceneSpan: number, out: number[]): number[] {
  const h = Math.abs(heightAboveGround);
  out[0] = Math.max(0.2, Math.min(50, h * 0.02));
  out[1] = Math.max(sceneSpan * 6, distanceToTarget * 6, h * 40);
  return out;
}

/** Pose of an orbit camera at `distance` from `target` looking at it from a heading/elevation. */
export function orbitPose(target: [number, number, number], distance: number, heading: number, elevation: number): CameraPose {
  // the camera sits opposite to its view direction
  const d: number[] = [0, 0, 0];
  directionOf(heading, -elevation, d);
  return {
    mode: 'orbit',
    position: [target[0] - d[0] * distance, target[1] - d[1] * distance, target[2] - d[2] * distance],
    heading: wrapAngle(heading),
    pitch: -elevation,
    target: [target[0], target[1], target[2]],
  };
}

// ── Serialisation (URL and storage) ──

const r1 = (v: number) => Math.round(v * 10) / 10;
const r4 = (v: number) => Math.round(v * 1e4) / 1e4;

/** Compact text form for URLs: `mode,x,y,z,heading,pitch[,tx,ty,tz]` (heading/pitch in rad). */
export function encodePose(p: CameraPose): string {
  const parts: (string | number)[] = [p.mode, r1(p.position[0]), r1(p.position[1]), r1(p.position[2]), r4(p.heading), r4(p.pitch)];
  if (p.target) parts.push(r1(p.target[0]), r1(p.target[1]), r1(p.target[2]));
  return parts.join(',');
}

/** Inverse of {@link encodePose}; null for anything malformed. */
export function decodePose(text: string | null | undefined): CameraPose | null {
  if (!text) return null;
  const parts = text.split(',');
  if (parts.length !== 6 && parts.length !== 9) return null;
  const mode = parts[0] as CameraMode;
  if (!CAMERA_MODES.includes(mode)) return null;
  const n = parts.slice(1).map(Number);
  if (n.some((v) => !Number.isFinite(v))) return null;
  const pose: CameraPose = {
    mode,
    position: [n[0], n[1], n[2]],
    heading: wrapAngle(n[3]),
    pitch: clampPitch(n[4]),
  };
  if (n.length === 8) pose.target = [n[5], n[6], n[7]];
  return pose;
}

/** User bookmarks as JSON (built-ins excluded). */
export function serializeBookmarks(list: Bookmark[]): string {
  return JSON.stringify(list.filter((b) => !b.builtin).map((b) => ({ name: b.name, pose: encodePose(b.pose) })));
}

export function parseBookmarks(json: string | null | undefined): Bookmark[] {
  if (!json) return [];
  try {
    const raw = JSON.parse(json) as unknown;
    if (!Array.isArray(raw)) return [];
    const out: Bookmark[] = [];
    for (const item of raw) {
      if (!item || typeof item !== 'object') continue;
      const { name, pose } = item as { name?: unknown; pose?: unknown };
      const p = typeof pose === 'string' ? decodePose(pose) : null;
      if (typeof name === 'string' && name.length > 0 && p) out.push({ name: name.slice(0, 60), pose: p });
    }
    return out;
  } catch {
    return [];
  }
}

/** Shortest signed difference b − a between two angles, in (−π, π]. */
export function angleDelta(a: number, b: number): number {
  let d = wrapAngle(b) - wrapAngle(a);
  if (d > Math.PI) d -= 2 * Math.PI;
  if (d <= -Math.PI) d += 2 * Math.PI;
  return d;
}

/** Smoothstep easing for transitions. */
export function ease(t: number): number {
  const x = Math.max(0, Math.min(1, t));
  return x * x * (3 - 2 * x);
}

/** Compass label for a heading (8 points). */
export function compassPoint(heading: number): string {
  const points = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];
  return points[Math.round(wrapAngle(heading) / (Math.PI / 4)) % 8];
}
