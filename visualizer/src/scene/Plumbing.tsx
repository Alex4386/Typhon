import { useFrame, type ThreeEvent } from '@react-three/fiber';
import { useMemo, useRef } from 'react';
import * as THREE from 'three';
import { useCamera } from '../camera/cameraStore';
import { fieldValue, elevationOnVertical, radiusFromVolume, volumeFromRadius } from '../panels/builder';
import type { WorldInfo } from '../protocol/messages';
import type { EntityView } from '../store/entities';
import { useStore } from '../store/store';
import { pickHandlers } from './EntityMarkers';
import { displayZ } from './Terrain';
import { rayGround } from './terrainMath';

const PARTICLES = 14;

/**
 * The magma plumbing beyond the main chamber (drawn in Markers): further chambers, the pathways between
 * them with magma flowing along at a speed that grows with the flow rate, and Build mode's chamber draft
 * with its gizmos (drag to move, Shift-drag for depth, the knob for size). Drawn over the ground so the
 * plumbing stays visible from above and underground.
 */
export function Plumbing({ world }: { world: WorldInfo }) {
  const entities = useStore((s) => s.entities);
  const show = useStore((s) => s.showChambers);
  const vExag = useStore((s) => s.verticalExaggeration);
  const extra = useMemo(() => Object.values(entities).filter((e) => e.removedAt === undefined && e.kind === 'chamber' && e.props.chamberId && e.props.chamberId !== 'main'), [entities]);
  const links = useMemo(() => Object.values(entities).filter((e) => e.removedAt === undefined && e.kind === 'connection' && e.path && e.path.length >= 2), [entities]);
  return (
    <group>
      {show && extra.map((e) => <ExtraChamber key={e.id} e={e} vExag={vExag} />)}
      {show && links.map((e) => <Pathway key={e.id} e={e} vExag={vExag} />)}
      <Draft world={world} vExag={vExag} />
    </group>
  );
}

function ExtraChamber({ e, vExag }: { e: EntityView; vExag: number }) {
  const r = typeof e.props.radiusM === 'number' ? e.props.radiusM : 300;
  const hot = typeof e.props.overpressureMPa === 'number' && typeof e.props.tensileStrengthMPa === 'number' ? Math.min(1, Math.max(0, e.props.overpressureMPa / e.props.tensileStrengthMPa)) : 0.4;
  const lit = useStore((s) => (s.selection?.type === 'entity' && s.selection.id === e.id) || s.hoverId === e.id);
  return (
    <mesh
      position={[e.at[0], e.at[2] * vExag, -e.at[1]]}
      scale={[r, (r / 1.8) * vExag, r]}
      renderOrder={9}
      {...pickHandlers({ pick: { type: 'entity', id: e.id }, label: e.label, detail: typeof e.props.overpressureMPa === 'number' ? `${e.props.overpressureMPa.toFixed(1)} MPa overpressure` : undefined, xray: true, volume: true })}
    >
      <sphereGeometry args={[1, 32, 16]} />
      <meshBasicMaterial color={new THREE.Color().setHSL(0.03 - hot * 0.03, 0.9, 0.42 + hot * 0.1 + (lit ? 0.15 : 0))} transparent opacity={lit ? 0.34 : 0.2} depthTest={false} depthWrite={false} />
    </mesh>
  );
}

/** A pathway: a line between the chambers and magma particles moving along it (faster for more flow). */
function Pathway({ e, vExag }: { e: EntityView; vExag: number }) {
  const path = e.path!;
  const a = useMemo(() => new THREE.Vector3(path[0][0], path[0][2] * vExag, -path[0][1]), [path, vExag]);
  const b = useMemo(() => new THREE.Vector3(path[1][0], path[1][2] * vExag, -path[1][1]), [path, vExag]);
  const line = useMemo(() => new THREE.BufferGeometry().setFromPoints([a, b]), [a, b]);
  const dots = useMemo(() => new THREE.BufferGeometry().setAttribute('position', new THREE.BufferAttribute(new Float32Array(PARTICLES * 3), 3)), []);
  const phase = useRef(0);
  const flow = typeof e.props.flowM3PerS === 'number' ? e.props.flowM3PerS : 0;
  const open = e.props.open !== false && e.props.frozen !== true;
  const lit = useStore((s) => (s.selection?.type === 'entity' && s.selection.id === e.id) || s.hoverId === e.id);
  useFrame((state, dt) => {
    if (!open || !(flow > 0)) return;
    // log-scaled speed: a trickle creeps, a flood races (path fractions per second)
    phase.current = (phase.current + dt * Math.min(0.6, 0.04 + 0.08 * Math.log10(1 + flow * 10))) % 1;
    const p = dots.getAttribute('position') as THREE.BufferAttribute;
    for (let k = 0; k < PARTICLES; k++) {
      const f = (k / PARTICLES + phase.current) % 1;
      p.setXYZ(k, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f);
    }
    p.needsUpdate = true;
    state.invalidate(); // keep on-demand rendering going while magma moves
  });
  const color = !open ? '#6b7280' : flow > 0 ? '#ff7a2a' : '#a3593a';
  return (
    <group>
      <lineSegments
        geometry={line}
        renderOrder={10}
        {...pickHandlers({ pick: { type: 'entity', id: e.id }, label: e.label, detail: open ? `${flow.toPrecision(2)} m³/s` : e.props.frozen ? 'frozen shut' : 'closed', xray: true })}
      >
        <lineBasicMaterial color={color} transparent opacity={lit ? 1 : 0.8} depthTest={false} />
      </lineSegments>
      {open && flow > 0 && (
        <points geometry={dots} renderOrder={11}>
          <pointsMaterial color="#ffb070" size={6} sizeAttenuation={false} transparent opacity={0.95} depthTest={false} />
        </points>
      )}
    </group>
  );
}

