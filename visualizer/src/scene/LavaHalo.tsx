import { useFrame } from '@react-three/fiber';
import { useLayoutEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import { Field } from '../protocol/fields';
import type { WorldInfo } from '../protocol/messages';
import { QUALITY, getTile, useStore } from '../store/store';
import { BLACKBODY, ramp, type RGB } from '../util/color';
import { displayZ } from './Terrain';

/** Rebuild interval (ms): lava moves slowly compared with the frame rate. */
const REBUILD_MS = 500;
/** Lava cooler than this does not glow visibly in daylight (°C). */
const GLOW_MIN_C = 650;

/** Soft radial falloff, drawn once into a canvas texture. */
function haloTexture(): THREE.Texture {
  const size = 64;
  const cv = document.createElement('canvas');
  cv.width = cv.height = size;
  const g = cv.getContext('2d')!;
  const grad = g.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
  grad.addColorStop(0, 'rgba(255,255,255,1)');
  grad.addColorStop(0.25, 'rgba(255,255,255,0.55)');
  grad.addColorStop(0.6, 'rgba(255,255,255,0.12)');
  grad.addColorStop(1, 'rgba(255,255,255,0)');
  g.fillStyle = grad;
  g.fillRect(0, 0, size, size);
  const tex = new THREE.CanvasTexture(cv);
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

/** Halo brightness (0–1) of lava at temperature {@code t} °C: nothing below ~650 °C, saturating near 1150 °C. */
export function haloStrength(t: number): number {
  return Math.max(0, Math.min(1, (t - GLOW_MIN_C) / 500));
}

function hash(i: number): number {
  const x = Math.sin(i * 127.1 + 311.7) * 43758.5453;
  return x - Math.floor(x);
}

/**
 * Bloom-like glow over incandescent lava without a post-processing pass (so it works the same on
 * the WebGPU and WebGL paths): additive, depth-tested halo quads lying on the lava, sampled over the
 * glowing cells, coloured by temperature. Quads rather than points: WebGPU draws points one pixel
 * wide, which turned the glow into a polka-dot grid. Sample positions are jittered within their
 * stride cell so no lattice shows. Rebuilt only when tiles or exaggerations change; the sprite
 * budget comes from the quality preset (off on low).
 */
export function LavaHalo({ world }: { world: WorldInfo }) {
  const budget = useStore((s) => QUALITY[s.quality].glow);
  const material = useMemo(
    () =>
      new THREE.MeshBasicMaterial({
        map: haloTexture(),
        transparent: true,
        depthWrite: false,
        blending: THREE.AdditiveBlending,
        toneMapped: false,
      }),
    [],
  );
  const geometry = useMemo(() => new THREE.PlaneGeometry(1, 1).rotateX(-Math.PI / 2), []);
  const ref = useRef<THREE.InstancedMesh>(null);
  const last = useRef({ at: 0, rev: null as unknown, vExag: 0, dExag: 0 });
  const c: RGB = useMemo(() => [0, 0, 0], []);
  const m = useMemo(() => new THREE.Matrix4(), []);
  const col = useMemo(() => new THREE.Color(), []);

  useLayoutEffect(() => {
    // instance colours exist before the first draw, so the material is compiled with them
    const mesh = ref.current;
    if (mesh && !mesh.instanceColor) mesh.instanceColor = new THREE.InstancedBufferAttribute(new Float32Array(budget * 3), 3);
  }, [budget]);

  useFrame(() => {
    const mesh = ref.current;
    if (budget <= 0 || !mesh) return;
    const now = performance.now();
    const st = useStore.getState();
    const l = last.current;
    if (now - l.at < REBUILD_MS) return;
    if (l.rev === st.tileRevision && l.vExag === st.verticalExaggeration && l.dExag === st.deformationExaggeration) return;
    l.at = now;
    l.rev = st.tileRevision;
    const vExag = (l.vExag = st.verticalExaggeration);
    const dExag = (l.dExag = st.deformationExaggeration);
    const t = world.tileSize;
    // count glowing cells first to choose a sampling stride that fits the budget
    let hot = 0;
    for (let ty = world.tiles.minTy; ty <= world.tiles.maxTy; ty++) {
      for (let tx = world.tiles.minTx; tx <= world.tiles.maxTx; tx++) {
        const d = getTile(Field.LavaDepth, tx, ty)?.values;
        const T = getTile(Field.LavaTemperature, tx, ty)?.values;
        if (!d || !T) continue;
        for (let k = 0; k < d.length; k++) if (d[k] > 0.05 && T[k] > GLOW_MIN_C) hot++;
      }
    }
    const stride = Math.max(1, Math.ceil(Math.sqrt(hot / budget)));
    const size = world.cellSize * 2.4 * stride;
    let n = 0;
    for (let ty = world.tiles.minTy; ty <= world.tiles.maxTy && n < budget; ty++) {
      for (let tx = world.tiles.minTx; tx <= world.tiles.maxTx && n < budget; tx++) {
        const d = getTile(Field.LavaDepth, tx, ty)?.values;
        const T = getTile(Field.LavaTemperature, tx, ty)?.values;
        if (!d || !T) continue;
        for (let b0 = 0; b0 < t && n < budget; b0 += stride) {
          for (let a0 = 0; a0 < t && n < budget; a0 += stride) {
            // one jittered sample per stride block
            const seed = (tx * 7919 + ty * 104729 + b0 * 131 + a0) | 0;
            const a = Math.min(t - 1, a0 + Math.floor(hash(seed) * stride));
            const b = Math.min(t - 1, b0 + Math.floor(hash(seed + 17) * stride));
            const k = b * t + a;
            const s = d[k] > 0.05 ? haloStrength(T[k]) : 0;
            if (s <= 0) continue;
            const x = world.origin[0] + (tx * t + a + hash(seed + 31)) * world.cellSize;
            const y = world.origin[1] + (ty * t + b + hash(seed + 43)) * world.cellSize;
            const z = displayZ(world, x, y, vExag, dExag) + (d[k] + 1.5) * vExag;
            const sz = size * (0.8 + 0.4 * hash(seed + 59));
            m.makeScale(sz, 1, sz).setPosition(x, z, -y);
            mesh.setMatrixAt(n, m);
            ramp(BLACKBODY, T[k], c);
            const gain = 0.2 * s;
            mesh.setColorAt(n, col.setRGB(c[0] * gain, c[1] * gain * 0.8, c[2] * gain * 0.6));
            n++;
          }
        }
      }
    }
    mesh.count = n;
    mesh.instanceMatrix.needsUpdate = true;
    if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true;
  });

  if (budget <= 0) return null;
  return <instancedMesh ref={ref} args={[geometry, material, budget]} renderOrder={6} frustumCulled={false} raycast={() => null} />;
}
