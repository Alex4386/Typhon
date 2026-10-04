import { useFrame } from '@react-three/fiber';
import { useMemo, useRef } from 'react';
import * as THREE from 'three';
import type { SimEvent, WorldInfo } from '../protocol/messages';
import { simNow, useStore } from '../store/store';
import { displayZ } from './Terrain';

const PLUME_PARTICLES = 1400;
const ASH_PARTICLES = 900;
const MAX_BOMBS = 400;
const MAX_FRONT = 600;
const G = 9.81;

function hash(i: number): number {
  const x = Math.sin(i * 127.1 + 311.7) * 43758.5453;
  return x - Math.floor(x);
}

/**
 * Event-driven atmosphere: eruption column and umbrella, downwind ash cloud, ballistic bombs,
 * lightning and PDC/lahar fronts. Uses instanced meshes only (WebGPU and WebGL2 alike).
 */
export function Atmosphere({ world }: { world: WorldInfo }) {
  const events = useStore((s) => s.events);
  const plumeRef = useRef<THREE.InstancedMesh>(null);
  const ashRef = useRef<THREE.InstancedMesh>(null);
  const bombRef = useRef<THREE.InstancedMesh>(null);
  const frontRef = useRef<THREE.InstancedMesh>(null);
  const boltGroup = useRef<THREE.Group>(null);
  const seenBolts = useRef(new Map<string, number>());

  const bombs = useMemo(() => events.filter((e): e is Extract<SimEvent, { kind: 'bombLaunched' }> => e.kind === 'bombLaunched').slice(-MAX_BOMBS * 2), [events]);
  const bolts = useMemo(() => events.filter((e): e is Extract<SimEvent, { kind: 'lightning' }> => e.kind === 'lightning').slice(-40), [events]);
  const fronts = useMemo(() => events.filter((e): e is Extract<SimEvent, { kind: 'massFlowFront' }> => e.kind === 'massFlowFront').slice(-20), [events]);

  const boltMat = useMemo(() => new THREE.LineBasicMaterial({ color: '#e8f0ff', transparent: true, depthTest: false }), []);

  useFrame(() => {
    const st = useStore.getState();
    const vExag = st.verticalExaggeration;
    const dExag = st.deformationExaggeration;
    const now = simNow();
    const wall = performance.now() / 1000;
    const m = new THREE.Matrix4();
    const q = new THREE.Quaternion();
    const s = new THREE.Vector3();
    const p = new THREE.Vector3();
    const wind = st.state?.world.wind ?? { speed: 0, bearingDeg: 0 };
    const wr = (wind.bearingDeg * Math.PI) / 180;
    // protocol convention: bearing is the direction the wind blows towards, clockwise from north
    const wx = Math.sin(wr);
    const wy = Math.cos(wr);
    const show = st.showAtmosphere;

    // ── plume + ash cloud ──
    const plume = plumeRef.current;
    const ash = ashRef.current;
    if (plume && ash) {
      let np = 0;
      let na = 0;
      if (show && st.state) {
        for (const v of world.volcanoes) {
          const vs = st.state.volcanoes[v.id];
          if (!vs?.plume) continue;
          const vent = v.vents[0];
          const base = displayZ(world, vent.at[0], vent.at[1], vExag, dExag);
          const top = vs.plume.topZ * vExag;
          const height = Math.max(200, top - base);
          for (let k = 0; k < PLUME_PARTICLES / world.volcanoes.length && np < PLUME_PARTICLES; k++, np++) {
            const life = (wall * 0.08 + hash(k)) % 1;
            const h = life * height;
            const umbrella = Math.max(0, (life - 0.75) / 0.25);
            const spread = 60 + h * 0.08 + umbrella * height * 0.9;
            const ang = hash(k + 7) * Math.PI * 2;
            const rr = Math.sqrt(hash(k + 13)) * spread;
            const drift = h * 0.25 * (wind.speed / 10);
            p.set(vent.at[0] + Math.cos(ang) * rr + wx * drift, base + h - umbrella * height * 0.05, -(vent.at[1] + Math.sin(ang) * rr + wy * drift));
            const size = 40 + spread * 0.25;
            m.compose(p, q, s.set(size, size, size));
            plume.setMatrixAt(np, m);
          }
          for (let k = 0; k < ASH_PARTICLES / world.volcanoes.length && na < ASH_PARTICLES; k++, na++) {
            const d = ((wall * 0.03 + hash(k + 101)) % 1) * 9000;
            const lateral = (hash(k + 202) - 0.5) * (300 + d * 0.35);
            const z = top - d * 0.05 - hash(k + 303) * height * 0.3;
            p.set(vent.at[0] + wx * d - wy * lateral, Math.max(base, z), -(vent.at[1] + wy * d + wx * lateral));
            const size = 120 + d * 0.03;
            m.compose(p, q, s.set(size, size * 0.4, size));
            ash.setMatrixAt(na, m);
          }
        }
      }
      plume.count = np;
      ash.count = na;
      plume.instanceMatrix.needsUpdate = true;
      ash.instanceMatrix.needsUpdate = true;
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
          m.compose(p.set(x, z * vExag, -y), q, s.set(10, 10, 10));
          bm.setMatrixAt(nb++, m);
        }
      }
      bm.count = nb;
      bm.instanceMatrix.needsUpdate = true;
    }

    // ── PDC / lahar fronts ──
    const fm = frontRef.current;
    if (fm) {
      let nf = 0;
      if (show) {
        for (const f of fronts) {
          if (now - f.time > 300) continue;
          for (const c of f.cells) {
            if (nf >= MAX_FRONT) break;
            const z = displayZ(world, c[0], c[1], vExag, dExag);
            m.compose(p.set(c[0], z + 25, -c[1]), q, s.set(45, 30, 45));
            fm.setMatrixAt(nf++, m);
          }
        }
      }
      fm.count = nf;
      fm.instanceMatrix.needsUpdate = true;
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
      <instancedMesh ref={plumeRef} args={[undefined, undefined, PLUME_PARTICLES]} frustumCulled={false} renderOrder={6}>
        <icosahedronGeometry args={[1, 0]} />
        <meshStandardMaterial color="#6d6660" transparent opacity={0.35} depthWrite={false} roughness={1} />
      </instancedMesh>
      <instancedMesh ref={ashRef} args={[undefined, undefined, ASH_PARTICLES]} frustumCulled={false} renderOrder={7}>
        <icosahedronGeometry args={[1, 0]} />
        <meshStandardMaterial color="#8a837b" transparent opacity={0.12} depthWrite={false} roughness={1} />
      </instancedMesh>
      <instancedMesh ref={bombRef} args={[undefined, undefined, MAX_BOMBS]} frustumCulled={false}>
        <icosahedronGeometry args={[1, 0]} />
        <meshBasicMaterial color="#ff8a3c" toneMapped={false} />
      </instancedMesh>
      <instancedMesh ref={frontRef} args={[undefined, undefined, MAX_FRONT]} frustumCulled={false} renderOrder={8}>
        <icosahedronGeometry args={[1, 0]} />
        <meshStandardMaterial color="#c9b9a0" transparent opacity={0.55} depthWrite={false} />
      </instancedMesh>
      <group ref={boltGroup} />
    </group>
  );
}
