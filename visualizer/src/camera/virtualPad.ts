/**
 * On-screen joysticks for fly and walk on touch screens: written by `TouchSticks`, read by the camera
 * rig every frame like a gamepad. Axes are −1…1 (move: x right, y forward; look: x right, y up);
 * `up` climbs (+1) or sinks (−1) in fly mode.
 */
export const virtualPad = {
  moveX: 0,
  moveY: 0,
  lookX: 0,
  lookY: 0,
  up: 0,
  active: false,
};

/** Stick deflection −1…1 from a drag offset (px) inside a stick of radius `r`, with a small dead zone. */
export function stickAxis(dx: number, dy: number, r: number, dead = 0.12): [number, number] {
  const len = Math.hypot(dx, dy);
  if (len < 1e-6 || r <= 0) return [0, 0];
  const m = Math.min(1, len / r);
  if (m < dead) return [0, 0];
  const k = (m - dead) / (1 - dead) / len;
  return [dx * k, -dy * k];
}
