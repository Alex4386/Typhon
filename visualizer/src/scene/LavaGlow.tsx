import { useFrame } from '@react-three/fiber';
import { useRef } from 'react';
import * as THREE from 'three';
import type { WorldInfo } from '../protocol/messages';
import { useStore } from '../store/store';
import { displayZ } from './Terrain';

const MAX_LIGHTS = 4;

/**
 * Orange light thrown by erupting vents onto their surroundings (fountains, lava lakes, domes).
 * Point lights rather than post-processing bloom, so it works the same on WebGPU and WebGL and stays
 * cheap on integrated GPUs. Intensity follows the eruption rate (log scale) with a slow flicker.
 */
export function LavaGlow({ world }: { world: WorldInfo }) {
  const lights = useRef<(THREE.PointLight | null)[]>([]);
  useFrame(() => {
    const st = useStore.getState();
    const vExag = st.verticalExaggeration;
    const dExag = st.deformationExaggeration;
    const wall = performance.now() / 1000;
    let n = 0;
    for (const v of world.volcanoes) {
      const vs = st.state?.volcanoes[v.id];
      const rate = vs?.chamber.eruptionRate ?? 0;
      if (!(rate > 0)) continue;
      const explosive = vs?.chamber.regime === 'EXPLOSIVE';
      const active = vs?.activeVents ? new Set(vs.activeVents) : null;
      for (const vent of v.vents) {
        if (n >= MAX_LIGHTS) break;
        if (active && !active.has(vent.id)) continue;
        const light = lights.current[n++];
        if (!light) continue;
        const z = displayZ(world, vent.at[0], vent.at[1], vExag, dExag);
        light.position.set(vent.at[0], z + 60 * vExag, -vent.at[1]);
        const flicker = 0.85 + 0.15 * Math.sin(wall * 7.3 + n) * Math.sin(wall * 3.1 + 2 * n);
        // a local glow on the crater and nearby flows, not a floodlight on the whole edifice
        const base = Math.min(1.4, 0.35 + Math.log10(1 + rate) * 0.35);
        light.intensity = base * flicker * (explosive ? 0.5 : 1);
        // a fissure glows along its whole length
        const halfLength = vent.line ? Math.hypot(vent.line[1][0] - vent.line[0][0], vent.line[1][1] - vent.line[0][1]) / 2 : 0;
        light.distance = 700 + Math.log10(1 + rate) * 300 + halfLength;
        light.visible = st.showAtmosphere;
      }
    }
    for (let k = n; k < MAX_LIGHTS; k++) {
      const light = lights.current[k];
      if (light) light.visible = false;
    }
  });
  return (
    <group>
      {Array.from({ length: MAX_LIGHTS }, (_, k) => (
        <pointLight
          key={k}
          ref={(l) => {
            lights.current[k] = l;
          }}
          color="#ff6a1a"
          decay={0}
          visible={false}
        />
      ))}
    </group>
  );
}
