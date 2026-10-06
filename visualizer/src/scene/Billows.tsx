import { useFrame } from '@react-three/fiber';
import { useMemo, useRef } from 'react';
import * as THREE from 'three';
import { waterUniforms } from './water';

/**
 * One billow of a cloud: a camera-facing, pre-shaded lobe texture (scene units; y is the display
 * height). `cx/cy/cz` is the centre of the mass it belongs to, which decides how the sun lights it.
 */
export interface Billow {
  x: number;
  y: number;
  z: number;
  size: number;
  /** Vertical squash (1 = round, < 1 flattened, e.g. an umbrella cloud). */
  flat: number;
  /** Centre of the cloud mass (scene units), for sun shading. */
  cx: number;
  cy: number;
  cz: number;
  /** Base colour (linear 0..1) before shading. */
  r: number;
  g: number;
  b: number;
  /** 0..1, multiplies the shading (fading in or out). */
  fade: number;
  seed: number;
}

/**
 * Pre-shaded billow texture: overlapping cauliflower lobes, lit from the top of the image (the sun is
 * above for camera-facing sprites, which are only rolled a little), soft-edged. Drawn once.
 */
export function billowTexture(): THREE.Texture {
  const size = 256;
  const cv = document.createElement('canvas');
  cv.width = cv.height = size;
  const g = cv.getContext('2d')!;
  const rnd = (i: number) => {
    const x = Math.sin(i * 91.7 + 17.3) * 43758.5453;
    return x - Math.floor(x);
  };
  g.filter = 'blur(6px)';
  for (let k = 0; k < 40; k++) {
    const a = rnd(k) * Math.PI * 2;
    const r = Math.sqrt(rnd(k + 40)) * size * 0.27;
    const x = size / 2 + Math.cos(a) * r;
    const y = size / 2 + Math.sin(a) * r * 0.85;
    const lobe = size * (0.11 + rnd(k + 80) * 0.14);
    // each lobe lit from above: bright top, shadowed underside
    const grad = g.createRadialGradient(x, y - lobe * 0.45, lobe * 0.08, x, y, lobe);
    grad.addColorStop(0, 'rgba(255,255,255,0.75)');
    grad.addColorStop(0.55, 'rgba(196,196,196,0.55)');
    grad.addColorStop(1, 'rgba(120,120,120,0)');
    g.fillStyle = grad;
    g.beginPath();
    g.arc(x, y, lobe, 0, Math.PI * 2);
    g.fill();
  }
  g.filter = 'none';
  g.globalCompositeOperation = 'destination-in';
  const mask = g.createRadialGradient(size / 2, size / 2, size * 0.22, size / 2, size / 2, size / 2);
  mask.addColorStop(0, 'rgba(0,0,0,1)');
  mask.addColorStop(1, 'rgba(0,0,0,0)');
  g.fillStyle = mask;
  g.fillRect(0, 0, size, size);
  const tex = new THREE.CanvasTexture(cv);
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

/**
 * Light reaching a billow: ambient sky plus sun on the side of the mass facing the sun, so a cloud
 * is bright where the sun hits it and darker in its core and underside (cheap self-shading: the mass
 * shadows its own far side). 0..~1.1.
 */
export function billowLight(dx: number, dy: number, dz: number, sx: number, sy: number, sz: number): number {
  const len = Math.hypot(dx, dy, dz);
  const facing = len > 1e-6 ? (dx * sx + dy * sy + dz * sz) / len : 0;
  // −1 (far side, in shadow) … +1 (facing the sun); the core (len→0) sits in between
  return 0.42 + 0.38 * Math.max(-0.4, facing) + 0.22 * Math.max(0, sy);
}

/**
 * Draws billows from `source()` every frame: one instanced draw call, at most `max` billboards, no
 * custom shader (WebGPU and WebGL2 alike). Instance colours carry each billow's base colour times its
 * sun shading; billboards face the camera and roll only slightly, so the texture's top-lit lobes stay
 * consistent with the sun above.
 */
export function BillowLayer({ source, max, opacity = 0.82, renderOrder = 8 }: { source: () => Billow[]; max: number; opacity?: number; renderOrder?: number }) {
  const mesh = useRef<THREE.InstancedMesh>(null);
  const tex = useMemo(() => billowTexture(), []);
  const m = useMemo(() => new THREE.Matrix4(), []);
  const p = useMemo(() => new THREE.Vector3(), []);
  const s = useMemo(() => new THREE.Vector3(), []);
  const q = useMemo(() => new THREE.Quaternion(), []);
  const roll = useMemo(() => new THREE.Quaternion(), []);
  const zAxis = useMemo(() => new THREE.Vector3(0, 0, 1), []);
  const c = useMemo(() => new THREE.Color(), []);

  useFrame(({ camera, clock }) => {
    const im = mesh.current;
    if (!im) return;
    const t = clock.elapsedTime;
    const sun = waterUniforms.uSunDir.value as THREE.Vector3;
    let n = 0;
    for (const b of source()) {
      if (n >= max) break;
      p.set(b.x, b.y, b.z);
      s.set(b.size, b.size * b.flat, 1);
      roll.setFromAxisAngle(zAxis, Math.sin(b.seed * 12.9898) * 0.25 + Math.sin(t * 0.05 + b.seed) * 0.05);
      q.copy(camera.quaternion).multiply(roll);
      m.compose(p, q, s);
      im.setMatrixAt(n, m);
      const light = billowLight(b.x - b.cx, b.y - b.cy, b.z - b.cz, sun.x, sun.y, sun.z) * b.fade;
      c.setRGB(b.r * light, b.g * light, b.b * light);
      im.setColorAt(n, c);
      n++;
    }
    im.count = n;
    im.instanceMatrix.needsUpdate = true;
    if (im.instanceColor) im.instanceColor.needsUpdate = true;
  });

  return (
    <instancedMesh ref={mesh} args={[undefined, undefined, max]} frustumCulled={false} renderOrder={renderOrder}>
      <planeGeometry args={[1, 1]} />
      <meshBasicMaterial map={tex} transparent opacity={opacity} depthWrite={false} />
    </instancedMesh>
  );
}
