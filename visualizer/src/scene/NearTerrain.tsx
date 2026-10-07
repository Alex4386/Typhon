import { useFrame, type ThreeEvent } from '@react-three/fiber';
import { useEffect, useRef, useState } from 'react';
import * as THREE from 'three';
import { notATap } from '../camera/gestures';
import type { WorldInfo, XY } from '../protocol/messages';
import { tileKey, useStore } from '../store/store';
import { coreGroundMeshes, displayColours, displayHeights, groundMaterial, refinedTiles, sceneProbe } from './Terrain';
import { valueNoise } from './groundColor';
import { refinedHeights, type HeightAt } from './nearRefine';
import { viewFocus } from './terrainMath';

/** Refined vertices per column (10 m columns → 2.5 m). */
const K = 4;
/** Core tiles whose centre lies within this distance (m) of the view focus are refined. */
const RADIUS_M = 700;
/** Refinement only while the camera is this close to the ground (m, unexaggerated). */
const MAX_HEIGHT_M = 2500;
/** At most this many tiles refined at once. */
const MAX_TILES = 36;
const CHECK_MS = 400;

/** What the refinement saw at its last check (inspect as window.__typhonNear). */
export const nearDebug = { above: Number.NaN, wanted: 0, cached: 0, refined: 0 };
(globalThis as unknown as { __typhonNear: typeof nearDebug }).__typhonNear = nearDebug;

interface Props {
  world: WorldInfo;
  onPick: (xy: XY, e: ThreeEvent<MouseEvent>) => void;
}

interface Refined {
  key: string;
  tx: number;
  ty: number;
  /** The core data it was built from (rebuilt when the core tile changes). */
  heights: Float32Array;
  colours: Float32Array;
  geo: THREE.BufferGeometry;
}

/**
 * The ground near the camera at four times the column resolution: the core tiles around the view focus
 * are redrawn as a smooth (Catmull-Rom) surface through their column heights with bilinear colours and a
 * fine grain, in place of their 10 m facets, wherever the camera goes. Far tiles and crater-detail tiles
 * keep their own meshes. A drawing refinement only: the simulation data stays the columns'.
 */
