import { useFrame } from '@react-three/fiber';
import { useMemo, useRef } from 'react';
import * as THREE from 'three';
import type { WorldInfo } from '../protocol/messages';
import { useStore } from '../store/store';
import { simulatedOutline } from '../util/extent';
import { displayZ } from './Terrain';

/** Rebuild at most this often (ms): the outline only changes when the area grows or tiles stream in. */
const REBUILD_MS = 1000;

/**
 * Outline of the simulated ground, draped over the terrain: inside it the model runs, outside the
 * landscape is generated and becomes simulated when lava, flows, thick ash or running water reach it.
 * Rebuilt when the area grows, tiles arrive or the exaggerations change.
 */
export function SimulatedArea({ world }: { world: WorldInfo }) {
  const show = useStore((s) => s.showSimulatedArea);
  const outline = useMemo(() => simulatedOutline(world), [world]);
  const geometry = useMemo(() => new THREE.BufferGeometry(), []);
  const material = useMemo(
    () => new THREE.LineBasicMaterial({ color: '#ffb547', transparent: true, opacity: 0.85, depthTest: true, toneMapped: false }),
    [],
  );
  const last = useRef({ at: 0, rev: null as unknown, vExag: 0, dExag: 0, outline: null as unknown });

  useFrame(({ invalidate }) => {
    if (!show) return;
    const st = useStore.getState();
    const l = last.current;
    const now = performance.now();
    const same = l.outline === outline && l.rev === st.tileRevision && l.vExag === st.verticalExaggeration
      && l.dExag === st.deformationExaggeration;
    if (same || (l.outline === outline && now - l.at < REBUILD_MS)) return;
    l.at = now;
    l.rev = st.tileRevision;
    l.outline = outline;
    const vExag = (l.vExag = st.verticalExaggeration);
    const dExag = (l.dExag = st.deformationExaggeration);
    const step = world.cellSize * 4;
    const lift = 3 * vExag;
    const points: number[] = [];
    for (const [x0, y0, x1, y1] of outline) {
      const n = Math.max(1, Math.ceil(Math.hypot(x1 - x0, y1 - y0) / step));
      let px = x0;
      let py = y0;
      let pz = displayZ(world, px, py, vExag, dExag) + lift;
      for (let k = 1; k <= n; k++) {
        const x = x0 + ((x1 - x0) * k) / n;
        const y = y0 + ((y1 - y0) * k) / n;
        const z = displayZ(world, x, y, vExag, dExag) + lift;
        // scene axes: x east, y up, z south
        points.push(px, pz, -py, x, z, -y);
        px = x;
        py = y;
        pz = z;
      }
    }
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(points, 3));
    geometry.computeBoundingSphere();
    invalidate();
  });

  if (!show || outline.length === 0) return null;
  return <lineSegments geometry={geometry} material={material} renderOrder={5} frustumCulled={false} raycast={() => null} />;
}
