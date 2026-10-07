import { useFrame } from '@react-three/fiber';
import { useEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import { useStore } from '../store/store';
import { hexToRgb, ramp, type RGB } from '../util/color';
import { waterUniforms } from './water';

/** Haze at the horizon: the sky's lowest band, and the colour the fog fades distant ground into. */
export const HORIZON = '#b9c4cc';

/**
 * Daytime sky by elevation angle of the view direction (degrees, sRGB): hazy, pale horizon, deepening
 * to blue overhead (Rayleigh scattering; aerosols brighten and whiten the lowest few degrees). Below
 * the horizon the haze continues, so distant ground fogs into it without a seam.
 */
const SKY: [number, number, number, number][] = [
  [-90, ...hexToRgb(HORIZON)],
  [0, ...hexToRgb(HORIZON)],
  [3, 0.67, 0.75, 0.82],
  [12, 0.5, 0.64, 0.79],
  [35, 0.35, 0.52, 0.73],
  [90, 0.23, 0.4, 0.64],
];
/** Warm forward scattering around the sun: a broad aureole and a tight glare (sRGB, added). */
const AUREOLE: RGB = [0.32, 0.27, 0.18];
const GLARE: RGB = [0.6, 0.55, 0.42];

function lin(c: number): number {
  return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

/**
 * The sky as a dome around the camera: vertex-coloured, unlit and drawn first behind everything, so
 * it stays locked to the real horizon whatever the camera's pitch (a CSS gradient behind the canvas
 * could not), on WebGPU and WebGL2 alike. Hidden underground, where the view's dark rock backdrop shows.
 */
export function Sky() {
  const mesh = useRef<THREE.Mesh>(null);
  const geo = useMemo(() => {
    const g = new THREE.SphereGeometry(1, 64, 32);
    g.setAttribute('color', new THREE.BufferAttribute(new Float32Array(g.getAttribute('position').count * 3), 3));
    return g;
  }, []);
  const mat = useMemo(
    () => new THREE.MeshBasicMaterial({ vertexColors: true, side: THREE.BackSide, fog: false, depthWrite: false, depthTest: false, toneMapped: false }),
    [],
  );
  useEffect(
    () => () => {
      geo.dispose();
      mat.dispose();
    },
    [geo, mat],
  );
  const painted = useRef(new THREE.Vector3());

  useFrame(({ camera }) => {
    const m = mesh.current;
    if (!m) return;
    const under = useStore.getState().underground;
    m.visible = !under;
    if (under) return;
    // a dome just inside the far plane, centred on the camera
    const far = (camera as THREE.PerspectiveCamera).far ?? 1e5;
    m.position.copy(camera.position);
    m.scale.setScalar(far * 0.9);
    const sun = waterUniforms.uSunDir.value as THREE.Vector3;
    if (painted.current.distanceToSquared(sun) < 1e-8) return;
    painted.current.copy(sun);
    const pos = geo.getAttribute('position') as THREE.BufferAttribute;
    const col = geo.getAttribute('color') as THREE.BufferAttribute;
    const c: RGB = [0, 0, 0];
    for (let k = 0; k < pos.count; k++) {
      const x = pos.getX(k);
      const y = pos.getY(k);
      const z = pos.getZ(k);
      ramp(SKY, (Math.asin(Math.max(-1, Math.min(1, y))) * 180) / Math.PI, c);
      // forward scattering only above the horizon (below it the haze hides the ground, not the sun)
      const cos = x * sun.x + y * sun.y + z * sun.z;
      const up = Math.min(1, Math.max(0, y * 8 + 0.3));
      const a = Math.pow(Math.max(0, cos), 6) * up;
      const g = Math.pow(Math.max(0, cos), 180) * up;
      col.setXYZ(k, lin(Math.min(1, c[0] + AUREOLE[0] * a + GLARE[0] * g)), lin(Math.min(1, c[1] + AUREOLE[1] * a + GLARE[1] * g)), lin(Math.min(1, c[2] + AUREOLE[2] * a + GLARE[2] * g)));
    }
    col.needsUpdate = true;
  });

  return <mesh ref={mesh} geometry={geo} material={mat} renderOrder={-1000} frustumCulled={false} raycast={() => null} />;
}