export function NearTerrain({ world, onPick }: Props) {
  const [tiles, setTiles] = useState<Refined[]>([]);
  const last = useRef(0);
  const focusDir = useRef(new THREE.Vector3());

  useFrame(({ camera }) => {
    const now = performance.now();
    if (now - last.current < CHECK_MS) return;
    last.current = now;
    const st = useStore.getState();
    const t = world.tileSize;
    const span = t * world.cellSize;
    const ground = Number.isFinite(sceneProbe.cameraGround) ? sceneProbe.cameraGround : 0;
    const above = (camera.position.y - ground) / st.verticalExaggeration;
    const wanted: { key: string; tx: number; ty: number; d: number }[] = [];
    if (above < MAX_HEIGHT_M) {
      const [fx, fy] = viewFocus(camera.position, camera.getWorldDirection(focusDir.current), ground);
      const r = Math.ceil(RADIUS_M / span);
      const ctx = Math.floor((fx - world.origin[0]) / span);
      const cty = Math.floor((fy - world.origin[1]) / span);
      for (let ty = cty - r; ty <= cty + r; ty++) {
        for (let tx = ctx - r; tx <= ctx + r; tx++) {
          const key = tileKey(tx, ty);
          if (!displayColours.has(key) || !displayHeights.has(key)) continue;
          const cx = world.origin[0] + (tx + 0.5) * span;
          const cy = world.origin[1] + (ty + 0.5) * span;
          const d = Math.hypot(cx - fx, cy - fy);
          if (d <= RADIUS_M) wanted.push({ key, tx, ty, d });
        }
      }
      wanted.sort((a, b) => a.d - b.d);
      wanted.length = Math.min(wanted.length, MAX_TILES);
    }
    nearDebug.above = above;
    nearDebug.wanted = wanted.length;
    nearDebug.cached = displayColours.size;
    nearDebug.refined = tiles.length;
    const keep = new Set(wanted.map((w) => w.key));
    let changed = false;
    const next: Refined[] = [];
    for (const r of tiles) {
      const h = displayHeights.get(r.key);
      const c = displayColours.get(r.key);
      if (keep.has(r.key) && h && c && h.z === r.heights && c === r.colours) next.push(r);
      else {
        r.geo.dispose();
        changed = true;
      }
    }
    // build at most a few tiles per check, nearest first
    let built = 0;
    for (const w of wanted) {
      if (next.some((r) => r.key === w.key)) continue;
      if (built >= 4) break;
      const g = build(world, w.tx, w.ty);
      if (!g) continue;
      next.push(g);
      built++;
      changed = true;
    }
    if (!changed) return;
    // the refined tiles hide their coarse ground
    const now2 = new Set(next.map((r) => r.key));
    for (const key of refinedTiles) {
      if (!now2.has(key)) {
        refinedTiles.delete(key);
        const m = coreGroundMeshes.get(key);
        if (m && displayHeights.has(key)) m.visible = true;
      }
    }
    for (const key of now2) {
      refinedTiles.add(key);
      const m = coreGroundMeshes.get(key);
      if (m) m.visible = false;
    }
    setTiles(next);
  });

  // leaving: give every tile its coarse ground back
  useEffect(
    () => () => {
      for (const key of refinedTiles) {
        const m = coreGroundMeshes.get(key);
        if (m) m.visible = true;
      }
      refinedTiles.clear();
    },
    [],
  );

  return (
    <group>
      {tiles.map((r) => (
        <mesh
          key={r.key}
          geometry={r.geo}
          userData={{ occluder: true }}
          receiveShadow
          onClick={(e) => {
            if (notATap(e)) return;
            e.stopPropagation();
            onPick([e.point.x, -e.point.z], e);
          }}
        >
          <primitive object={groundMaterial} attach="material" />
        </mesh>
      ))}
    </group>
  );
}

/**
 * Drawn height of global column vertex (i, j) from the cached core tiles. Where the neighbouring tile is not
 * loaded (yet), the nearest vertex of the tile being refined stands in, so the refined surface never gets NaN.
 */
function heightReader(world: WorldInfo, ownTx: number, ownTy: number): HeightAt {
  const t = world.tileSize;
  const n = t + 1;
  const own = displayHeights.get(tileKey(ownTx, ownTy));
  return (i, j) => {
    const tx = Math.floor(i / t);
    const ty = Math.floor(j / t);
    const h = displayHeights.get(tileKey(tx, ty));
    const v = h ? h.z[(j - ty * t) * n + (i - tx * t)] : Number.NaN;
    if (Number.isFinite(v) || !own) return v;
    const a = Math.max(0, Math.min(t, i - ownTx * t));
    const b = Math.max(0, Math.min(t, j - ownTy * t));
    return own.z[b * n + a];
  };
}

