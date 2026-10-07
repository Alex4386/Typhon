import type { EruptiveRegime, VentInfo, VolcanoState, WorldInfo } from '../protocol/messages';

/** A vent throwing up lava: where, how high (m, unexaggerated) and its share of the particles. */
export interface FountainSource {
  vent: VentInfo;
  heightM: number;
  weight: number;
}

/** Regimes in which an erupting fissure shows a curtain of fire (fountaining, or lower while effusing). */
const CURTAIN: Partial<Record<EruptiveRegime, number>> = { FOUNTAINING: 1, SURTSEYAN: 0.5, EFFUSIVE: 0.3, OPEN_VENT: 0.3 };

/** Fissure length (m), 0 for craters. */
export function fissureLength(v: VentInfo): number {
  return v.line ? Math.hypot(v.line[1][0] - v.line[0][0], v.line[1][1] - v.line[0][1]) : 0;
}

/**
 * The fountains to draw now. Hawaiian fountains reached ~50–500 m at 10–500 m³/s (Kīlauea), so the full
 * height is 25·√rate (≤ 550 m). Craters fountain only in the fountaining regime; erupting fissures keep a
 * curtain of fire along their length through effusive phases too, lower. Only the vents the server reports
 * erupting are used (all of the volcano's vents when it does not say). A fissure's share of particles
 * grows with its length (one crater counts as 100 m). A vent under the sea throws no fire into the air:
 * the water quenches the magma at the vent (what rises above a shallow one is steam and dark tephra).
 */
export function fountainSources(world: WorldInfo, volcanoes: Record<string, VolcanoState | undefined>): FountainSource[] {
  const out: FountainSource[] = [];
  for (const v of world.volcanoes) {
    const vs = volcanoes[v.id];
    const rate = vs?.chamber.eruptionRate ?? 0;
    if (!(rate > 0) || !vs) continue;
    const full = Math.min(550, 25 * Math.sqrt(rate));
    const active = vs.activeVents ? new Set(vs.activeVents) : null;
    const sea = world.hasSea !== false && Number.isFinite(world.seaLevel) ? world.seaLevel : -Infinity;
    for (const vent of v.vents) {
      if (active && !active.has(vent.id)) continue;
      if (vent.z < sea) continue;
      if (vent.kind === 'fissure' && vent.line) {
        const k = CURTAIN[vs.chamber.regime];
        if (k) out.push({ vent, heightM: Math.max(15, full * k), weight: Math.max(1, fissureLength(vent) / 100) });
      } else if (vs.chamber.regime === 'FOUNTAINING') {
        out.push({ vent, heightM: full, weight: 1 });
      }
    }
  }
  return out;
}
