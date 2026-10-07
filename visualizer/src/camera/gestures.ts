/**
 * Touch gestures on the 3D view: pure recogniser math plus a small shared state the canvas listener
 * (TouchGestures) writes and the picking handlers read. OrbitControls already turns one finger into
 * orbit and two fingers into pinch-zoom and pan; this adds two-finger twist (heading), tap vs drag,
 * double-tap focus and long-press, and keeps pinches from selecting things.
 */

export interface Pt {
  x: number;
  y: number;
}

/** Movement (px) under which a press-and-release counts as a tap, per pointer type. */
export function tapSlop(pointerType: string | undefined): number {
  return pointerType === 'touch' ? 12 : pointerType === 'pen' ? 8 : 4;
}

export const TAP_MAX_MS = 400;
export const DOUBLE_TAP_MS = 320;
export const DOUBLE_TAP_PX = 36;
export const LONG_PRESS_MS = 550;

/** Centre, finger distance and angle (rad) of a two-finger contact. */
export function twoFinger(a: Pt, b: Pt): { cx: number; cy: number; dist: number; angle: number } {
  return { cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2, dist: Math.hypot(b.x - a.x, b.y - a.y), angle: Math.atan2(b.y - a.y, b.x - a.x) };
}

/** Wraps an angle difference into (−π, π]. */
export function wrapDelta(a: number): number {
  let d = a % (2 * Math.PI);
  if (d > Math.PI) d -= 2 * Math.PI;
  if (d <= -Math.PI) d += 2 * Math.PI;
  return d;
}

/**
 * Change between two two-finger samples: `scale` > 1 when the fingers spread (zoom in), `pan` of the
 * centre (px), and `twist` (rad, positive = clockwise on screen). A twist below `deadTwist` reads as 0,
 * so a pinch or pan does not also rotate.
 */
export function twoFingerDelta(prev: ReturnType<typeof twoFinger>, next: ReturnType<typeof twoFinger>, deadTwist = 0.02) {
  const twist = wrapDelta(next.angle - prev.angle);
  return {
    scale: prev.dist > 0 ? next.dist / prev.dist : 1,
    panX: next.cx - prev.cx,
    panY: next.cy - prev.cy,
    twist: Math.abs(twist) < deadTwist ? 0 : twist,
  };
}

/** Whether a second tap continues the previous one into a double tap. */
export function isDoubleTap(prev: { t: number; x: number; y: number } | null, t: number, x: number, y: number): boolean {
  return !!prev && t - prev.t <= DOUBLE_TAP_MS && Math.hypot(x - prev.x, y - prev.y) <= DOUBLE_TAP_PX;
}

/** What the canvas listener has seen of the current and last gesture (read by the click handlers). */
export const gesture = {
  /** More than one finger touched during the current press: no tap, no selection. */
  multi: false,
  /** The current press turned into a long-press: its release is not a tap. */
  longPressed: false,
  /** Time of the last press start (ms). */
  downAt: 0,
  /** The last accepted tap, for double-tap detection. */
  lastTap: null as { t: number; x: number; y: number } | null,
};

/** A pointer-up that should not select anything: moved too far, was part of a pinch, or a long-press. */
export function notATap(e: { delta: number; nativeEvent?: object }): boolean {
  // a click is a PointerEvent in current browsers, carrying the pointer type
  const type = (e.nativeEvent as { pointerType?: string } | undefined)?.pointerType;
  if (type === 'touch' && (gesture.multi || gesture.longPressed)) return true;
  return e.delta > tapSlop(type);
}
