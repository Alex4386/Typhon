import { useFrame } from '@react-three/fiber';
import { useMemo, useRef } from 'react';
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

/**
 * Bloom-like glow over incandescent lava without a post-processing pass (so it works the same on
 * the WebGPU and WebGL paths): additive, depth-tested halo sprites sampled over the lava field,
 * coloured and sized by temperature. The sprite budget comes from the quality preset (off on low).
 */
export function LavaHalo({ world }: { world: WorldInfo }) {
  const budget = useStore((s) => QUALITY[s.quality].glow);
  const geometry = useMemo(() => {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(Math.max(1, budget) * 3), 3));
    g.setAttribute('color', new THREE.BufferAttribute(new Float32Array(Math.max(1, budget) * 3), 3));
    g.setDrawRange(0, 0);
    return g;
  }, [budget]);
  const material = useMemo(
    () =>
      new THREE.PointsMaterial({
        map: haloTexture(),
        size: world.cellSize * 3.5,
        sizeAttenuation: true,
        vertexColors: true,
        transparent: true,
        depthWrite: false,
        blending: THREE.AdditiveBlending,
        toneMapped: false,
      }),
    [world.cellSize],
  );
  const last = useRef(0);
  const c: RGB = useMemo(() => [0, 0, 0], []);

  useFrame(() => {
    if (budget <= 0) return;
    const now = performance.now();
    if (now - last.current < REBUILD_MS) return;
    last.current = now;
    const st = useStore.getState();
    const vExag = st.verticalExaggeration;
    const dExag = st.deformationExaggeration;
    const pos = geometry.getAttribute('position') as THREE.BufferAttribute;
    const col = geometry.getAttribute('color') as THREE.BufferAttribute;
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
    let n = 0;
    for (let ty = world.tiles.minTy; ty <= world.tiles.maxTy && n < budget; ty++) {
      for (let tx = world.tiles.minTx; tx <= world.tiles.maxTx && n < budget; tx++) {
        const d = getTile(Field.LavaDepth, tx, ty)?.values;
        const T = getTile(Field.LavaTemperature, tx, ty)?.values;
        if (!d || !T) continue;
        for (let b = 0; b < t && n < budget; b += stride) {
          for (let a = 0; a < t && n < budget; a += stride) {
            const k = b * t + a;
            const s = d[k] > 0.05 ? haloStrength(T[k]) : 0;
            if (s <= 0) continue;
            const x = world.origin[0] + (tx * t + a + 0.5) * world.cellSize;
            const y = world.origin[1] + (ty * t + b + 0.5) * world.cellSize;
            const z = displayZ(world, x, y, vExag, dExag) + (d[k] + 1.5) * vExag;
            pos.setXYZ(n, x, z, -y);
            ramp(BLACKBODY, T[k], c);
            // sparser sampling → brighter sprites, so the total glow does not depend on the stride
            const gain = 0.35 * s * Math.min(3, stride);
            col.setXYZ(n, c[0] * gain, c[1] * gain * 0.8, c[2] * gain * 0.6);
            n++;
          }
        }
      }
    }
    geometry.setDrawRange(0, n);
    pos.needsUpdate = true;
    col.needsUpdate = true;
    geometry.computeBoundingSphere();
    material.size = world.cellSize * 3.5 * Math.min(2.5, stride);
  });

  if (budget <= 0) return null;
  return <points geometry={geometry} material={material} renderOrder={6} frustumCulled={false} />;
}
