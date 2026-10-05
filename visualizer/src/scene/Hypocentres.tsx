import { useEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import type { SimEvent } from '../protocol/messages';
import { simNow, useStore } from '../store/store';

const MAX = 3000;
/** How long (simulated seconds) hypocentres stay visible. */
const WINDOW = 3 * 3600;
const TYPE_COLOR: Record<string, THREE.Color> = {
  VT: new THREE.Color('#ffd166'),
  LP: new THREE.Color('#06d6a0'),
  TREMOR: new THREE.Color('#118ab2'),
  EXPLOSION: new THREE.Color('#ef476f'),
};

/** Earthquake hypocentres, seen through the terrain ("x-ray"), sized by magnitude, fading with age. */
export function Hypocentres() {
  const vExag = useStore((s) => s.verticalExaggeration);
  const events = useStore((s) => s.events);
  const ref = useRef<THREE.InstancedMesh>(null);
  const quakes = useMemo(() => events.filter((e): e is Extract<SimEvent, { kind: 'seismic' }> => e.kind === 'seismic').slice(-MAX), [events]);

  useEffect(() => {
    const mesh = ref.current;
    if (!mesh) return;
    const now = simNow();
    const m = new THREE.Matrix4();
    const c = new THREE.Color();
    let n = 0;
    for (const q of quakes) {
      const age = now - q.time;
      if (age > WINDOW) continue;
      const r = 7 * Math.pow(1.8, q.magnitude);
      m.makeScale(r, r, r).setPosition(q.hypocenter[0], q.hypocenter[2] * vExag, -q.hypocenter[1]);
      mesh.setMatrixAt(n, m);
      const fade = 1 - Math.max(0, age) / WINDOW;
      c.copy(TYPE_COLOR[q.type] ?? TYPE_COLOR.VT).multiplyScalar(0.35 + 0.65 * fade);
      mesh.setColorAt(n, c);
      n++;
    }
    mesh.count = n;
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  }, [quakes, vExag]);

  return (
    <instancedMesh ref={ref} args={[undefined, undefined, MAX]} renderOrder={11} frustumCulled={false}>
      <icosahedronGeometry args={[1, 1]} />
      <meshBasicMaterial transparent opacity={0.6} depthTest={false} depthWrite={false} />
    </instancedMesh>
  );
}
