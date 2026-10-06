import { pulsePhase, type EntityView } from '../store/entities';

/** Whether any entity is pulsing or fading (the scene then keeps animating). */
export function entitiesAnimating(items: Iterable<EntityView>, now: number): boolean {
  for (const e of items) {
    if (e.removedAt !== undefined) return true;
    if (now - e.seenAt < 600) return true;
    if (pulsePhase(e, now) > 0) return true;
  }
  return false;
}

/**
 * Adaptive resolution: compares the frames asked for with the frames rendered. When the scene
 * cannot keep up with its target rate, the pixel ratio steps down (to 0.6 at worst); with headroom
 * it steps back up to the quality preset's maximum.
 */
export function nextDpr(cur: number, maxDpr: number, delivered: number, wanted: number, slowFor: number, fastFor: number): number {
  if (wanted <= 0) return cur;
  if (slowFor >= 3 && delivered < wanted * 0.75) return Math.max(0.6, Math.round((cur - 0.2) * 100) / 100);
  if (fastFor >= 6 && delivered >= wanted * 0.95 && cur < maxDpr) return Math.min(maxDpr, Math.round((cur + 0.1) * 100) / 100);
  return cur;
}

