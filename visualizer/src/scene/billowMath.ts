/**
 * Light reaching a billow: ambient sky plus sun on the side of the mass facing the sun, so a cloud
 * is bright where the sun hits it and darker in its core and underside (cheap self-shading: the mass
 * shadows its own far side). 0..~1.1.
 */
export function billowLight(dx: number, dy: number, dz: number, sx: number, sy: number, sz: number): number {
  const len = Math.hypot(dx, dy, dz);
  const facing = len > 1e-6 ? (dx * sx + dy * sy + dz * sz) / len : 0;
  // −1 (far side, in shadow) … +1 (facing the sun); the core (len→0) sits in between
  // ambient floor: light scattered through the cloud keeps its shadowed side grey, not black
  return 0.5 + 0.34 * Math.max(-0.4, facing) + 0.2 * Math.max(0, sy);
}
