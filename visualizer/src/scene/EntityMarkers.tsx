import { useFrame, type ThreeEvent } from '@react-three/fiber';
import { useLayoutEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import type { WorldInfo } from '../protocol/messages';
import { entityColor, entityOpacity, isDeposit, KIND_LABEL, pulsePhase, type EntityView, type Selection } from '../store/entities';
import { hiddenByCategory } from '../store/entityTree';
import { filterQuakes } from '../store/quakeFilter';
import { simNow, useStore } from '../store/store';
import { FEATURE_COLORS } from '../util/color';
import { perfStats } from './perf';
export { entitiesAnimating } from './frameMath';
import { SURFACE_KINDS, toScene } from './picking';
import { displayZ } from './Terrain';

/** Marks an object as pickable: clicks on it (or through the ground onto it) select this. */
export interface PickData {
  pick: Selection;
  label: string;
  detail?: string;
  /** Drawn through the ground: picked even when the ground is in front of it. */
  xray?: boolean;
  /**
   * A large see-through volume (a magma chamber): picked through the ground too, but after markers
   * right next to the click, which sit inside its outline from above.
   */
  volume?: boolean;
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

/**
 * Pointer handlers for a pickable object; `get` resolves the pick data of the instance under the
 * pointer (instanced meshes) or returns the object's own.
 */
function handlersFor(get: (instanceId: number | undefined) => PickData | undefined) {
  const reach = (e: ThreeEvent<PointerEvent | MouseEvent>, data: PickData) =>
    !!data.xray || (e.intersections[0]?.object === e.eventObject && e.intersections[0]?.instanceId === e.instanceId);
  return {
    onClick: (e: ThreeEvent<MouseEvent>) => {
      const data = get(e.instanceId);
      if (!data || e.delta > 4 || useStore.getState().tool !== 'orbit' || !reach(e, data)) return;
      e.stopPropagation();
      useStore.getState().select(data.pick);
    },
    onPointerOver: (e: ThreeEvent<PointerEvent>) => {
      const data = get(e.instanceId);
      if (!data || useStore.getState().tool !== 'orbit' || !reach(e, data)) return;
      e.stopPropagation();
      setCursor(e, 'pointer');
      useStore.getState().set({
        hover: { label: data.label, detail: data.detail, x: e.nativeEvent.clientX, y: e.nativeEvent.clientY },
        hoverId: data.pick.type === 'entity' ? data.pick.id : null,
      });
    },
    onPointerMove: (e: ThreeEvent<PointerEvent>) => {
      const data = get(e.instanceId);
      const h = useStore.getState().hover;
      if (data && h && h.label === data.label) useStore.getState().set({ hover: { ...h, x: e.nativeEvent.clientX, y: e.nativeEvent.clientY } });
    },
    onPointerOut: (e: ThreeEvent<PointerEvent>) => {
      setCursor(e, null);
      const data = get(e.instanceId);
      const s = useStore.getState();
      if (data && s.hover?.label === data.label) s.set({ hover: null, hoverId: null });
    },
  };
}

/** Pointer handlers shared by every pickable marker. */
export function pickHandlers(data: PickData) {
  return { userData: { pickData: data }, ...handlersFor(() => data) };
}

/** Pick data of a ray hit: a marker's own, or that of the instance hit on an instanced marker. */
export function pickDataOf(hit: { object: THREE.Object3D; instanceId?: number }): PickData | undefined {
  const u = hit.object.userData;
  if (u.pickData) return u.pickData as PickData;
  if (typeof u.pickAt === 'function' && hit.instanceId !== undefined) return (u.pickAt as (i: number) => PickData | undefined)(hit.instanceId);
  return undefined;
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

function pickOf(e: EntityView): PickData {
  return { pick: { type: 'entity', id: e.id }, label: e.label, detail: detailOf(e), xray: XRAY.has(e.kind) };
}

/** A shown entity with its scene position (computed when entities, tiles or exaggerations change). */
interface Placed {
  e: EntityView;
  pos: THREE.Vector3;
  color: THREE.Color;
}

/** Point-like markers drawn as one instanced mesh per shape. */
interface Shape {
  key: string;
  geometry: THREE.BufferGeometry;
  material: THREE.MeshBasicMaterial;
  /** Lift above the anchor point (scene units, before the marker scale). */
  lift: number;
  renderOrder: number;
  /** Marker scale at camera distance d. */
  scale: (e: EntityView, d: number) => number;
}

function basic(opacity: number, xray = false): THREE.MeshBasicMaterial {
  return new THREE.MeshBasicMaterial({ transparent: true, opacity, depthTest: !xray, depthWrite: !xray && opacity >= 1 });
}

const SHAPES: Shape[] = [
  // ground deposits: flat discs facing up (single-sided: one draw call, not two)
  { key: 'deposit', geometry: new THREE.CircleGeometry(1, 12).rotateX(-Math.PI / 2), material: basic(0.7), lift: 2, renderOrder: 5, scale: (_e, d) => screenScale(d, 0.005, 3) },
  { key: 'feature', geometry: new THREE.OctahedronGeometry(1, 0), material: basic(1), lift: 1.2, renderOrder: 6, scale: (_e, d) => screenScale(d, 0.009, 4) },
  { key: 'station', geometry: new THREE.ConeGeometry(0.6, 2, 4), material: basic(1), lift: 1.5, renderOrder: 6, scale: (_e, d) => screenScale(d, 0.009, 4) },
  { key: 'front', geometry: new THREE.SphereGeometry(1, 16, 10), material: basic(1), lift: 1.2, renderOrder: 6, scale: (_e, d) => screenScale(d, 0.009, 4) },
  { key: 'plume', geometry: new THREE.SphereGeometry(1.2, 16, 10), material: basic(1), lift: 0, renderOrder: 6, scale: (_e, d) => screenScale(d, 0.009, 4) },
  {
    key: 'quake',
    geometry: new THREE.IcosahedronGeometry(1, 1),
    material: basic(0.85, true),
    lift: 0,
    renderOrder: 12,
    scale: (e, d) => screenScale(d, 0.009, 4) * (0.5 + Number(e.props.magnitude ?? 2) * 0.25),
  },
];

function shapeOf(e: EntityView): string | null {
  switch (e.kind) {
    case 'feature':
      return isDeposit(e) ? 'deposit' : 'feature';
    case 'station':
      return 'station';
    case 'lavaFront':
    case 'pdc':
    case 'lahar':
      return 'front';
    case 'plume':
      return 'plume';
    case 'quake':
      return 'quake';
    default:
      return null;
  }
}

/** Radius of the "new" pulse ring / selection halo around a marker of scale k (or kind-specific). */
function ringRadius(e: EntityView, k: number, d: number): number {
  switch (e.kind) {
    case 'vent':
      return Math.max(30, Number(e.props.craterRadiusM ?? 0)) * Math.max(1, screenScale(d, 0.012, 1) / Math.max(30, Number(e.props.craterRadiusM ?? 0)));
    case 'fissure':
      return Math.max(40, Number(e.props.lengthM ?? 0)) / 2;
    case 'dike':
      return screenScale(d, 0.02, 30);
    default:
      return k * 2.2;
  }
}

const tmpM = new THREE.Matrix4();
const tmpC = new THREE.Color();

/** One instanced mesh for all markers of a shape; fades by scale, highlights by colour. */
function InstancedShape({ shape, items, selectedId, hoverId }: { shape: Shape; items: Placed[]; selectedId: string | null; hoverId: string | null }) {
  const ref = useRef<THREE.InstancedMesh>(null);
  const capacity = Math.max(16, 1 << Math.ceil(Math.log2(Math.max(1, items.length))));
  const itemsRef = useRef(items);
  itemsRef.current = items;
  const handlers = useMemo(
    () =>
      handlersFor((i) => {
        const p = i === undefined ? undefined : itemsRef.current[i];
        return p ? pickOf(p.e) : undefined;
      }),
    [],
  );
  const userData = useMemo(() => ({ pickAt: (i: number) => (itemsRef.current[i] ? pickOf(itemsRef.current[i].e) : undefined) }), []);

  useLayoutEffect(() => {
    const mesh = ref.current;
    if (mesh && !mesh.instanceColor) mesh.instanceColor = new THREE.InstancedBufferAttribute(new Float32Array(capacity * 3), 3);
  }, [capacity]);

  useFrame(({ camera }) => {
    const mesh = ref.current;
    if (!mesh) return;
    const now = performance.now();
    const list = itemsRef.current;
    for (let i = 0; i < list.length; i++) {
      const { e, pos, color } = list[i];
      const op = entityOpacity(e, now);
      const d = pos.distanceTo(camera.position);
      const hot = e.id === hoverId || e.id === selectedId;
      const k = shape.scale(e, d) * (hot ? 1.35 : 1) * (0.25 + 0.75 * op) * (op > 0 ? 1 : 0);
      tmpM.makeScale(k, k, k).setPosition(pos.x, pos.y + shape.lift * k, pos.z);
      mesh.setMatrixAt(i, tmpM);
      tmpC.copy(color);
      if (hot) tmpC.lerp(WHITE, 0.35);
      mesh.setColorAt(i, tmpC);
    }
    mesh.count = list.length;
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
    perfStats.entities += list.length;
  });

  return (
    <instancedMesh
      key={capacity}
      ref={ref}
      args={[shape.geometry, shape.material, capacity]}
      renderOrder={shape.renderOrder}
      frustumCulled={false}
      userData={userData}
      {...handlers}
    />
  );
}

/** Vents, fissures and dikes: few, each with its own geometry. */
function ShapedMarker({ p, vExag, selected, hovered }: { p: Placed; vExag: number; selected: boolean; hovered: boolean }) {
  const { e, pos } = p;
  const body = useRef<THREE.Mesh>(null);
  const handlers = useMemo(() => pickHandlers(pickOf(e)), [e]);
  const color = p.color;
  const dike = useMemo(() => {
    if (e.kind !== 'dike' || !e.path || e.path.length < 2) return null;
    const pts = e.path.map((q) => new THREE.Vector3(q[0] - pos.x, q[2] * vExag - pos.y, -q[1] - pos.z));
    const curve = new THREE.CatmullRomCurve3(pts.length === 2 ? [pts[0], pts[0].clone().lerp(pts[1], 0.5), pts[1]] : pts);
    const width = Math.max(10, Math.min(60, Number(e.props.openingM ?? 1) * 8));
    return new THREE.TubeGeometry(curve, 16, width, 6, false);
  }, [e, pos, vExag]);
  useLayoutEffect(() => () => dike?.dispose(), [dike]);
  const fissureLen = Math.max(40, Number(e.props.lengthM ?? 0));
  const strike = (Number(e.props.strikeDeg ?? 0) * Math.PI) / 180;
  const ventR = Math.max(30, Number(e.props.craterRadiusM ?? 0));
  const xray = XRAY.has(e.kind);

  useFrame(({ camera }) => {
    const b = body.current;
    if (!b) return;
    const op = entityOpacity(e, performance.now());
    if (e.kind === 'vent') {
      const k = Math.max(1, screenScale(pos.distanceTo(camera.position), 0.012, 1) / ventR);
      b.scale.setScalar(k * (hovered || selected ? 1.15 : 1));
    }
    const m = b.material as THREE.MeshBasicMaterial;
    m.opacity = op * (xray ? 0.85 : 1);
    m.color.copy(color);
    if (hovered || selected) m.color.lerp(WHITE, 0.35);
  });

  return (
    <group position={pos}>
      {e.kind === 'vent' && (
        <mesh ref={body} rotation={[-Math.PI / 2, 0, 0]} position={[0, 4, 0]} renderOrder={6} {...handlers}>
          <torusGeometry args={[ventR, Math.max(4, ventR * 0.12), 6, 48]} />
          <meshBasicMaterial transparent opacity={0} />
        </mesh>
      )}
      {e.kind === 'fissure' && (
        <mesh ref={body} rotation={[0, -strike, 0]} position={[0, 6, 0]} renderOrder={6} {...handlers}>
          <boxGeometry args={[fissureLen, 10, 18]} />
          <meshBasicMaterial transparent opacity={0} />
        </mesh>
      )}
      {dike && (
        <mesh ref={body} geometry={dike} renderOrder={12} {...handlers}>
          <meshBasicMaterial transparent opacity={0} depthTest={false} depthWrite={false} />
        </mesh>
      )}
    </group>
  );
}

const RING_GEOMETRY = new THREE.RingGeometry(0.92, 1, 48).rotateX(-Math.PI / 2);
/** Additive: fading a ring out is darkening its colour, so instances need no per-instance alpha. */
const RING_MATERIAL = new THREE.MeshBasicMaterial({ transparent: true, depthTest: false, depthWrite: false, blending: THREE.AdditiveBlending, side: THREE.DoubleSide });
const RING_CAPACITY = 256;

/** Expanding "new" rings around entities that appeared while watching, all in one instanced mesh. */
function PulseRings({ items }: { items: Placed[] }) {
  const ref = useRef<THREE.InstancedMesh>(null);
  const itemsRef = useRef(items);
  itemsRef.current = items;
  useLayoutEffect(() => {
    const mesh = ref.current;
    if (mesh && !mesh.instanceColor) mesh.instanceColor = new THREE.InstancedBufferAttribute(new Float32Array(RING_CAPACITY * 3), 3);
  }, []);
  useFrame(({ camera }) => {
    const mesh = ref.current;
    if (!mesh) return;
    const now = performance.now();
    let n = 0;
    for (const p of itemsRef.current) {
      if (n >= RING_CAPACITY) break;
      const phase = pulsePhase(p.e, now);
      if (phase <= 0) continue;
      const d = p.pos.distanceTo(camera.position);
      const shape = shapeOf(p.e);
      const k = shape ? (SHAPES.find((s) => s.key === shape)?.scale(p.e, d) ?? 4) : 1;
      const r = ringRadius(p.e, k, d) * (1 + phase * 2.2);
      tmpM.makeScale(r, r, r).setPosition(p.pos.x, p.pos.y + 2, p.pos.z);
      mesh.setMatrixAt(n, tmpM);
      mesh.setColorAt(n, tmpC.copy(p.color).multiplyScalar((1 - phase) * 0.9 * entityOpacity(p.e, now)));
      n++;
    }
    mesh.count = n;
    mesh.visible = n > 0;
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  });
  return <instancedMesh ref={ref} args={[RING_GEOMETRY, RING_MATERIAL, RING_CAPACITY]} renderOrder={13} frustumCulled={false} raycast={() => null} />;
}

/** The hexagonal halo around the selected entity. */
function SelectionHalo({ placed }: { placed: Placed | null }) {
  const ref = useRef<THREE.Mesh>(null);
  useFrame(({ camera }) => {
    const m = ref.current;
    if (!m) return;
    m.visible = !!placed;
    if (!placed) return;
    const d = placed.pos.distanceTo(camera.position);
    const shape = shapeOf(placed.e);
    const k = shape ? (SHAPES.find((s) => s.key === shape)?.scale(placed.e, d) ?? 4) * 1.35 : 1;
    m.position.set(placed.pos.x, placed.pos.y + 2, placed.pos.z);
    m.scale.setScalar(ringRadius(placed.e, k, d) * 1.5);
  });
  return (
    <mesh ref={ref} rotation={[-Math.PI / 2, 0, 0]} visible={false} renderOrder={14} raycast={() => null}>
      <ringGeometry args={[0.85, 1, 6, 1]} />
      <meshBasicMaterial color="#ffffff" transparent opacity={0.9} depthTest={false} depthWrite={false} side={THREE.DoubleSide} />
    </mesh>
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

/** Placement cache: an entity record keeps its position until tiles or exaggerations change. */
const placements = new WeakMap<EntityView, { p: Placed; key: string }>();

function placedOf(e: EntityView, world: WorldInfo, vExag: number, dExag: number, tiles: number): Placed {
  const key = `${vExag}|${dExag}|${tiles}`;
  const c = placements.get(e);
  if (c && c.key === key) return c.p;
  const p: Placed = { e, pos: placeOf(e, world, vExag, dExag), color: new THREE.Color(entityColor(e, FEATURE_COLORS)) };
  placements.set(e, { p, key });
  return p;
}

/** Scene position of an entity marker. */
function placeOf(e: EntityView, world: WorldInfo, vExag: number, dExag: number): THREE.Vector3 {
  if (SURFACE_KINDS.has(e.kind)) {
    const p = toScene(e.at, vExag, displayZ(world, e.at[0], e.at[1], vExag, dExag));
    return new THREE.Vector3(p[0], p[1], p[2]);
  }
  if (e.kind === 'plume' && typeof e.props.topZ === 'number') return new THREE.Vector3(e.at[0], e.props.topZ * vExag, -e.at[1]);
  const p = toScene(e.at, vExag);
  return new THREE.Vector3(p[0], p[1], p[2]);
}

/**
 * Every entity the server reports (vents, fissures, dikes, springs, flows, stations, quakes, plumes).
 * Point-like markers are instanced per shape (one draw call per shape, however many springs and
 * deposits there are); vents, fissures and dikes keep their own meshes.
 */
export function EntityMarkers({ world }: { world: WorldInfo }) {
  const entities = useStore((s) => s.entities);
  const selection = useStore((s) => s.selection);
  const hoverId = useStore((s) => s.hoverId);
  const vExag = useStore((s) => s.verticalExaggeration);
  const dExag = useStore((s) => s.deformationExaggeration);
  const showFeatures = useStore((s) => s.showFeatures);
  const showHypo = useStore((s) => s.showHypocentres);
  const showAtmosphere = useStore((s) => s.showAtmosphere);
  const hiddenCats = useStore((s) => s.hiddenCategories);
  const quakeFilter = useStore((s) => s.quakeFilter);
  // quake markers follow the clock (last N as of now); re-evaluated with each clock message
  const clockTime = useStore((s) => s.clock?.time ?? 0);
  // ground under surface markers changes as tiles stream in
  const tiles = useStore((s) => Object.keys(s.tileRevision).length);
  const selectedId = selection?.type === 'entity' ? selection.id : null;

  const placed = useMemo(() => {
    const now = simNow();
    const quakes = Object.values(entities)
      .filter((e) => e.kind === 'quake' && !e.hidden)
      .map((e) => ({ e, time: e.createdAt, magnitude: Number(e.props.magnitude ?? 0) }))
      .sort((a, b) => a.time - b.time);
    const quakeShown = new Set(filterQuakes(quakes, Math.max(now, clockTime), quakeFilter).map((q) => q.e.id));
    const out: Placed[] = [];
    for (const e of Object.values(entities)) {
      if (e.hidden || e.kind === 'chamber' || e.kind === 'connection' || e.kind === 'lavaField') continue;
      const selected = e.id === selectedId;
      if (!selected) {
        if (e.kind === 'feature' && !showFeatures) continue;
        if (e.kind === 'quake' && (!showHypo || !quakeShown.has(e.id))) continue;
        if (e.kind === 'plume' && !showAtmosphere) continue;
        if (hiddenByCategory(e, hiddenCats)) continue;
      }
      out.push(placedOf(e, world, vExag, dExag, tiles));
    }
    return out;
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `tiles` re-places surface markers as ground arrives
  }, [entities, selectedId, showFeatures, showHypo, showAtmosphere, hiddenCats, quakeFilter, clockTime, world, vExag, dExag, tiles]);

  const byShape = useMemo(() => {
    const m = new Map<string, Placed[]>(SHAPES.map((s) => [s.key, []]));
    const shaped: Placed[] = [];
    for (const p of placed) {
      const k = shapeOf(p.e);
      if (k) m.get(k)!.push(p);
      else shaped.push(p);
    }
    return { m, shaped };
  }, [placed]);
  const selectedPlaced = useMemo(() => placed.find((p) => p.e.id === selectedId) ?? null, [placed, selectedId]);

  useFrame(() => {
    perfStats.entities = 0;
  }, -999);

  return (
    <group>
      {SHAPES.map((s) => (
        <InstancedShape key={s.key} shape={s} items={byShape.m.get(s.key)!} selectedId={selectedId} hoverId={hoverId} />
      ))}
      {byShape.shaped.map((p) => (
        <ShapedMarker key={p.e.id} p={p} vExag={vExag} selected={p.e.id === selectedId} hovered={p.e.id === hoverId} />
      ))}
      <PulseRings items={placed} />
      <SelectionHalo placed={selectedPlaced} />
      <SelectionMarker world={world} vExag={vExag} dExag={dExag} />
    </group>
  );
}
