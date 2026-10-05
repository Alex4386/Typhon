import { useFrame, type ThreeEvent } from '@react-three/fiber';
import { useMemo, useRef } from 'react';
import * as THREE from 'three';
import type { WorldInfo } from '../protocol/messages';
import { entityColor, entityOpacity, isDeposit, KIND_LABEL, pulsePhase, type EntityView, type Selection } from '../store/entities';
import { useStore } from '../store/store';
import { FEATURE_COLORS } from '../util/color';
import { SURFACE_KINDS, toScene } from './picking';
import { displayZ } from './Terrain';

/** Marks an object as pickable: clicks on it (or through the ground onto it) select this. */
export interface PickData {
  pick: Selection;
  label: string;
  detail?: string;
  /** Drawn through the ground: picked even when the ground is in front of it. */
  xray?: boolean;
}

/** Whether a pointer event should go to this marker: x-ray markers always, others only when nothing is in front. */
function reachable(e: ThreeEvent<PointerEvent | MouseEvent>, data: PickData): boolean {
  return !!data.xray || e.intersections[0]?.object === e.eventObject;
}

/** Kinds drawn through the ground (x-ray) so they can be seen and picked from above. */
const XRAY = new Set(['dike', 'quake']);

const WHITE = new THREE.Color('#ffffff');

/** Screen-size-ish scale: about `angular` of the distance, never below `min` metres. */
function screenScale(d: number, angular: number, min: number): number {
  return Math.max(min, d * angular);
}

function setCursor(e: ThreeEvent<PointerEvent>, cursor: string | null) {
  const el = e.nativeEvent.target as HTMLElement | null;
  if (el && el.tagName === 'CANVAS') el.style.cursor = cursor ?? '';
}

/** Pointer handlers shared by every pickable marker. */
export function pickHandlers(data: PickData) {
  return {
    userData: { pickData: data },
    onClick: (e: ThreeEvent<MouseEvent>) => {
      if (e.delta > 4 || useStore.getState().tool !== 'orbit' || !reachable(e, data)) return;
      e.stopPropagation();
      useStore.getState().select(data.pick);
    },
    onPointerOver: (e: ThreeEvent<PointerEvent>) => {
      if (useStore.getState().tool !== 'orbit' || !reachable(e, data)) return;
      e.stopPropagation();
      setCursor(e, 'pointer');
      useStore.getState().set({
        hover: { label: data.label, detail: data.detail, x: e.nativeEvent.clientX, y: e.nativeEvent.clientY },
        hoverId: data.pick.type === 'entity' ? data.pick.id : null,
      });
    },
    onPointerMove: (e: ThreeEvent<PointerEvent>) => {
      const h = useStore.getState().hover;
      if (h && h.label === data.label) useStore.getState().set({ hover: { ...h, x: e.nativeEvent.clientX, y: e.nativeEvent.clientY } });
    },
    onPointerOut: (e: ThreeEvent<PointerEvent>) => {
      setCursor(e, null);
      const s = useStore.getState();
      if (s.hover?.label === data.label) s.set({ hover: null, hoverId: null });
    },
  };
}

function detailOf(e: EntityView): string {
  const p = e.props;
  const kind = KIND_LABEL[e.kind] ?? e.kind;
  switch (e.kind) {
    case 'feature':
      return typeof p.groundTemperatureC === 'number' ? `${p.groundTemperatureC} °C ground` : kind;
    case 'dike':
      return `${String(p.status ?? '').toLowerCase()} · tip ${Math.round(Number(p.tipDepthM ?? 0))} m deep`;
    case 'quake':
      return `M ${Number(p.magnitude).toFixed(1)} ${String(p.type ?? '')}`;
    case 'vent':
    case 'fissure':
      return `${kind}${p.erupting ? ' · erupting' : ''}`;
    default:
      return kind;
  }
}

