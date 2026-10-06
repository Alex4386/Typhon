import { useFrame } from '@react-three/fiber';
import { useMemo, useRef } from 'react';
import * as THREE from 'three';

/**
 * One cloud: an elongated billowing mass over an oriented footprint (scene units are map metres; the
 * caller supplies ground heights already in display units).
 */
export interface CloudShape {
  /** Footprint centre (map metres) and unit axis (east, north). */
  x: number;
  y: number;
  ax: number;
  ay: number;
  halfLength: number;
  halfWidth: number;
  /** Height of the cloud at its head (display units). */
  height: number;
  /** Which end of the axis is the head (tall, leading): +1 or −1. */
  head: 1 | -1;
  /** Display ground height under a map point. */
  ground: (x: number, y: number) => number;
  /** 0..1: fades the whole cloud (appearing, dissipating). */
  opacity: number;
}

/** Billows per cloud at most, and in total. */
const PER_CLOUD = 10;
const MAX_BILLOWS = 160;

/**
 * Pre-shaded billow texture: overlapping cauliflower lobes, each lit from above (the sun is high),
 * darker underneath, with a soft edge. Drawn once.
 */
function billowTexture(): THREE.Texture {
  const size = 256;
  const cv = document.createElement('canvas');
  cv.width = cv.height = size;
  const g = cv.getContext('2d')!;
  const rnd = (i: number) => {
    const x = Math.sin(i * 91.7 + 17.3) * 43758.5453;
    return x - Math.floor(x);
  };
  // lobes are drawn blurred so their edges never show as rings
  g.filter = 'blur(7px)';
  for (let k = 0; k < 34; k++) {
    // lobes cluster towards the middle, larger at the bottom (the base of a billowing cloud)
    const a = rnd(k) * Math.PI * 2;
    const r = Math.sqrt(rnd(k + 40)) * size * 0.28;
    const x = size / 2 + Math.cos(a) * r;
    const y = size / 2 + Math.sin(a) * r * 0.8;
    const lobe = size * (0.12 + rnd(k + 80) * 0.14);
    const grad = g.createRadialGradient(x - lobe * 0.3, y - lobe * 0.35, lobe * 0.1, x, y, lobe);
    grad.addColorStop(0, 'rgba(255,255,255,0.7)');
    grad.addColorStop(0.5, 'rgba(210,210,210,0.5)');
    grad.addColorStop(1, 'rgba(150,150,150,0)');
    g.fillStyle = grad;
    g.beginPath();
    g.arc(x, y, lobe, 0, Math.PI * 2);
    g.fill();
  }
  // soft overall edge
  g.filter = 'none';
  g.globalCompositeOperation = 'destination-in';
  const mask = g.createRadialGradient(size / 2, size / 2, size * 0.25, size / 2, size / 2, size / 2);
  mask.addColorStop(0, 'rgba(0,0,0,1)');
  mask.addColorStop(1, 'rgba(0,0,0,0)');
  g.fillStyle = mask;
  g.fillRect(0, 0, size, size);
  const tex = new THREE.CanvasTexture(cv);
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

/**
 * Billowing clouds drawn as a few large, pre-shaded billboards laid out along each cloud's footprint:
 * taller and denser at the head, low and sheet-like at the tail, slowly churning. One instanced draw
 * call for all clouds; no custom shader, so WebGPU and WebGL2 alike. Reusable for any cloud with a
 * footprint (pyroclastic surges now; ocean-entry steam or ash clouds later).
 */
export function VolumeCloud({ clouds, color = '#857868' }: { clouds: () => CloudShape[]; color?: string }) {
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
    let n = 0;
    for (const cl of clouds()) {
      if (n >= MAX_BILLOWS) break;
      const length = 2 * cl.halfLength;
      const width = 2 * cl.halfWidth;
      const k = Math.max(2, Math.min(PER_CLOUD, Math.round(length / Math.max(1, width * 0.8))));
      for (let b = 0; b < k && n < MAX_BILLOWS; b++) {
        // u from tail (0) to head (1)
        const u = k === 1 ? 1 : b / (k - 1);
        const along = (u * 2 - 1) * cl.halfLength * 0.85 * cl.head;
        const x = cl.x + cl.ax * along;
        const y = cl.y + cl.ay * along;
        const h = cl.height * (0.3 + 0.7 * Math.pow(u, 1.4));
        const size = Math.max(width * 1.15, h * 1.25);
        const churn = Math.sin(t * 0.35 + b * 1.7) * 0.06;
        const ground = cl.ground(x, y);
        p.set(x, ground + size * (0.38 + churn), -y);
        s.set(size * (1 + churn), size * (0.8 - churn * 0.5), 1);
        roll.setFromAxisAngle(zAxis, b * 2.3 + t * 0.03);
        q.copy(camera.quaternion).multiply(roll);
        m.compose(p, q, s);
        im.setMatrixAt(n, m);
        // darker, dustier at the base of the tail; lighter where the cloud rises at the head
        c.setScalar((0.65 + 0.35 * u) * (0.35 + 0.65 * cl.opacity));
        im.setColorAt(n, c);
        n++;
      }
    }
    im.count = n;
    im.instanceMatrix.needsUpdate = true;
    if (im.instanceColor) im.instanceColor.needsUpdate = true;
  });

  return (
    <instancedMesh ref={mesh} args={[undefined, undefined, MAX_BILLOWS]} frustumCulled={false} renderOrder={8}>
      <planeGeometry args={[1, 1]} />
      {/* the cloud's colour is the material's; instance colours only shade it (base darker, head lighter) */}
      <meshBasicMaterial map={tex} color={color} transparent opacity={0.78} depthWrite={false} />
    </instancedMesh>
  );
}
