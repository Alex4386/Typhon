import { useFrame } from '@react-three/fiber';
import { useRef } from 'react';
import { Field } from '../protocol/fields';
import type { WorldInfo } from '../protocol/messages';
import { getTile, useStore } from '../store/store';
import { easeClusters, flowClusters, type FlowCluster } from './pdcClusters';
import { displayZ } from './Terrain';
import { VolumeCloud, type CloudShape } from './VolumeCloud';

/** Clusters are recomputed this often (ms of wall time). */
const UPDATE_MS = 500;
/** A vanished cluster fades out over this long (s of wall time). */
const FADE_S = 2.5;

interface Tracked extends FlowCluster {
  opacity: number;
  /** Wall time (s) the cluster was last present in the field. */
  seen: number;
}

/**
 * Pyroclastic density currents as clouds: the streamed PDC depth field is clustered into a few
 * flows (see pdcClusters.ts), each drawn as one billowing ash cloud along its footprint, tallest at
 * its head (the end farthest from the vent), eased between updates and fading as the flow stops.
 * Lahars get no cloud: they are a mud sheet on the ground (Terrain's flow layer).
 */
export function SurgeClouds({ world }: { world: WorldInfo }) {
  const state = useRef<{ at: number; last: number; tracked: Tracked[] }>({ at: 0, last: 0, tracked: [] });
  const shapes = useRef<CloudShape[]>([]);

  useFrame(({ clock }) => {
    const now = performance.now();
    const wall = clock.elapsedTime;
    const st = state.current;
    const s = useStore.getState();
    const show = s.showAtmosphere;
    if (now - st.at >= UPDATE_MS) {
      st.at = now;
      const fresh = show ? currentClusters(world) : [];
      const eased = easeClusters(st.tracked, fresh, 0.45);
      const next: Tracked[] = eased.map((c) => {
        const prev = st.tracked.find((p) => Math.hypot(p.x - c.x, p.y - c.y) < c.halfLength + p.halfLength);
        return { ...c, opacity: prev ? prev.opacity : 0.15, seen: wall };
      });
      // vanished clusters linger while they fade
      for (const p of st.tracked) {
        if (next.some((n) => Math.hypot(n.x - p.x, n.y - p.y) < n.halfLength + p.halfLength)) continue;
        if (wall - p.seen < FADE_S) next.push(p);
      }
      st.tracked = next;
    }
    const vExag = s.verticalExaggeration;
    const dExag = s.deformationExaggeration;
    const dt = Math.min(0.1, Math.max(0, wall - st.last));
    st.last = wall;
    const out: CloudShape[] = [];
    for (const c of st.tracked) {
      const gone = wall - c.seen;
      const target = gone > 0.01 ? Math.max(0, 1 - gone / FADE_S) : 1;
      c.opacity += (target - c.opacity) * Math.min(1, dt * 2);
      if (c.opacity < 0.02) continue;
      // a surge's ash cloud stands far above its basal flow: a few times the flow depth plus a share of its width
      const height = Math.min(900, Math.max(60, 6 * c.maxDepthM + 0.35 * c.halfWidth * 2)) * vExag;
      out.push({
        x: c.x,
        y: c.y,
        ax: c.ax,
        ay: c.ay,
        halfLength: c.halfLength,
        halfWidth: c.halfWidth,
        height,
        head: headSign(world, c),
        ground: (x, y) => {
          const g = displayZ(world, x, y, vExag, dExag);
          return world.hasSea === false ? g : Math.max(g, world.seaLevel * vExag);
        },
        opacity: c.opacity,
      });
    }
    shapes.current = out;
  });

  // grey-brown ash, dustier than the eruption column
  return <VolumeCloud clouds={() => shapes.current} color="#7f7264" />;
}

/** The end of the cluster's axis farther from the nearest vent leads the flow. */
function headSign(world: WorldInfo, c: FlowCluster): 1 | -1 {
  let vx = 0;
  let vy = 0;
  let best = Infinity;
  for (const v of world.volcanoes) {
    for (const vent of v.vents) {
      const d = Math.hypot(vent.at[0] - c.x, vent.at[1] - c.y);
      if (d < best) {
        best = d;
        vx = vent.at[0];
        vy = vent.at[1];
      }
    }
  }
  if (!Number.isFinite(best)) return 1;
  return (c.x - vx) * c.ax + (c.y - vy) * c.ay >= 0 ? 1 : -1;
}

/** Clusters of the PDC depth field over the core tiles that currently hold any flow. */
function currentClusters(world: WorldInfo): FlowCluster[] {
  const t = world.tileSize;
  let minTx = Infinity;
  let minTy = Infinity;
  let maxTx = -Infinity;
  let maxTy = -Infinity;
  const active = new Map<string, Float32Array>();
  for (let ty = world.tiles.minTy; ty <= world.tiles.maxTy; ty++) {
    for (let tx = world.tiles.minTx; tx <= world.tiles.maxTx; tx++) {
      const f = getTile(Field.PdcDepth, tx, ty);
      if (!f) continue;
      let any = false;
      for (let k = 0; k < f.values.length; k++) {
        if (f.values[k] > 0.3) {
          any = true;
          break;
        }
      }
      if (!any) continue;
      active.set(`${tx},${ty}`, f.values);
      minTx = Math.min(minTx, tx);
      minTy = Math.min(minTy, ty);
      maxTx = Math.max(maxTx, tx);
      maxTy = Math.max(maxTy, ty);
    }
  }
  if (active.size === 0) return [];
  const nx = (maxTx - minTx + 1) * t;
  const ny = (maxTy - minTy + 1) * t;
  return flowClusters(
    {
      originX: world.origin[0] + minTx * t * world.cellSize,
      originY: world.origin[1] + minTy * t * world.cellSize,
      cellSize: world.cellSize,
      nx,
      ny,
      depth: (i, j) => {
        const tx = minTx + Math.floor(i / t);
        const ty = minTy + Math.floor(j / t);
        const v = active.get(`${tx},${ty}`);
        return v ? v[(j % t) * t + (i % t)] : 0;
      },
    },
    { block: Math.max(1, Math.round(40 / world.cellSize)), maxClusters: 12 },
  );
}
