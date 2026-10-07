import type { SimEvent, VolcanoInfo, WorldInfo, XY } from '../protocol/messages';

/**
 * Where a volcano meets the surface: its first vent, or, before magma has reached the surface anywhere
 * (a placed chamber has no vent), the point above its chamber.
 */
export function volcanoAnchor(v: VolcanoInfo): XY {
  return v.vents[0]?.at ?? [v.chamber.center[0], v.chamber.center[1]];
}

/**
 * The world's vent list with the fissures dikes opened and the vents fissures localised into since it
 * was sent: the attach-time `world` message lists only the vents that existed then, while fountains and
 * glow read this list. Returns the same object when nothing changes.
 */
export function withOpenedFissures(world: WorldInfo | null, events: SimEvent[]): WorldInfo | null {
  if (!world) return world;
  let out = world;
  for (const e of events) {
    if (e.kind !== 'fissureOpened' && e.kind !== 'ventFormed') continue;
    const i = out.volcanoes.findIndex((v) => v.id === e.volcanoId);
    if (i < 0) continue;
    const v = out.volcanoes[i];
    const vents = v.vents.filter((x) => x.id !== e.vent.id).concat([e.vent]);
    const volcanoes = out.volcanoes.slice();
    volcanoes[i] = { ...v, vents };
    out = { ...out, volcanoes };
  }
  return out;
}
