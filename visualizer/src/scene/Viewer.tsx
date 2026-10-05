import { OrbitControls } from '@react-three/drei';
import { Canvas } from '@react-three/fiber';
import { useCallback } from 'react';
import * as THREE from 'three';
import { WebGPURenderer } from 'three/webgpu';
import { command } from '../net/connection';
import type { WorldInfo, XY } from '../protocol/messages';
import { useStore } from '../store/store';
import { worldExtent } from '../util/world';
import { Atmosphere } from './Atmosphere';
import { Hypocentres } from './Hypocentres';
import { Markers } from './Markers';
import { Terrain } from './Terrain';

const FORCE_WEBGL = new URLSearchParams(window.location.search).get('renderer') === 'webgl';

type RendererFactory = (props: { canvas: HTMLCanvasElement | OffscreenCanvas }) => Promise<THREE.WebGLRenderer>;

/** WebGPURenderer (which itself falls back to a WebGL2 backend), or classic WebGL with ?renderer=webgl. */
const createRenderer: RendererFactory = async (props) => {
  const setName = (renderer: string) => useStore.getState().set({ renderer });
  if (FORCE_WEBGL) {
    setName('WebGL2 (classic)');
    return new THREE.WebGLRenderer({ canvas: props.canvas as HTMLCanvasElement, antialias: true, logarithmicDepthBuffer: true });
  }
  const r = new WebGPURenderer({ canvas: props.canvas as HTMLCanvasElement, antialias: true, logarithmicDepthBuffer: true });
  await r.init();
  const backend = r.backend as unknown as { isWebGPUBackend?: boolean };
  setName(backend.isWebGPUBackend ? 'WebGPU' : 'WebGL2 (WebGPU fallback)');
  return r as unknown as THREE.WebGLRenderer;
};

export function Viewer({ world }: { world: WorldInfo }) {
  const tool = useStore((s) => s.tool);
  const showHypo = useStore((s) => s.showHypocentres);
  const ext = worldExtent(world);
  const cx = (ext.minX + ext.maxX) / 2;
  const cy = (ext.minY + ext.maxY) / 2;
  const span = Math.max(ext.maxX - ext.minX, ext.maxY - ext.minY);
  // Look at the middle of the terrain's elevation range, not the datum: real terrain can sit
  // hundreds of metres above z = 0 (the initial exaggeration is good enough for framing).
  const cz = ((world.elevationRange[0] + world.elevationRange[1]) / 2) * useStore.getState().verticalExaggeration;

  const onPick = useCallback((xy: XY) => {
    const s = useStore.getState();
    switch (s.tool) {
      case 'section':
        s.set({ sectionPolyline: [...s.sectionPolyline, xy] });
        return;
      case 'water':
        command({ kind: 'addWater', at: xy, volumeM3: s.waterVolume, seconds: 600 });
        return;
      case 'dig':
        command({ kind: 'dig', at: xy, radius: s.digRadius, depth: s.digDepth });
        return;
      default:
        return;
    }
  }, []);

  return (
    <Canvas
      gl={createRenderer as never}
      camera={{ position: [cx + span * 0.12, cz + span * 0.8, -(cy - span * 1.2)], fov: 38, near: 5, far: span * 20 }}
      style={{ cursor: tool === 'orbit' ? 'grab' : 'crosshair' }}
    >
      <color attach="background" args={['#0d1117']} />
      <fog attach="fog" args={['#0d1117', span * 1.2, span * 4]} />
      <hemisphereLight args={['#cfd8e6', '#3a3028', 0.9]} />
      <directionalLight position={[-span, span * 0.7, span * 0.4]} intensity={1.4} />
      <Terrain world={world} onPick={onPick} />
      <Markers world={world} />
      {showHypo && <Hypocentres />}
      <Atmosphere world={world} />
      <OrbitControls makeDefault target={[cx, cz, -cy]} maxPolarAngle={Math.PI * 0.495} minDistance={200} maxDistance={span * 4} />
    </Canvas>
  );
}
