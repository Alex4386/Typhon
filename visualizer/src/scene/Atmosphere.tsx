import { useFrame } from '@react-three/fiber';
import { useMemo, useRef } from 'react';
import * as THREE from 'three';
import type { SimEvent, WorldInfo } from '../protocol/messages';
import { QUALITY, simNow, useStore } from '../store/store';
import { displayZ } from './Terrain';

/** Upper bounds; the active counts follow the quality setting. */
const PLUME_PARTICLES = 2400;
const ASH_PARTICLES = 1600;
const MAX_BOMBS = 400;
const FOUNTAIN_PARTICLES = 700;
const MAX_FRONT = 220;
/** Front cells closer than this (m) merge into one dust puff. */
const FRONT_MERGE_M = 90;
/** A front puff fades out over this long after its event (simulated s). */
const FRONT_FADE_S = 300;
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
 * Event-driven atmosphere: eruption column and umbrella, downwind ash cloud, ballistic bombs,
 * lightning and PDC/lahar fronts. Everything translucent is a camera-facing soft sprite on an
 * instanced quad (WebGPU and WebGL2 alike); counts follow the quality setting and, for the column,
 * how much of the screen it covers, so overlapping puffs do not pile up overdraw close up.
 */
export function Atmosphere({ world }: { world: WorldInfo }) {
  const events = useStore((s) => s.events);
  const plumeRef = useRef<THREE.InstancedMesh>(null);
  const ashRef = useRef<THREE.InstancedMesh>(null);
  const bombRef = useRef<THREE.InstancedMesh>(null);
  const frontRef = useRef<THREE.InstancedMesh>(null);
  const fountainRef = useRef<THREE.InstancedMesh>(null);
  const boltGroup = useRef<THREE.Group>(null);
  const seenBolts = useRef(new Map<string, number>());

  const bombs = useMemo(() => events.filter((e): e is Extract<SimEvent, { kind: 'bombLaunched' }> => e.kind === 'bombLaunched').slice(-MAX_BOMBS * 2), [events]);
  const bolts = useMemo(() => events.filter((e): e is Extract<SimEvent, { kind: 'lightning' }> => e.kind === 'lightning').slice(-40), [events]);
  const fronts = useMemo(() => events.filter((e): e is Extract<SimEvent, { kind: 'massFlowFront' }> => e.kind === 'massFlowFront').slice(-20), [events]);

  const boltMat = useMemo(() => new THREE.LineBasicMaterial({ color: '#e8f0ff', transparent: true, depthTest: false }), []);
  const puff = useMemo(() => puffTexture(), []);
  const dark = useMemo(() => new THREE.Color('#3b3632'), []);
  const light = useMemo(() => new THREE.Color('#9a938a'), []);
  const tint = useMemo(() => new THREE.Color(), []);

  useFrame(({ camera }) => {
    const st = useStore.getState();
    const vExag = st.verticalExaggeration;
    const dExag = st.deformationExaggeration;
    const now = simNow();
    const wall = performance.now() / 1000;
    const m = new THREE.Matrix4();
    // puffs are camera-facing billboards
    const q = camera.quaternion;
    const counts = QUALITY[st.quality];
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
          if (!vent) continue;
          const base = displayZ(world, vent.at[0], vent.at[1], vExag, dExag);
          const top = vs.plume.topZ * vExag;
          const height = Math.max(200, top - base);
          // overdraw scales with how much of the screen the column covers: close up, fewer and
          // larger puffs; far away the full count keeps the column dense
          const dist = Math.max(1, camera.position.distanceTo(p.set(vent.at[0], base + height * 0.5, -vent.at[1])));
          const lod = Math.min(1, Math.max(0.25, dist / (height * 3)));
          const grow = 1 / Math.sqrt(lod);
          const perVolcano = Math.round((counts.plume / world.volcanoes.length) * lod);
          for (let k = 0; k < perVolcano && np < counts.plume; k++, np++) {
            const life = (wall * 0.08 + hash(k)) % 1;
            const h = life * height;
            const umbrella = Math.max(0, (life - 0.75) / 0.25);
            const spread = 60 + h * 0.08 + umbrella * height * 0.9;
            const ang = hash(k + 7) * Math.PI * 2;
            const rr = Math.sqrt(hash(k + 13)) * spread;
            const drift = h * 0.25 * (wind.speed / 10);
            p.set(vent.at[0] + Math.cos(ang) * rr + wx * drift, base + h - umbrella * height * 0.05, -(vent.at[1] + Math.sin(ang) * rr + wy * drift));
            // puffs grow and lighten as the column entrains air and cools
            const size = (90 + spread * 0.9) * grow;
            m.compose(p, q, s.set(size, size, size));
            plume.setMatrixAt(np, m);
            tint.copy(dark).lerp(light, Math.min(1, life * 1.2));
            plume.setColorAt(np, tint);
          }
          for (let k = 0; k < Math.round((counts.ash / world.volcanoes.length) * lod) && na < counts.ash; k++, na++) {
            const d = ((wall * 0.03 + hash(k + 101)) % 1) * 9000;
            const lateral = (hash(k + 202) - 0.5) * (300 + d * 0.35);
            const z = top - d * 0.05 - hash(k + 303) * height * 0.3;
            p.set(vent.at[0] + wx * d - wy * lateral, Math.max(base, z), -(vent.at[1] + wy * d + wx * lateral));
            const size = (260 + d * 0.06) * grow;
            m.compose(p, q, s.set(size, size * 0.6, size));
            ash.setMatrixAt(na, m);
          }
        }
      }
      plume.count = np;
      ash.count = na;
      if (plume.instanceColor) plume.instanceColor.needsUpdate = true;
      plume.instanceMatrix.needsUpdate = true;
      ash.instanceMatrix.needsUpdate = true;
    }

    // ── lava fountains (Hawaiian fire fountaining; height grows with the effusion rate) ──
    const fm0 = fountainRef.current;
    if (fm0) {
      let nf = 0;
      if (show && st.state) {
        const budget = Math.round(FOUNTAIN_PARTICLES * (st.quality === 'low' ? 0.4 : st.quality === 'medium' ? 0.7 : 1));
        for (const v of world.volcanoes) {
          const vs = st.state.volcanoes[v.id];
          const rate = vs?.chamber.eruptionRate ?? 0;
          if (!(rate > 0) || vs?.chamber.regime !== 'FOUNTAINING') continue;
          // Kīlauea's fountains reached ~50–500 m at 10–500 m³/s
          const H = Math.min(550, 25 * Math.sqrt(rate)) * vExag;
          for (const vent of v.vents) {
            const base = displayZ(world, vent.at[0], vent.at[1], vExag, dExag);
            const per = Math.floor(budget / Math.max(1, v.vents.length * world.volcanoes.length));
            for (let k = 0; k < per && nf < budget; k++, nf++) {
              const life = (wall * (0.5 + hash(k + 7) * 0.3) + hash(k)) % 1;
              const h = H * (0.6 + hash(k + 3) * 0.4) * 4 * life * (1 - life);
              const ang = hash(k + 11) * Math.PI * 2;
              const rr = (8 + hash(k + 17) * 25) * life * 2;
              p.set(vent.at[0] + Math.cos(ang) * rr, base + h, -(vent.at[1] + Math.sin(ang) * rr));
              const size = 14 + 10 * (1 - life);
              m.compose(p, q, s.set(size, size, size));
              fm0.setMatrixAt(nf, m);
            }
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

    // ── PDC / lahar fronts: soft dust puffs resting on the ground, one per merged cell group ──
    const fm = frontRef.current;
    if (fm) {
      let nf = 0;
      if (show) {
        const seen = new Set<number>();
        // newest first: a cell covered by a newer front keeps the newer puff
        for (let fi = fronts.length - 1; fi >= 0 && nf < MAX_FRONT; fi--) {
          const f = fronts[fi];
          const age = now - f.time;
          if (age < 0 || age > FRONT_FADE_S) continue;
          const fade = 1 - age / FRONT_FADE_S;
          const size = (f.flow === 'LAHAR' ? 70 : 140) * (0.6 + 0.4 * fade);
          for (const c of f.cells) {
            if (nf >= MAX_FRONT) break;
            const key = Math.round(c[0] / FRONT_MERGE_M) * 73856093 + Math.round(c[1] / FRONT_MERGE_M) * 19349663;
            if (seen.has(key)) continue;
            seen.add(key);
            const ground = Math.max(displayZ(world, c[0], c[1], vExag, dExag), (world.hasSea === false ? -Infinity : world.seaLevel) * vExag);
            m.compose(p.set(c[0], ground + size * 0.35, -c[1]), q, s.set(size, size * 0.7, size));
            fm.setMatrixAt(nf, m);
            tint.set(f.flow === 'LAHAR' ? '#6f5a44' : '#b9ab98').multiplyScalar(0.55 + 0.45 * fade);
            fm.setColorAt(nf, tint);
            nf++;
          }
        }
      }
      fm.count = nf;
      if (fm.instanceColor) fm.instanceColor.needsUpdate = true;
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
        <planeGeometry args={[1, 1]} />
        <meshLambertMaterial map={puff} transparent opacity={0.55} depthWrite={false} />
      </instancedMesh>
      <instancedMesh ref={ashRef} args={[undefined, undefined, ASH_PARTICLES]} frustumCulled={false} renderOrder={7}>
        <planeGeometry args={[1, 1]} />
        <meshLambertMaterial map={puff} color="#8a837b" transparent opacity={0.18} depthWrite={false} />
      </instancedMesh>
      <instancedMesh ref={fountainRef} args={[undefined, undefined, FOUNTAIN_PARTICLES]} frustumCulled={false} renderOrder={9}>
        <planeGeometry args={[1, 1]} />
        <meshBasicMaterial map={puff} color="#ffb347" transparent opacity={0.9} depthWrite={false} blending={THREE.AdditiveBlending} toneMapped={false} />
      </instancedMesh>
      {/* bombs: small glowing billboards (incandescent clasts), not faceted solids */}
      <instancedMesh ref={bombRef} args={[undefined, undefined, MAX_BOMBS]} frustumCulled={false} renderOrder={9}>
        <planeGeometry args={[1, 1]} />
        <meshBasicMaterial map={puff} color="#ff8a3c" transparent opacity={0.95} depthWrite={false} blending={THREE.AdditiveBlending} toneMapped={false} />
      </instancedMesh>
      {/* pyroclastic-flow and lahar fronts: soft dust billows (instance colour carries kind and fade) */}
      <instancedMesh ref={frontRef} args={[undefined, undefined, MAX_FRONT]} frustumCulled={false} renderOrder={8}>
        <planeGeometry args={[1, 1]} />
        <meshBasicMaterial map={puff} transparent opacity={0.5} depthWrite={false} />
      </instancedMesh>
      <group ref={boltGroup} />
    </group>
  );
}
