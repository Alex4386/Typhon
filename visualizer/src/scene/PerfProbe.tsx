import { useFrame, useThree } from '@react-three/fiber';
import { useEffect, useRef } from 'react';
import { perfStats } from './perf';

interface RenderInfo {
  render?: { calls?: number; drawCalls?: number; triangles?: number };
  memory?: { geometries?: number; textures?: number };
  programs?: unknown[] | null;
}

/**
 * Collects {@link perfStats}: frame rate and time, JS time per frame (update callbacks through the
 * end of rendering) and the renderer's draw statistics. Runs first in every frame.
 */
export function PerfProbe() {
  const gl = useThree((s) => s.gl);
  const scene = useThree((s) => s.scene);
  const frameStart = useRef(0);
  const window1s = useRef({ t0: performance.now(), frames: 0, cpu: 0 });

  useEffect(() => {
    if (new URLSearchParams(window.location.search).has('debug')) (window as unknown as { __typhonScene: unknown }).__typhonScene = scene;
    const prev = scene.onAfterRender;
    scene.onAfterRender = (...args) => {
      prev.apply(scene, args);
      const w = window1s.current;
      w.cpu += performance.now() - frameStart.current;
      const info = (gl as unknown as { info: RenderInfo }).info;
      perfStats.calls = info.render?.calls ?? info.render?.drawCalls ?? 0;
      perfStats.triangles = info.render?.triangles ?? 0;
      perfStats.geometries = info.memory?.geometries ?? 0;
      perfStats.textures = info.memory?.textures ?? 0;
      perfStats.programs = Array.isArray(info.programs) ? info.programs.length : 0;
    };
    return () => {
      scene.onAfterRender = prev;
    };
  }, [gl, scene]);

  useFrame(() => {
    const now = performance.now();
    frameStart.current = now;
    perfStats.frames++;
    const w = window1s.current;
    w.frames++;
    const dt = now - w.t0;
    if (dt >= 1000) {
      perfStats.fps = (w.frames * 1000) / dt;
      perfStats.frameMs = dt / w.frames;
      perfStats.cpuMs = w.cpu / w.frames;
      w.t0 = now;
      w.frames = 0;
      w.cpu = 0;
    }
    perfStats.dpr = gl.getPixelRatio();
  }, -1000);

  return null;
}
