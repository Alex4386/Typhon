import { waterUniforms } from './water';
import { Canvas, useFrame, type ThreeEvent } from '@react-three/fiber';
import { useCallback, useEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import { WebGPURenderer } from 'three/webgpu';
import { CameraRig } from '../camera/CameraRig';
import { useCamera } from '../camera/cameraStore';
import { command } from '../net/connection';
import type { SimEvent, WorldInfo, XY } from '../protocol/messages';
import { QUALITY, useStore } from '../store/store';
import { worldExtent } from '../util/world';
import { Atmosphere } from './Atmosphere';
import { SurgeClouds } from './SurgeClouds';
import { EruptionColumn } from './EruptionColumn';
import { Hypocentres } from './Hypocentres';
import { LavaGlow } from './LavaGlow';
import { LavaHalo } from './LavaHalo';
import { SimulatedArea } from './SimulatedArea';
import { EntityMarkers, pickDataOf, type PickData } from './EntityMarkers';
import { FarField } from './FarField';
import { FrameScheduler } from './FrameScheduler';
import { Markers } from './Markers';
import { PerfProbe } from './PerfProbe';
import { Plumbing } from './Plumbing';
import { draftAtClick } from '../panels/builder';
import { nearestSurfaceEntity, pickRadius } from './picking';
import { DetailTerrain } from './DetailTerrain';
import { Terrain, displayZ } from './Terrain';
import { rayGround } from './terrainMath';

const FORCE_WEBGL = new URLSearchParams(window.location.search).get('renderer') === 'webgl';

/** Horizon colour of the CSS sky behind the canvas; the fog fades distant terrain into it. */
export const HORIZON = '#6e7680';

type RendererFactory = (props: { canvas: HTMLCanvasElement | OffscreenCanvas }) => Promise<THREE.WebGLRenderer>;

function configure(r: { toneMapping: THREE.ToneMapping; toneMappingExposure: number; outputColorSpace: string }) {
  r.toneMapping = THREE.ACESFilmicToneMapping;
  r.toneMappingExposure = 1.05;
  r.outputColorSpace = THREE.SRGBColorSpace;
}

/** WebGPURenderer (which itself falls back to a WebGL2 backend), or classic WebGL with ?renderer=webgl. */
const createRenderer: RendererFactory = async (props) => {
  const setName = (renderer: string) => useStore.getState().set({ renderer });
  // Transparent canvas: the sky is a CSS gradient behind it (cheap, identical on both backends).
  if (FORCE_WEBGL) {
    setName('WebGL2 (classic)');
    const r = new THREE.WebGLRenderer({ canvas: props.canvas as HTMLCanvasElement, antialias: true, alpha: true, logarithmicDepthBuffer: true });
    configure(r);
    r.shadowMap.type = THREE.PCFSoftShadowMap;
    return r;
  }
  const r = new WebGPURenderer({ canvas: props.canvas as HTMLCanvasElement, antialias: true, alpha: true, logarithmicDepthBuffer: true });
  await r.init();
  configure(r as unknown as THREE.WebGLRenderer);
  const backend = r.backend as unknown as { isWebGPUBackend?: boolean };
  setName(backend.isWebGPUBackend ? 'WebGPU' : 'WebGL2 (WebGPU fallback)');
  return r as unknown as THREE.WebGLRenderer;
};

/**
 * Placeholder camera before the rig applies its initial pose (URL, last session or summit view):
 * an oblique view of the first volcano from the south-south-west.
 */
function framing(world: WorldInfo, vExag: number) {
  const ext = worldExtent(world);
  const span = Math.max(ext.maxX - ext.minX, ext.maxY - ext.minY);
  const vent = world.volcanoes[0]?.vents[0]?.at ?? [(ext.minX + ext.maxX) / 2, (ext.minY + ext.maxY) / 2];
  const [lo, hi] = world.elevationRange;
  const summit = (lo + 0.85 * (hi - lo)) * vExag;
  const dist = span * 0.42;
  const elevAngle = (32 * Math.PI) / 180;
  const azimuth = (200 * Math.PI) / 180;
  const position: [number, number, number] = [
    vent[0] + Math.sin(azimuth) * Math.cos(elevAngle) * dist,
    summit + Math.sin(elevAngle) * dist,
    -(vent[1] + Math.cos(azimuth) * Math.cos(elevAngle) * dist),
  ];
  return { span, position };
}

/** Key light with its target in the scene graph (so the shadow camera follows it). */
function Sun({ position, target, span, shadows }: { position: [number, number, number]; target: [number, number, number]; span: number; shadows: boolean }) {
  const light = useRef<THREE.DirectionalLight>(null);
  const tgt = useMemo(() => new THREE.Object3D(), []);
  useEffect(() => {
    tgt.position.set(...target);
    tgt.updateMatrixWorld();
    if (light.current) light.current.target = tgt;
  }, [tgt, target]);
  useEffect(() => {
    waterUniforms.uSunDir.value.set(position[0] - target[0], position[1] - target[1], position[2] - target[2]).normalize();
  }, [position, target]);
  useFrame(({ clock }) => {
    waterUniforms.uTime.value = clock.elapsedTime;
  });
  return (
    <>
      <primitive object={tgt} />
      <directionalLight
        ref={light}
        position={position}
        intensity={2.2}
        color="#fff1dc"
        castShadow={shadows}
        shadow-mapSize={[2048, 2048]}
        shadow-bias={-0.0004}
        shadow-normalBias={2}
        shadow-camera-left={-span * 0.6}
        shadow-camera-right={span * 0.6}
        shadow-camera-top={span * 0.6}
        shadow-camera-bottom={-span * 0.6}
        shadow-camera-near={1}
        shadow-camera-far={span * 3}
      />
    </>
  );
}

/**
 * Picking where no ground mesh is (yet): an invisible plane below all terrain catches clicks that hit
 * nothing nearer, and the ground point is found along the view ray from the elevation data. Built tile
 * meshes are always nearer, so they keep the click; this only fills the gaps (tiles still queued for
 * building, ground under open water drawn by the far field).
 */
function PickPlane({ world, onPick }: { world: WorldInfo; onPick: (xy: XY, e: ThreeEvent<MouseEvent>) => void }) {
  const vExag = useStore((s) => s.verticalExaggeration);
  const ext = world.lod?.extent ?? (() => { const e = worldExtent(world); return [e.minX, e.minY, e.maxX, e.maxY]; })();
  const below = (Math.min(world.elevationRange[0], world.seaLevel ?? 0) - 2000) * vExag;
  const w = ext[2] - ext[0];
  const h = ext[3] - ext[1];
  return (
    <mesh
      rotation={[-Math.PI / 2, 0, 0]}
      position={[(ext[0] + ext[2]) / 2, below, -(ext[1] + ext[3]) / 2]}
      onClick={(e) => {
        if (e.delta > 4) return;
        e.stopPropagation();
        const st = useStore.getState();
        const mid = ((world.elevationRange[0] + world.elevationRange[1]) / 2) * st.verticalExaggeration;
        const p = rayGround(e.ray.origin, e.ray.direction, (x, z) => displayZ(world, x, -z, st.verticalExaggeration, st.deformationExaggeration), mid);
        if (p) onPick([p[0], -p[2]], e);
      }}
    >
      <planeGeometry args={[w, h]} />
      <meshBasicMaterial visible={false} />
    </mesh>
  );
}

export function Viewer({ world }: { world: WorldInfo }) {
  const tool = useStore((s) => s.tool);
  const showHypo = useStore((s) => s.showHypocentres);
  const quality = useStore((s) => s.quality);
  const q = QUALITY[quality];
  const { span, position } = framing(world, useStore.getState().verticalExaggeration);
  const camMode = useCamera((c) => c.mode);
  const ext = worldExtent(world);
  const cx = (ext.minX + ext.maxX) / 2;
  const cy = (ext.minY + ext.maxY) / 2;
  const hiZ = world.elevationRange[1] * useStore.getState().verticalExaggeration;

  const onPick = useCallback((xy: XY, e: ThreeEvent<MouseEvent>) => {
    const s = useStore.getState();
    switch (s.tool) {
      case 'orbit': {
        // x-ray markers (dikes, quakes) drawn over the ground win; then a marker next to the click; then a
        // see-through volume the ray passes (the chamber under the summit); else the ground
        let volume: PickData | undefined;
        for (const hit of e.intersections) {
          const data = pickDataOf(hit);
          if (data?.volume) volume ??= data;
          else if (data?.xray) return s.select(data.pick);
          const quakes = hit.object.userData.quakes as { current: Extract<SimEvent, { kind: 'seismic' }>[] } | undefined;
          if (quakes && hit.instanceId !== undefined && quakes.current[hit.instanceId]) return s.select({ type: 'quake', event: quakes.current[hit.instanceId] });
        }
        const near = nearestSurfaceEntity(s.entities, xy, pickRadius(e.distance) / Math.max(1, s.verticalExaggeration * 0.5));
        if (near) return s.select({ type: 'entity', id: near.id });
        s.select(volume ? volume.pick : { type: 'point', at: xy });
        return;
      }
      case 'section':
        s.set({ sectionPolyline: [...s.sectionPolyline, xy] });
        return;
      case 'water':
        command({ kind: 'addWater', at: xy, volumeM3: s.waterVolume, seconds: 600 });
        return;
      case 'dig':
        command({ kind: 'dig', at: xy, radius: s.digRadius, depth: s.digDepth });
        return;
      case 'chamber':
        // Build mode: the click sets (or moves) the chamber draft; its form and ghost follow
        s.set(draftAtClick(s, xy));
        return;
      default:
        return;
    }
  }, []);

  // Low sun from the north-west: long relief shadows read well on volcanic slopes.
  const sun: [number, number, number] = [cx - span * 0.6, hiZ + span * 0.55, -(cy + span * 0.45)];

  return (
    <Canvas
      key={quality /* the renderer's shadow map is fixed at creation */}
      gl={createRenderer as never}
      shadows={q.shadows}
      dpr={q.dpr}
      frameloop="demand"
      camera={{ position, fov: 38, near: 5, far: span * 20 }}
      style={{ cursor: tool !== 'orbit' ? 'crosshair' : camMode === 'fly' || camMode === 'walk' ? 'crosshair' : 'default', touchAction: 'none' }}
    >
      <fog attach="fog" args={[HORIZON, span * 0.9, span * 3.2]} />
      <hemisphereLight args={['#c9d6e8', '#4a3a2c', 0.75]} />
      <ambientLight intensity={0.12} />
      <Sun position={sun} target={[cx, 0, -cy]} span={span} shadows={q.shadows} />
      <group
        onDoubleClick={(e) => {
          e.stopPropagation();
          useCamera.getState().requestCamera({ kind: 'focus', point: [e.point.x, e.point.y, e.point.z] });
        }}
      >
        <Terrain world={world} onPick={onPick} />
        <PickPlane world={world} onPick={onPick} />
        <Plumbing world={world} />
        <DetailTerrain world={world} onPick={onPick} />
      </group>
      <FarField world={world} />
      <LavaGlow world={world} />
      <LavaHalo world={world} />
      <SimulatedArea world={world} />
      <Markers world={world} />
      <EntityMarkers world={world} />
      {showHypo && <Hypocentres />}
      <Atmosphere world={world} />
      <SurgeClouds world={world} />
      <EruptionColumn world={world} />
      <CameraRig world={world} />
      <PerfProbe />
      <FrameScheduler world={world} />
    </Canvas>
  );
}
