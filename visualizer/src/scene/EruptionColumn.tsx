import { useRef } from 'react';
import { useFrame } from '@react-three/fiber';
import type { SimEvent, WorldInfo } from '../protocol/messages';
import { useStore } from '../store/store';
import { BillowLayer, type Billow } from './Billows';
import { displayZ } from './Terrain';

/** Billows in all columns at most (one draw call). */
const MAX_BILLOWS = 300;
/** Per column: rising column, umbrella, downwind ash. */
const COLUMN = 110;
const UMBRELLA = 80;
const DRIFT = 60;
/** Plume radius growth with height: top-hat entrainment α ≈ 0.1 gives db/dz = 6α/5 (Morton, Taylor & Turner 1956). */
const SPREAD = 0.12;
/** Neutral-buoyancy height as a share of the column top: H_T ≈ 1.32·H_B (Sparks 1986). */
const NEUTRAL = 0.76;

/** Base colours in linear RGB: ash ≈ 30 % grey (sRGB), steam near white. */
const ASH: [number, number, number] = [0.075, 0.066, 0.06];
const STEAM: [number, number, number] = [0.72, 0.75, 0.78];

function hash(i: number): number {
  const x = Math.sin(i * 127.1 + 311.7) * 43758.5453;
  return x - Math.floor(x);
}

/** Share of steam in a column's colour: phreatomagmatic (Surtseyan, phreatic) columns are mostly steam. */
function steamShare(regime: string | undefined, style: string | null | undefined): number {
  if (regime === 'SURTSEYAN' || style === 'SURTSEYAN') return 0.8;
  if (style === 'PHREATIC') return 0.9;
  return 0;
}

/**
 * Eruption columns as convecting clouds of lit billows (see Billows.tsx): a column whose radius grows
 * with height by entrainment, rising billows that churn upward, an umbrella spreading from the
 * neutral-buoyancy height to the top, and ash drifting downwind from it. Ash columns are dark grey,
 * phreatomagmatic ones white with steam; the sun lights the side facing it.
 */
export function EruptionColumn({ world }: { world: WorldInfo }) {
  const out = useRef<Billow[]>([]);
  const radius = useRef(new Map<string, number>());

  useFrame(({ clock }) => {
    const st = useStore.getState();
    const billows = out.current;
    billows.length = 0;
    if (!st.showAtmosphere || !st.state) return;
    const vExag = st.verticalExaggeration;
    const dExag = st.deformationExaggeration;
    const t = clock.elapsedTime;
    const wind = st.state.world.wind ?? { speed: 0, bearingDeg: 0 };
    const wr = (wind.bearingDeg * Math.PI) / 180;
    // the wind blows towards its bearing, clockwise from north (map +y)
    const wx = Math.sin(wr);
    const wy = Math.cos(wr);
    const lean = 0.25 * Math.min(2, wind.speed / 10);
    // the server's plume radius (latest plume event per volcano), when there is one
    radius.current.clear();
    for (let k = st.events.length - 1, seen = 0; k >= 0 && seen < 40; k--) {
      const e = st.events[k] as SimEvent;
      if (e.kind !== 'plume') continue;
      seen++;
      if (!radius.current.has(e.volcanoId)) radius.current.set(e.volcanoId, e.radius);
    }
    let seed = 0;
    for (const v of world.volcanoes) {
      const vs = st.state.volcanoes[v.id];
      const vent = v.vents[0];
      if (!vs?.plume || !vent) continue;
      const base = displayZ(world, vent.at[0], vent.at[1], vExag, dExag);
      const H = Math.max(150, vs.plume.topZ - base / vExag); // physical column height (m)
      const Hnb = H * NEUTRAL;
      const r0 = Math.max(10, vent.radius ?? 20);
      const rServer = radius.current.get(v.id);
      const k = rServer && rServer > 0 ? rServer / (r0 + SPREAD * Hnb) : 1;
      const b = (z: number) => (r0 + SPREAD * z) * Math.min(3, Math.max(0.33, k));
      const s = steamShare(vs.chamber.regime, vs.alert.style);
      const col: [number, number, number] = [ASH[0] + (STEAM[0] - ASH[0]) * s, ASH[1] + (STEAM[1] - ASH[1]) * s, ASH[2] + (STEAM[2] - ASH[2]) * s];
      const axis = (z: number): [number, number] => [vent.at[0] + wx * z * lean, vent.at[1] + wy * z * lean];
      // rising column: billows climb at a steady pace and wrap, so the column churns upward
      for (let i = 0; i < COLUMN && billows.length < MAX_BILLOWS; i++, seed++) {
        const phase = (i / COLUMN + t * 0.035 + hash(seed) * 0.02) % 1;
        const z = phase * Hnb;
        const r = b(z);
        const [ax, ay] = axis(z);
        const ang = hash(seed + 7) * Math.PI * 2 + t * 0.05;
        const off = r * 0.5 * Math.sqrt(hash(seed + 13));
        const x = ax + Math.cos(ang) * off;
        const y = ay + Math.sin(ang) * off;
        const zz = base + z * vExag;
        billows.push({ x, y: zz, z: -y, size: r * 1.6, flat: 1, cx: ax, cy: zz, cz: -ay, r: col[0], g: col[1], b: col[2], fade: 0.85 + 0.15 * phase, seed });
      }
      // umbrella: spreading at neutral buoyancy up to the top, stretched downwind
      const bTop = b(Hnb);
      const Ru = 3.5 * bTop;
      const [ux, uy] = axis(Hnb);
      const uzMid = base + ((Hnb + H) / 2) * vExag;
      for (let i = 0; i < UMBRELLA && billows.length < MAX_BILLOWS; i++, seed++) {
        const u = Math.sqrt(hash(seed + 3));
        const ang = hash(seed + 5) * Math.PI * 2;
        const rr = u * Ru;
        const down = u * Ru * 0.6;
        const x = ux + Math.cos(ang) * rr + wx * down;
        const y = uy + Math.sin(ang) * rr + wy * down;
        const zz = base + (Hnb + (H - Hnb) * (0.35 + 0.65 * hash(seed + 9)) * (1 - 0.5 * u)) * vExag;
        billows.push({ x, y: zz, z: -y, size: bTop * (1.1 + 0.6 * u), flat: 0.55, cx: ux, cy: uzMid - (H - Hnb) * 0.3 * vExag, cz: -uy, r: col[0], g: col[1], b: col[2], fade: 0.95, seed });
      }
      // ash drifting downwind from the umbrella, slowly settling (steam dissipates instead)
      for (let i = 0; i < DRIFT && billows.length < MAX_BILLOWS && s < 0.5; i++, seed++) {
        const f = (i / DRIFT + t * 0.004) % 1;
        const d = Ru + f * Math.max(3000, 12 * Ru);
        const lateral = (hash(seed + 21) - 0.5) * (Ru * 0.8 + d * 0.12);
        const x = ux + wx * d - wy * lateral;
        const y = uy + wy * d + wx * lateral;
        const zz = base + Hnb * (1 - 0.25 * f) * vExag;
        // overlapping, widening and thinning downwind: a continuous drifting cloud, not separate puffs
        billows.push({ x, y: zz, z: -y, size: Ru * (0.7 + f * 1.3), flat: 0.42, cx: x, cy: zz - bTop * vExag, cz: -y, r: col[0] * 1.3, g: col[1] * 1.3, b: col[2] * 1.3, fade: 0.9 - 0.5 * f, seed });
      }
    }
  });

  return <BillowLayer source={() => out.current} max={MAX_BILLOWS} opacity={0.8} renderOrder={6} />;
}
