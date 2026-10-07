import { scaledBudget } from '../util/device';
import { fountainSources } from './fountains';
import { useFrame } from '@react-three/fiber';
import { useMemo, useRef } from 'react';
import * as THREE from 'three';
import type { SimEvent, WorldInfo } from '../protocol/messages';
import { simNow, useStore } from '../store/store';
import { displayZ } from './Terrain';

/** Upper bounds; the active counts follow the quality setting. */
const MAX_BOMBS = scaledBudget(400);
const FOUNTAIN_PARTICLES = scaledBudget(700);
const G = 9.81;

/** Soft round puff (alpha falls off smoothly), drawn once into a canvas texture. */
function puffTexture(): THREE.Texture {
  const size = 128;
  const cv = document.createElement('canvas');
  cv.width = cv.height = size;
  const g = cv.getContext('2d')!;
  const grad = g.createRadialGradient(size / 2, size / 2, 0, size / 2, size / 2, size / 2);
  grad.addColorStop(0, 'rgba(255,255,255,0.9)');
  grad.addColorStop(0.45, 'rgba(255,255,255,0.55)');
  grad.addColorStop(0.8, 'rgba(255,255,255,0.12)');
  grad.addColorStop(1, 'rgba(255,255,255,0)');
  g.fillStyle = grad;
  g.fillRect(0, 0, size, size);
  // a little lumpiness so overlapping puffs read as billows
  for (let k = 0; k < 14; k++) {
    const x = size / 2 + (hash(k) - 0.5) * size * 0.45;
    const y = size / 2 + (hash(k + 50) - 0.5) * size * 0.45;
    const r = size * (0.12 + hash(k + 90) * 0.12);
    const gg = g.createRadialGradient(x, y, 0, x, y, r);
    gg.addColorStop(0, 'rgba(255,255,255,0.25)');
    gg.addColorStop(1, 'rgba(255,255,255,0)');
    g.fillStyle = gg;
    g.fillRect(0, 0, size, size);
  }
  const tex = new THREE.CanvasTexture(cv);
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

function hash(i: number): number {
  const x = Math.sin(i * 127.1 + 311.7) * 43758.5453;
  return x - Math.floor(x);
}

/**
 * Event-driven atmosphere: lava fountains, ballistic bombs and lightning. The eruption column,
 * umbrella and drifting ash are EruptionColumn, pyroclastic flows SurgeClouds (both lit billows);
 * lahars are the ground's mud layer. Sprites are camera-facing instanced quads (WebGPU and WebGL2 alike).
 */
export function Atmosphere({ world }: { world: WorldInfo }) {
  const events = useStore((s) => s.events);
  const bombRef = useRef<THREE.InstancedMesh>(null);
  const fountainRef = useRef<THREE.InstancedMesh>(null);
  const boltGroup = useRef<THREE.Group>(null);
  const seenBolts = useRef(new Map<string, number>());

  const bombs = useMemo(() => events.filter((e): e is Extract<SimEvent, { kind: 'bombLaunched' }> => e.kind === 'bombLaunched').slice(-MAX_BOMBS * 2), [events]);
  const bolts = useMemo(() => events.filter((e): e is Extract<SimEvent, { kind: 'lightning' }> => e.kind === 'lightning').slice(-40), [events]);

  const boltMat = useMemo(() => new THREE.LineBasicMaterial({ color: '#e8f0ff', transparent: true, depthTest: false }), []);
  const puff = useMemo(() => puffTexture(), []);

  useFrame(({ camera }) => {
    const st = useStore.getState();
    const vExag = st.verticalExaggeration;
    const dExag = st.deformationExaggeration;
    const now = simNow();
    const wall = performance.now() / 1000;
    const m = new THREE.Matrix4();
    // puffs are camera-facing billboards
    const q = camera.quaternion;
    const s = new THREE.Vector3();
    const p = new THREE.Vector3();
    const show = st.showAtmosphere;

    // the eruption column, umbrella and drifting ash are EruptionColumn (lit billows)

    // ── lava fountains (Hawaiian fire fountaining; height grows with the effusion rate) ──
    const fm0 = fountainRef.current;
    if (fm0) {
      let nf = 0;
      if (show && st.state) {
        const budget = Math.round(FOUNTAIN_PARTICLES * (st.quality === 'low' ? 0.4 : st.quality === 'medium' ? 0.7 : 1));
        const sources = fountainSources(world, st.state.volcanoes);
        const total = sources.reduce((sum, f) => sum + f.weight, 0);
        for (const src of sources) {
          const vent = src.vent;
          const H = src.heightM * vExag;
          const per = Math.floor((budget * src.weight) / Math.max(1, total));
          const line = vent.kind === 'fissure' ? vent.line : undefined;
          const base = line ? 0 : displayZ(world, vent.at[0], vent.at[1], vExag, dExag);
          for (let k = 0; k < per && nf < budget; k++, nf++) {
            const life = (wall * (0.5 + hash(k + 7) * 0.3) + hash(k)) % 1;
            const h = H * (0.6 + hash(k + 3) * 0.4) * 4 * life * (1 - life);
            const ang = hash(k + 11) * Math.PI * 2;
            const rr = (8 + hash(k + 17) * 25) * life * 2;
            if (line) {
              // a curtain of fire along the fissure: each particle rises from its own point of the line
              const f = hash(k + 23);
              const x = line[0][0] + (line[1][0] - line[0][0]) * f;
              const y = line[0][1] + (line[1][1] - line[0][1]) * f;
              const b = displayZ(world, x, y, vExag, dExag);
              p.set(x + Math.cos(ang) * rr * 0.3, b + h, -(y + Math.sin(ang) * rr * 0.3));
            } else {
              p.set(vent.at[0] + Math.cos(ang) * rr, base + h, -(vent.at[1] + Math.sin(ang) * rr));
            }
            const size = 14 + 10 * (1 - life);
            m.compose(p, q, s.set(size, size, size));
            fm0.setMatrixAt(nf, m);
          }
        }
      }
      fm0.count = nf;
      fm0.instanceMatrix.needsUpdate = true;
    }

    // ── bombs (ballistic, drag ignored for display) ──
    const bm = bombRef.current;
    if (bm) {
      let nb = 0;
      if (show) {
        for (const b of bombs) {
          const t = now - b.time;
          if (t < 0 || t > b.flightSeconds || nb >= MAX_BOMBS) continue;
          const x = b.start[0] + b.velocity[0] * t;
          const y = b.start[1] + b.velocity[1] * t;
          const z = b.start[2] + b.velocity[2] * t - 0.5 * G * t * t;
          m.compose(p.set(x, z * vExag, -y), q, s.set(16, 16, 16));
          bm.setMatrixAt(nb++, m);
        }
      }
      bm.count = nb;
      bm.instanceMatrix.needsUpdate = true;
    }

    // ── lightning: flash for 0.5 s of wall time after first sight ──
    const g = boltGroup.current;
    if (g) {
      g.clear();
      if (show) {
        for (const b of bolts) {
          const key = `${b.volcanoId}:${b.time}:${b.at.join(',')}`;
          let first = seenBolts.current.get(key);
          if (first === undefined) {
            if (now - b.time > 30) continue; // old event, do not flash on load
            first = wall;
            seenBolts.current.set(key, first);
          }
          const age = wall - first;
          if (age > 0.5) continue;
          const pts: THREE.Vector3[] = [];
          let x = b.at[0];
          let y = b.at[1];
          let z = b.at[2];
          for (let k = 0; k < 9; k++) {
            pts.push(new THREE.Vector3(x, z * vExag, -y));
            x += (hash(k + b.time) - 0.5) * 160;
            y += (hash(k * 3 + b.time) - 0.5) * 160;
            z -= 120;
          }
          const line = new THREE.Line(new THREE.BufferGeometry().setFromPoints(pts), boltMat);
          line.renderOrder = 12;
          g.add(line);
        }
      }
    }
  });

  return (
    <group>
      <instancedMesh ref={fountainRef} args={[undefined, undefined, FOUNTAIN_PARTICLES]} frustumCulled={false} renderOrder={9}>
        <planeGeometry args={[1, 1]} />
        <meshBasicMaterial map={puff} color="#ffb347" transparent opacity={0.9} depthWrite={false} blending={THREE.AdditiveBlending} toneMapped={false} />
      </instancedMesh>
      {/* bombs: small glowing billboards (incandescent clasts), not faceted solids */}
      <instancedMesh ref={bombRef} args={[undefined, undefined, MAX_BOMBS]} frustumCulled={false} renderOrder={9}>
        <planeGeometry args={[1, 1]} />
        <meshBasicMaterial map={puff} color="#ff8a3c" transparent opacity={0.95} depthWrite={false} blending={THREE.AdditiveBlending} toneMapped={false} />
      </instancedMesh>
      <group ref={boltGroup} />
    </group>
  );
}