function build(world: WorldInfo, tx: number, ty: number): Refined | null {
  const key = tileKey(tx, ty);
  const h = displayHeights.get(key);
  const colours = displayColours.get(key);
  if (!h || !colours) return null;
  const t = world.tileSize;
  const n = t + 1;
  const m = t * K + 1;
  const w = m + 2;
  const reader = heightReader(world, tx, ty);
  const heights = refinedHeights(reader, tx, ty, t, K);
  for (let k = 0; k < heights.length; k++) if (!Number.isFinite(heights[k])) return null;
  // Along the tile's edges the surface must be the coarse mesh's straight line between column points, or a
  // coarse neighbour leaves a crack (a sawtooth seam): blend from the spline inside to the straight edge
  // over the outermost column (the ring for normals is blended the same way).
  for (let b = -1; b <= m; b++) {
    for (let a = -1; a <= m; a++) {
      const edge = Math.min(a, m - 1 - a, b, m - 1 - b) / K; // columns from the nearest tile edge
      if (edge >= 1) continue;
      const fi = tx * t + a / K;
      const fj = ty * t + b / K;
      const i0 = Math.floor(fi);
      const j0 = Math.floor(fj);
      const u = fi - i0;
      const v = fj - j0;
      const lin = (reader(i0, j0) * (1 - u) + reader(i0 + 1, j0) * u) * (1 - v) + (reader(i0, j0 + 1) * (1 - u) + reader(i0 + 1, j0 + 1) * u) * v;
      if (!Number.isFinite(lin)) continue;
      const k = Math.max(0, edge);
      const smooth = k * k * (3 - 2 * k);
      const idx = (b + 1) * w + (a + 1);
      heights[idx] = lin + (heights[idx] - lin) * smooth;
    }
  }
  const cell = world.cellSize / K;
  const pos = new Float32Array(m * m * 3);
  const col = new Float32Array(m * m * 3);
  const nor = new Float32Array(m * m * 3);
  for (let b = 0; b < m; b++) {
    for (let a = 0; a < m; a++) {
      const v = b * m + a;
      const x = world.origin[0] + (tx * t + a / K + 0.5) * world.cellSize;
      const y = world.origin[1] + (ty * t + b / K + 0.5) * world.cellSize;
      const z = heights[(b + 1) * w + (a + 1)];
      pos[3 * v] = x;
      pos[3 * v + 1] = z;
      pos[3 * v + 2] = -y;
      // normal from the refined surface (its ring reaches into the neighbouring tiles: no seams)
      const hx = (heights[(b + 1) * w + (a + 2)] - heights[(b + 1) * w + a]) / (2 * cell);
      const hy = (heights[(b + 2) * w + (a + 1)] - heights[b * w + (a + 1)]) / (2 * cell);
      const inv = 1 / Math.hypot(hx, 1, hy);
      nor[3 * v] = -hx * inv;
      nor[3 * v + 1] = inv;
      nor[3 * v + 2] = hy * inv;
      // colour: bilinear between the four column vertices, with a fine grain below the column scale
      const fa = a / K;
      const fb = b / K;
      const a0 = Math.min(t - 1, Math.floor(fa));
      const b0 = Math.min(t - 1, Math.floor(fb));
      const u = fa - a0;
      const vv = fb - b0;
      const grain = 0.93 + 0.14 * (valueNoise(x + 911, y - 377, 3.5) * 0.6 + valueNoise(x - 41, y + 83, 1.5) * 0.4);
      for (let k = 0; k < 3; k++) {
        const c00 = colours[3 * (b0 * n + a0) + k];
        const c10 = colours[3 * (b0 * n + a0 + 1) + k];
        const c01 = colours[3 * ((b0 + 1) * n + a0) + k];
        const c11 = colours[3 * ((b0 + 1) * n + a0 + 1) + k];
        const cv = (c00 * (1 - u) + c10 * u) * (1 - vv) + (c01 * (1 - u) + c11 * u) * vv;
        col[3 * v + k] = Math.min(1, cv * grain);
      }
    }
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  geo.setAttribute('color', new THREE.BufferAttribute(col, 3));
  geo.setAttribute('normal', new THREE.BufferAttribute(nor, 3));
  const idx = new Uint32Array((m - 1) * (m - 1) * 6);
  let o = 0;
  for (let b = 0; b < m - 1; b++) {
    for (let a = 0; a < m - 1; a++) {
      const v = b * m + a;
      idx[o++] = v;
      idx[o++] = v + 1;
      idx[o++] = v + m;
      idx[o++] = v + 1;
      idx[o++] = v + m + 1;
      idx[o++] = v + m;
    }
  }
  geo.setIndex(new THREE.BufferAttribute(idx, 1));
  geo.computeBoundingSphere();
  geo.computeBoundingBox();
  return { key, tx, ty, heights: h.z, colours, geo };
}