/** One entity: geometry by kind, fades in and out, pulses while new, highlights on hover/selection. */
function EntityMarker({ e, world, vExag, dExag, selected, hovered }: { e: EntityView; world: WorldInfo; vExag: number; dExag: number; selected: boolean; hovered: boolean }) {
  const group = useRef<THREE.Group>(null);
  const body = useRef<THREE.Mesh>(null);
  const ring = useRef<THREE.Mesh>(null);
  const halo = useRef<THREE.Mesh>(null);
  const color = entityColor(e, FEATURE_COLORS);
  const surface = SURFACE_KINDS.has(e.kind);
  const xray = XRAY.has(e.kind);
  const deposit = isDeposit(e);
  const handlers = useMemo(() => pickHandlers({ pick: { type: 'entity', id: e.id }, label: e.label, detail: detailOf(e), xray: XRAY.has(e.kind) }), [e]);

  const pos = useMemo((): [number, number, number] => {
    if (surface) return toScene(e.at, vExag, displayZ(world, e.at[0], e.at[1], vExag, dExag));
    if (e.kind === 'plume' && typeof e.props.topZ === 'number') return [e.at[0], e.props.topZ * vExag, -e.at[1]];
    return toScene(e.at, vExag);
  }, [e, surface, world, vExag, dExag]);

  // dikes: a tube from the origin to the tip, in group-local coordinates
  const dike = useMemo(() => {
    if (e.kind !== 'dike' || !e.path || e.path.length < 2) return null;
    const pts = e.path.map((p) => new THREE.Vector3(p[0] - pos[0], p[2] * vExag - pos[1], -p[1] - pos[2]));
    const curve = new THREE.CatmullRomCurve3(pts.length === 2 ? [pts[0], pts[0].clone().lerp(pts[1], 0.5), pts[1]] : pts);
    const width = Math.max(10, Math.min(60, Number(e.props.openingM ?? 1) * 8));
    return new THREE.TubeGeometry(curve, 16, width, 6, false);
  }, [e, pos, vExag]);

  const fissureLen = Math.max(40, Number(e.props.lengthM ?? 0));
  const strike = (Number(e.props.strikeDeg ?? 0) * Math.PI) / 180;
  const ventR = Math.max(30, Number(e.props.craterRadiusM ?? 0));

  useFrame(({ camera }) => {
    const g = group.current;
    if (!g) return;
    const now = performance.now();
    const op = entityOpacity(e, now);
    const d = g.position.distanceTo(camera.position);
    // point-like markers keep a readable size on screen
    const k =
      e.kind === 'vent' ? Math.max(1, screenScale(d, 0.012, 1) / ventR) : e.kind === 'fissure' || e.kind === 'dike' ? 1 : deposit ? screenScale(d, 0.005, 3) : screenScale(d, 0.009, 4);
    const grow = hovered || selected ? 1.35 : 1;
    if (body.current) {
      if (e.kind !== 'dike' && e.kind !== 'fissure' && e.kind !== 'vent') body.current.scale.setScalar(k * grow);
      else if (e.kind === 'vent') body.current.scale.setScalar(k * (hovered || selected ? 1.15 : 1));
      const m = body.current.material as THREE.MeshBasicMaterial;
      m.opacity = op * (xray ? 0.85 : deposit && !hovered && !selected ? 0.7 : 1);
      if (hovered || selected) m.color.set(color).lerp(WHITE, 0.35);
      else m.color.set(color);
    }
    const ringScale = e.kind === 'vent' ? ventR * k : e.kind === 'fissure' ? fissureLen / 2 : e.kind === 'dike' ? screenScale(d, 0.02, 30) : k * 2.2;
    const phase = deposit ? 0 : pulsePhase(e, now);
    if (ring.current) {
      ring.current.visible = phase > 0;
      ring.current.scale.setScalar(ringScale * (1 + phase * 2.2));
      (ring.current.material as THREE.MeshBasicMaterial).opacity = (1 - phase) * 0.9 * op;
    }
    if (halo.current) {
      halo.current.visible = selected;
      halo.current.scale.setScalar(ringScale * 1.5);
      halo.current.rotation.z += 0.01;
    }
  });

  return (
    <group ref={group} position={pos}>
      {e.kind === 'feature' && deposit && (
        <mesh ref={body} rotation={[-Math.PI / 2, 0, 0]} position={[0, 2, 0]} renderOrder={5} {...handlers}>
          <circleGeometry args={[1, 12]} />
          <meshBasicMaterial color={color} transparent opacity={0} side={THREE.DoubleSide} />
        </mesh>
      )}
      {e.kind === 'feature' && !deposit && (
        <mesh ref={body} position={[0, 1.2, 0]} renderOrder={6} {...handlers}>
          <octahedronGeometry args={[1, 0]} />
          <meshBasicMaterial color={color} transparent opacity={0} />
        </mesh>
      )}
      {e.kind === 'vent' && (
        <mesh ref={body} rotation={[-Math.PI / 2, 0, 0]} position={[0, 4, 0]} renderOrder={6} {...handlers}>
          <torusGeometry args={[ventR, Math.max(4, ventR * 0.12), 6, 48]} />
          <meshBasicMaterial color={color} transparent opacity={0} />
        </mesh>
      )}
      {e.kind === 'fissure' && (
        <mesh ref={body} rotation={[0, -strike, 0]} position={[0, 6, 0]} renderOrder={6} {...handlers}>
          <boxGeometry args={[fissureLen, 10, 18]} />
          <meshBasicMaterial color={color} transparent opacity={0} />
        </mesh>
      )}
      {e.kind === 'station' && (
        <mesh ref={body} position={[0, 1.5, 0]} renderOrder={6} {...handlers}>
          <coneGeometry args={[0.6, 2, 4]} />
          <meshBasicMaterial color={color} transparent opacity={0} />
        </mesh>
      )}
      {(e.kind === 'lavaFront' || e.kind === 'pdc' || e.kind === 'lahar') && (
        <mesh ref={body} position={[0, 1.2, 0]} renderOrder={6} {...handlers}>
          <sphereGeometry args={[1, 16, 10]} />
          <meshBasicMaterial color={color} transparent opacity={0} />
        </mesh>
      )}
      {e.kind === 'quake' && (
        <mesh ref={body} renderOrder={12} {...handlers}>
          <icosahedronGeometry args={[0.5 + Number(e.props.magnitude ?? 2) * 0.25, 1]} />
          <meshBasicMaterial color={color} transparent opacity={0} depthTest={false} depthWrite={false} />
        </mesh>
      )}
      {e.kind === 'plume' && (
        <mesh ref={body} renderOrder={6} {...handlers}>
          <sphereGeometry args={[1.2, 16, 10]} />
          <meshBasicMaterial color={color} transparent opacity={0} />
        </mesh>
      )}
      {dike && (
        <mesh ref={body} geometry={dike} renderOrder={12} {...handlers}>
          <meshBasicMaterial color={color} transparent opacity={0} depthTest={false} depthWrite={false} />
        </mesh>
      )}
      <mesh ref={ring} rotation={[-Math.PI / 2, 0, 0]} visible={false} renderOrder={13}>
        <ringGeometry args={[0.92, 1, 48]} />
        <meshBasicMaterial color={color} transparent opacity={0} depthTest={false} depthWrite={false} side={THREE.DoubleSide} />
      </mesh>
      <mesh ref={halo} rotation={[-Math.PI / 2, 0, 0]} visible={false} renderOrder={14}>
        <ringGeometry args={[0.85, 1, 6, 1]} />
        <meshBasicMaterial color="#ffffff" transparent opacity={0.9} depthTest={false} depthWrite={false} side={THREE.DoubleSide} />
      </mesh>
    </group>
  );
}

