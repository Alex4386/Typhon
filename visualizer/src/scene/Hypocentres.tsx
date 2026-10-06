import { useFrame, type ThreeEvent } from '@react-three/fiber';
import { useMemo, useRef } from 'react';
import * as THREE from 'three';
import type { SimEvent } from '../protocol/messages';
import { filterQuakes, quakeFade, QUAKE_COUNT_MAX } from '../store/quakeFilter';
import { simNow, useStore } from '../store/store';
import { sceneProbe } from './Terrain';

type Quake = Extract<SimEvent, { kind: 'seismic' }>;

/** The quake drawn as instance `id` (set while laying out). */
function quakeOf(drawn: Quake[], e: ThreeEvent<PointerEvent | MouseEvent>): Quake | undefined {
  return e.instanceId === undefined ? undefined : drawn[e.instanceId];
}

function quakeLabel(q: Quake): { label: string; detail: string } {
  return { label: `M ${q.magnitude.toFixed(1)} ${q.type} earthquake`, detail: `${Math.round(-q.hypocenter[2])} m below sea level` };
}

const MAX = QUAKE_COUNT_MAX;
/** Re-layout interval (ms): sizes and fading follow the camera, but not every frame. */
const RELAYOUT_MS = 200;
/** A sphere never covers more than this fraction of its distance (≈ 1.7° radius on screen). */
const MAX_ANGULAR = 0.03;
const TYPE_COLOR: Record<string, THREE.Color> = {
  VT: new THREE.Color('#ffd166'),
  LP: new THREE.Color('#06d6a0'),
  TREMOR: new THREE.Color('#118ab2'),
  EXPLOSION: new THREE.Color('#ef476f'),
};

/**
 * Overall opacity from the camera's situation: hypocentres are drawn through the terrain ("x-ray"),
 * which is useful from above or underground but clutters close-up views at the surface (walking a
 * crater rim), so they fade out as the camera nears the ground from above.
 */
export function hypocentreOpacity(heightAboveGround: number, underground: boolean): number {
  if (underground) return 0.7;
  return Math.min(0.6, Math.max(0.06, heightAboveGround / 2500));
}

/** Earthquake hypocentres, seen through the terrain ("x-ray"), sized by magnitude, fading with age. */
export function Hypocentres() {
  const vExag = useStore((s) => s.verticalExaggeration);
  const events = useStore((s) => s.events);
  const ref = useRef<THREE.InstancedMesh>(null);
  const material = useMemo(() => new THREE.MeshBasicMaterial({ transparent: true, opacity: 0.6, depthTest: false, depthWrite: false }), []);
  const filter = useStore((s) => s.quakeFilter);
  const quakes = useMemo(() => events.filter((e): e is Quake => e.kind === 'seismic'), [events]);
  const last = useRef({ at: 0, quakes: null as unknown, vExag: 0, filter: null as unknown, time: Number.NaN });
  const drawn = useRef<Quake[]>([]);
  const m = useMemo(() => new THREE.Matrix4(), []);
  const c = useMemo(() => new THREE.Color(), []);
  const p = useMemo(() => new THREE.Vector3(), []);

  useFrame(({ camera }) => {
    const mesh = ref.current;
    if (!mesh) return;
    const nowMs = performance.now();
    const l = last.current;
    if (l.quakes === quakes && l.vExag === vExag && l.filter === filter && nowMs - l.at < RELAYOUT_MS) return;
    l.at = nowMs;
    l.quakes = quakes;
    l.vExag = vExag;
    l.filter = filter;

    const st = useStore.getState();
    const groundBelow = Number.isFinite(sceneProbe.cameraGround) ? camera.position.y - sceneProbe.cameraGround : 1e4;
    material.opacity = hypocentreOpacity(groundBelow / Math.max(1e-6, vExag), st.underground);

    // the last N as of now (never quakes after a replay cursor), optionally windowed / magnitude-limited
    const now = simNow();
    const shown = filterQuakes(quakes, now, filter);
    let n = 0;
    drawn.current = [];
    for (let rank = 0; rank < shown.length; rank++) {
      const q = shown[rank];
      const age = now - q.time;
      p.set(q.hypocenter[0], q.hypocenter[2] * vExag, -q.hypocenter[1]);
      const dist = p.distanceTo(camera.position);
      // magnitude-sized, but never a screen-filling ball next to the camera
      const r = Math.min(7 * Math.pow(1.8, q.magnitude), Math.max(0.5, dist * MAX_ANGULAR));
      m.makeScale(r, r, r).setPosition(p);
      mesh.setMatrixAt(n, m);
      c.copy(TYPE_COLOR[q.type] ?? TYPE_COLOR.VT).multiplyScalar(quakeFade(filter, age, rank, shown.length));
      mesh.setColorAt(n, c);
      drawn.current[n] = q;
      n++;
    }
    mesh.count = n;
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  });

  return (
    <instancedMesh
      ref={ref}
      args={[undefined, material, MAX]}
      renderOrder={11}
      frustumCulled={false}
      userData={{ quakes: drawn }}
      onClick={(e) => {
        const q = quakeOf(drawn.current, e);
        if (!q || e.delta > 4 || useStore.getState().tool !== 'orbit') return;
        e.stopPropagation();
        useStore.getState().select({ type: 'quake', event: q });
      }}
      onPointerMove={(e) => {
        const q = quakeOf(drawn.current, e);
        if (!q || useStore.getState().tool !== 'orbit') return;
        useStore.getState().set({ hover: { ...quakeLabel(q), x: e.nativeEvent.clientX, y: e.nativeEvent.clientY }, hoverId: null });
      }}
      onPointerOut={() => {
        const h = useStore.getState().hover;
        if (h && / earthquake$/.test(h.label)) useStore.getState().set({ hover: null });
      }}
    >
      <icosahedronGeometry args={[1, 1]} />
    </instancedMesh>
  );
}
