import { useFrame } from '@react-three/fiber';
import { useMemo, useRef } from 'react';
import * as THREE from 'three';
import { BillowLayer, type Billow } from './Billows';

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
const PER_CLOUD = 28;
const MAX_BILLOWS = 160;
/** Lobes travel from tail to head at this share of the cloud length per second (wall time). */
const ADVECT = 0.06;

/**
 * Billowing clouds over oriented footprints, drawn as lit billows (see Billows.tsx): lobes laid out
 * along each cloud, a few across its width, stacked into tiers at the head where the flow rises
 * highest, hugging the ground at the tail. The lobes travel along the flow and wrap, so the cloud
 * visibly moves with it; the sun lights the side of each lobe facing it. One draw call for all clouds,
 * no custom shader (WebGPU and WebGL2 alike). Reusable for any cloud with a footprint.
 */
export function VolumeCloud({ clouds, color = '#857868' }: { clouds: () => CloudShape[]; color?: string }) {
  const out = useRef<Billow[]>([]);
  const base = useMemo(() => new THREE.Color(color), [color]);

  useFrame(({ clock }) => {
    const t = clock.elapsedTime;
    const billows = out.current;
    billows.length = 0;
    let seed = 0;
    for (const cl of clouds()) {
      if (billows.length >= MAX_BILLOWS) break;
      const length = 2 * cl.halfLength;
      const width = 2 * cl.halfWidth;
      // stations along the flow, a lobe or more across it
      const stations = Math.max(3, Math.min(10, Math.round(length / Math.max(1, width * 0.6))));
      const across = width > cl.height * 1.5 ? 2 : 1;
      const midGround = cl.ground(cl.x, cl.y);
      let own = 0;
      for (let k = 0; k < stations && own < PER_CLOUD && billows.length < MAX_BILLOWS; k++) {
        // u from tail (0) to head (1), advected forward and wrapping
        const u = (k / stations + t * ADVECT) % 1;
        const along = (u * 2 - 1) * cl.halfLength * 0.9 * cl.head;
        // the head is tallest; the tail a low ash sheet
        const h = cl.height * (0.25 + 0.75 * Math.pow(u, 1.3));
        const tiers = u > 0.7 ? 2 : 1;
        for (let a = 0; a < across && billows.length < MAX_BILLOWS; a++) {
          for (let tier = 0; tier < tiers && own < PER_CLOUD && billows.length < MAX_BILLOWS; tier++, seed++, own++) {
            const side = across === 1 ? 0 : (a === 0 ? -0.5 : 0.5) * cl.halfWidth;
            const jitter = (Math.sin(seed * 12.9898) * 0.5) * cl.halfWidth * 0.3;
            const x = cl.x + cl.ax * along - cl.ay * (side + jitter);
            const y = cl.y + cl.ay * along + cl.ax * (side + jitter);
            const size = Math.max(cl.halfWidth * 0.9, h * (tier === 0 ? 1.15 : 0.9));
            const ground = cl.ground(x, y);
            const zc = ground + size * (0.36 + tier * 0.55);
            billows.push({
              x,
              y: zc,
              z: -y,
              size,
              flat: 0.82,
              // the mass's centre: low over the middle of the footprint, so tops and the sunny side light up
              cx: cl.x,
              cy: midGround + cl.height * 0.25,
              cz: -cl.y,
              r: base.r,
              g: base.g,
              b: base.b,
              fade: (0.55 + 0.45 * u) * (0.35 + 0.65 * cl.opacity) * 1.6,
              seed,
            });
          }
        }
      }
    }
  });

  return <BillowLayer source={() => out.current} max={MAX_BILLOWS} opacity={0.85} renderOrder={8} />;
}