/** Build mode's chamber draft: a ghost at its position, depth and size, with drag gizmos. */
function Draft({ world, vExag }: { world: WorldInfo; vExag: number }) {
  const draft = useStore((s) => s.buildDraft);
  const schema = useStore((s) => s.schema);
  const drag = useRef<'move' | 'depth' | 'size' | null>(null);
  if (!draft) return null;
  const specs = draft.volcanoId === null ? schema?.commands.placeChamber : schema?.components?.chamber;
  const depth = fieldValue(draft.values, specs, 'depthM') ?? 3000;
  const volume = fieldValue(draft.values, specs, 'volumeM3') ?? 1e9;
  const groundShown = displayZ(world, draft.at[0], draft.at[1], vExag, useStore.getState().deformationExaggeration);
  const ground = groundShown / vExag;
  const r = radiusFromVolume(volume);
  const cy = (ground - depth) * vExag;
  const centre: [number, number, number] = [draft.at[0], cy, -draft.at[1]];
  const set = (d: Partial<typeof draft>) => {
    const cur = useStore.getState().buildDraft;
    if (cur) useStore.getState().set({ buildDraft: { ...cur, ...d } });
  };
  const clamp = (id: string, v: number) => {
    const p = specs?.find((s) => s.id === id);
    return Math.min(p?.max ?? Infinity, Math.max(p?.min ?? -Infinity, v));
  };
  const start = (mode: 'move' | 'depth' | 'size') => (e: ThreeEvent<PointerEvent>) => {
    e.stopPropagation();
    drag.current = mode === 'move' && e.shiftKey ? 'depth' : mode;
    (e.target as Element).setPointerCapture?.(e.pointerId);
    useCamera.getState().set({ gizmoDrag: true });
  };
  const move = (e: ThreeEvent<PointerEvent>) => {
    if (!drag.current) return;
    e.stopPropagation();
    const st = useStore.getState();
    const ray = e.ray;
    if (drag.current === 'move') {
      const p = rayGround(ray.origin, ray.direction, (x, z) => displayZ(world, x, -z, st.verticalExaggeration, st.deformationExaggeration), groundShown);
      if (p) set({ at: [p[0], -p[2]] });
    } else if (drag.current === 'depth') {
      const z = elevationOnVertical(ray.origin, ray.direction, { x: centre[0], z: centre[2] }, vExag);
      const cur = useStore.getState().buildDraft;
      if (cur) set({ values: { ...cur.values, depthM: Math.round(clamp('depthM', ground - z)) } });
    } else {
      // size: the knob's distance from the centre on the horizontal plane through it
      if (Math.abs(ray.direction.y) < 1e-6) return;
      const t = (cy - ray.origin.y) / ray.direction.y;
      const dx = ray.origin.x + t * ray.direction.x - centre[0];
      const dz = ray.origin.z + t * ray.direction.z - centre[2];
      const cur = useStore.getState().buildDraft;
      if (cur) set({ values: { ...cur.values, volumeM3: clamp('volumeM3', volumeFromRadius(Math.max(10, Math.hypot(dx, dz)))) } });
    }
  };
  const end = (e: ThreeEvent<PointerEvent>) => {
    if (!drag.current) return;
    e.stopPropagation();
    drag.current = null;
    (e.target as Element).releasePointerCapture?.(e.pointerId);
    useCamera.getState().set({ gizmoDrag: false });
  };
  const handlers = { onPointerMove: move, onPointerUp: end, onPointerCancel: end };
  const guide = new THREE.BufferGeometry().setFromPoints([new THREE.Vector3(centre[0], groundShown, centre[2]), new THREE.Vector3(centre[0], cy, centre[2])]);
  return (
    <group>
      <mesh position={centre} scale={[r, (r / 1.8) * vExag, r]} renderOrder={12} onPointerDown={start('move')} {...handlers}>
        <sphereGeometry args={[1, 32, 16]} />
        <meshBasicMaterial color="#ffb347" transparent opacity={0.3} depthTest={false} depthWrite={false} wireframe={false} />
      </mesh>
      <mesh position={centre} scale={[r, (r / 1.8) * vExag, r]} renderOrder={13}>
        <sphereGeometry args={[1, 24, 12]} />
        <meshBasicMaterial color="#ffd27a" wireframe transparent opacity={0.5} depthTest={false} />
      </mesh>
      <lineSegments geometry={guide} renderOrder={12}>
        <lineBasicMaterial color="#ffd27a" transparent opacity={0.8} depthTest={false} />
      </lineSegments>
      {/* the size knob, east of the centre */}
      <mesh position={[centre[0] + r, cy, centre[2]]} renderOrder={14} onPointerDown={start('size')} {...handlers}>
        <sphereGeometry args={[Math.max(world.cellSize * 1.5, r * 0.08), 16, 8]} />
        <meshBasicMaterial color="#ffffff" depthTest={false} />
      </mesh>
      {/* the depth handle at the ground: drag it up or down */}
      <mesh position={[centre[0], groundShown, centre[2]]} renderOrder={14} onPointerDown={start('depth')} {...handlers}>
        <coneGeometry args={[Math.max(world.cellSize * 2, r * 0.1), Math.max(world.cellSize * 4, r * 0.2), 12]} />
        <meshBasicMaterial color="#ffd27a" depthTest={false} />
      </mesh>
    </group>
  );
}