/** A pin at a selected ground point or a ring around a selected quake. */
function SelectionMarker({ world, vExag, dExag }: { world: WorldInfo; vExag: number; dExag: number }) {
  const sel = useStore((s) => s.selection);
  const ref = useRef<THREE.Group>(null);
  useFrame(({ camera }) => {
    const g = ref.current;
    if (!g) return;
    const d = g.position.distanceTo(camera.position);
    g.scale.setScalar(Math.max(4, d * 0.012));
    g.rotation.y += 0.01;
  });
  if (!sel || sel.type === 'entity') return null;
  const at: [number, number, number] =
    sel.type === 'point' ? [sel.at[0], displayZ(world, sel.at[0], sel.at[1], vExag, dExag), -sel.at[1]] : toScene(sel.event.hypocenter, vExag);
  return (
    <group ref={ref} position={at}>
      {sel.type === 'point' && (
        <mesh position={[0, 1.5, 0]} renderOrder={14}>
          <coneGeometry args={[0.5, 3, 12]} />
          <meshBasicMaterial color="#ffe14d" transparent opacity={0.95} depthTest={false} />
        </mesh>
      )}
      <mesh rotation={[-Math.PI / 2, 0, 0]} renderOrder={14}>
        <ringGeometry args={[1.6, 2, 6, 1]} />
        <meshBasicMaterial color={sel.type === 'point' ? '#ffe14d' : '#ffffff'} transparent opacity={0.9} depthTest={false} depthWrite={false} side={THREE.DoubleSide} />
      </mesh>
    </group>
  );
}

/** Every entity the server reports (vents, fissures, dikes, springs, flows, stations, quakes, plumes). */
export function EntityMarkers({ world }: { world: WorldInfo }) {
  const entities = useStore((s) => s.entities);
  const selection = useStore((s) => s.selection);
  const hoverId = useStore((s) => s.hoverId);
  const vExag = useStore((s) => s.verticalExaggeration);
  const dExag = useStore((s) => s.deformationExaggeration);
  const showFeatures = useStore((s) => s.showFeatures);
  const showHypo = useStore((s) => s.showHypocentres);
  const showAtmosphere = useStore((s) => s.showAtmosphere);
  // ground under surface markers changes as tiles stream in
  useStore((s) => Object.keys(s.tileRevision).length);
  const selectedId = selection?.type === 'entity' ? selection.id : null;
  const shown = Object.values(entities).filter((e) => {
    if (e.hidden || e.kind === 'chamber' || e.kind === 'lavaField') return false;
    if (e.kind === 'feature' && !showFeatures) return e.id === selectedId;
    if (e.kind === 'quake' && !showHypo) return e.id === selectedId;
    if (e.kind === 'plume' && !showAtmosphere) return e.id === selectedId;
    return true;
  });
  return (
    <group>
      {shown.map((e) => (
        <EntityMarker key={e.id} e={e} world={world} vExag={vExag} dExag={dExag} selected={e.id === selectedId} hovered={e.id === hoverId} />
      ))}
      <SelectionMarker world={world} vExag={vExag} dExag={dExag} />
    </group>
  );
}
