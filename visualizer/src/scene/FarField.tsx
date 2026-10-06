import { waterMaterial } from './water';
import { useFrame } from '@react-three/fiber';
import { useEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import type { WorldInfo } from '../protocol/messages';
import { getLodTile, tileKey, useStore } from '../store/store';
import { BATHY, HYPSO, ramp, type RGB } from '../util/color';
import { worldExtent } from '../util/world';
import {
  contextTerrain,
  farFieldElevation,
  median,
  onContextTerrain,
  setContextTerrain,
  stackedContext,
  stretch,
  type Domain,
  type Extrapolation,
  type LevelGrid,
} from './farFieldMath';
import { displayZ } from './Terrain';

/**
 * Vertices per axis of the far-field grid (the inner quarter spans the domain and is skipped): finer
 * when real context terrain is streamed, so its hills and valleys show.
 */
function gridSize(world: WorldInfo): number {
  return world.lod?.levels.some((l) => l.kind === 'context') ? 161 : 97;
}
/** How often (ms) edge changes are checked for a rebuild. */
const CHECK_MS = 1500;

function lin(c: number): number {
  return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

/** Far ground: shares the look of the domain's ground but sits behind it where they overlap. */
const farMaterial = new THREE.MeshLambertMaterial({ vertexColors: true, polygonOffset: true, polygonOffsetFactor: 2, polygonOffsetUnits: 4 });
/** The open sea beyond the simulated domain: deep water shading (see water.ts). */
const seaMaterial = waterMaterial({ perVertexDepth: false, depthM: 300 });
seaMaterial.polygonOffset = true;
seaMaterial.polygonOffsetFactor = 2;
seaMaterial.polygonOffsetUnits = 4;

/** The displayed-vertex rectangle of the domain (cell centres of its outermost columns). */
export function domainOf(world: WorldInfo): Domain {
  const e = worldExtent(world);
  const h = world.cellSize / 2;
  return { minX: e.minX + h, minY: e.minY + h, maxX: e.maxX - h, maxY: e.maxY - h };
}

/** Far-field radius (m from the domain centre): out to where the fog hides everything. */
export function farRadius(world: WorldInfo): number {
  const e = worldExtent(world);

  const span = Math.max(e.maxX - e.minX, e.maxY - e.minY);
  return Math.max(span * 5, 25_000);
}

/** Tile keys along the domain edge (their changes move the far field's seam). */
function edgeTiles(world: WorldInfo): string[] {
  const out: string[] = [];
  const { minTx, maxTx, minTy, maxTy } = world.tiles;
  for (let tx = minTx; tx <= maxTx; tx++) out.push(tileKey(tx, minTy), tileKey(tx, maxTy));
  for (let ty = minTy + 1; ty < maxTy; ty++) out.push(tileKey(minTx, ty), tileKey(maxTx, ty));
  return out;
}

/**
 * Ground (and sea) beyond the simulated domain, out to the horizon fog, so a small world sits in a
 * landscape instead of floating as a slab. Continues the domain's edge heights outward (or uses
 * context terrain once registered, see {@link setContextTerrain}); not pickable.
 */
/** The coarse context levels as grids, finest first (§5.5). */
function contextGrids(world: WorldInfo): LevelGrid[] {
  const t = world.tileSize;
  return (world.lod?.levels ?? [])
    .filter((l) => l.kind === 'context')
    .sort((a, b) => a.level - b.level)
    .map((l) => ({
      origin: world.origin,
      cellSize: l.cellSize,
      cell(i: number, j: number) {
        const tx = Math.floor(i / t);
        const ty = Math.floor(j / t);
        const tile = getLodTile(l.level, tx, ty);
        if (!tile) return undefined;
        const v = tile.values[(j - ty * t) * t + (i - tx * t)];
        return Number.isFinite(v) ? v : undefined;
      },
    }));
}

export function FarField({ world }: { world: WorldInfo }) {
  const N = gridSize(world);
  // real terrain around the core, from the server's coarse context levels
  useEffect(() => {
    const grids = contextGrids(world);
    if (grids.length === 0) return;
    const stack = stackedContext(grids, () => useStore.getState().contextRevision);
    const ext = world.lod?.extent;
    // beyond the described landscape (which can end inside the fog distance) its edge carries on
    const inset = grids[grids.length - 1].cellSize;
    setContextTerrain(
      ext
        ? {
            sample: (x, y) => stack.sample(Math.min(ext[2] - inset, Math.max(ext[0] + inset, x)), Math.min(ext[3] - inset, Math.max(ext[1] + inset, y))),
            get version() {
              return stack.version;
            },
          }
        : stack,
    );
    return () => setContextTerrain(null);
  }, [world]);
  const geo = useMemo(() => {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(N * N * 3), 3));
    g.setAttribute('color', new THREE.BufferAttribute(new Float32Array(N * N * 3), 3));
    g.setAttribute('normal', new THREE.BufferAttribute(new Float32Array(N * N * 3), 3));
    const idx: number[] = [];
    for (let b = 0; b < N - 1; b++) {
      for (let a = 0; a < N - 1; a++) {
        // skip cells within the domain rectangle (the domain's own terrain is drawn there)
        const ins = (p: number, q: number) => Math.abs((p / (N - 1)) * 2 - 1) <= 0.25 + 1e-9 && Math.abs((q / (N - 1)) * 2 - 1) <= 0.25 + 1e-9;
        if (ins(a, b) && ins(a + 1, b) && ins(a, b + 1) && ins(a + 1, b + 1)) continue;
        const v = b * N + a;
        idx.push(v, v + 1, v + N, v + 1, v + N + 1, v + N);
      }
    }
    g.setIndex(idx);
    return g;
  }, [N]);
  const sea = useMemo(() => {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(N * N * 3), 3));
    g.setIndex(geo.getIndex());
    return g;
  }, [geo, N]);
  const seaMesh = useRef<THREE.Mesh>(null);
  const state = useRef({ sig: '', at: 0, ctx: 0 });
  useEffect(() => onContextTerrain(() => (state.current.sig = '')), []);
  useEffect(() => () => {
    geo.dispose();
    sea.dispose();
  }, [geo, sea]);
  const edges = useMemo(() => edgeTiles(world), [world]);

  useFrame(() => {
    const now = performance.now();
    const s = state.current;
    if (now - s.at < CHECK_MS && s.sig !== '') return;
    s.at = now;
    const st = useStore.getState();
    const vExag = st.verticalExaggeration;
    const dExag = st.deformationExaggeration;
    let rev = 0;
    for (const k of edges) rev += st.tileRevision[k] ?? 0;
    const ctx = contextTerrain();
    const sig = `${rev}|${vExag}|${dExag}|${ctx?.version ?? -1}`;
    if (sig === s.sig) return;
    s.sig = sig;
    build(world, geo, sea, vExag, dExag, N);
    if (seaMesh.current) seaMesh.current.visible = sea.userData.any === true;
  });

  return (
    <group>
      <mesh geometry={geo} material={farMaterial} raycast={() => null} renderOrder={-1} />
      <mesh ref={seaMesh} geometry={sea} material={seaMaterial} raycast={() => null} visible={false} renderOrder={-1} />
    </group>
  );
}

/** Lowest edge height along the domain boundary within the neighbouring grid spacing of (x, y). */
function edgeMin(edgeH: (x: number, y: number) => number, x: number, y: number, x0: number, x1: number, y0: number, y1: number, d: Domain, step: number): number {
  let m = edgeH(x, y);
  const onX = Math.abs(y - d.minY) < 1e-6 || Math.abs(y - d.maxY) < 1e-6;
  const onY = Math.abs(x - d.minX) < 1e-6 || Math.abs(x - d.maxX) < 1e-6;
  if (onX) for (let px = Math.max(d.minX, x0); px <= Math.min(d.maxX, x1); px += step) m = Math.min(m, edgeH(px, y));
  if (onY) for (let py = Math.max(d.minY, y0); py <= Math.min(d.maxY, y1); py += step) m = Math.min(m, edgeH(x, py));
  return m;
}

function build(world: WorldInfo, geo: THREE.BufferGeometry, sea: THREE.BufferGeometry, vExag: number, dExag: number, N: number): void {
  const d = domainOf(world);
  const cx = (d.minX + d.maxX) / 2;
  const cy = (d.minY + d.maxY) / 2;
  const hx = (d.maxX - d.minX) / 2;
  const hy = (d.maxY - d.minY) / 2;
  const R = farRadius(world);
  // displayed edge heights (m), sampled along the domain rectangle
  const edgeH = (x: number, y: number) => displayZ(world, x, y, vExag, dExag) / vExag;
  const samples: number[] = [];
  for (let k = 0; k <= 64; k++) {
    const f = k / 64;
    samples.push(edgeH(d.minX + f * 2 * hx, d.minY), edgeH(d.minX + f * 2 * hx, d.maxY), edgeH(d.minX, d.minY + f * 2 * hy), edgeH(d.maxX, d.minY + f * 2 * hy));
  }
  const [eLo, eHi] = world.elevationRange;
  const relief = Math.max(50, eHi - eLo);
  const base = median(samples);
  const underSea = world.hasSea !== false && Number.isFinite(world.seaLevel) && base < world.seaLevel;
  const ex: Extrapolation = {
    edge: edgeH,
    base,
    falloff: Math.max(hx, hy) * 1.5,
    hills: relief * 0.08,
    hillScale: Math.max(hx, hy) * 0.9,
    ...(underSea ? { ceiling: world.seaLevel - 5 } : {}),
  };
  const ctx = contextTerrain();
  const pos = geo.getAttribute('position') as THREE.BufferAttribute;
  const col = geo.getAttribute('color') as THREE.BufferAttribute;
  const sp = sea.getAttribute('position') as THREE.BufferAttribute;
  const seaZ = world.seaLevel * vExag;
  const c: RGB = [0, 0, 0];
  let anySea = false;
  const heights = new Float32Array(N * N);
  const xs = new Float32Array(N);
  const ys = new Float32Array(N);
  for (let a = 0; a < N; a++) xs[a] = stretch((a / (N - 1)) * 2 - 1, cx, hx, R);
  for (let b = 0; b < N; b++) ys[b] = stretch((b / (N - 1)) * 2 - 1, cy, hy, R);
  for (let b = 0; b < N; b++) {
    for (let a = 0; a < N; a++) {
      const x = xs[a];
      const y = ys[b];
      const v = b * N + a;
      const inside = x > d.minX + 1e-6 && x < d.maxX - 1e-6 && y > d.minY + 1e-6 && y < d.maxY - 1e-6;
      // inside the domain: tuck under the drawn terrain; on and beyond the edge: continue it
      const onEdge = !inside && x >= d.minX - 1e-6 && x <= d.maxX + 1e-6 && y >= d.minY - 1e-6 && y <= d.maxY + 1e-6;
      // edge vertices: the lowest drawn edge height between their neighbours, so the coarse far
      // field never rises above the finely detailed domain edge (no slits of sky along the seam)
      const h = inside
        ? edgeH(x, y) - 30
        : onEdge
          ? edgeMin(edgeH, x, y, a > 0 ? xs[a - 1] : x, a < N - 1 ? xs[a + 1] : x, b > 0 ? ys[b - 1] : y, b < N - 1 ? ys[b + 1] : y, d, world.cellSize) - 1
          : farFieldElevation(d, x, y, ex, ctx);
      heights[v] = h;
      pos.setXYZ(v, x, h * vExag - (inside ? 0 : 0.5 * vExag), -y);
      // land below seaLevel in a world without sea (context terrain falling away) stays land
      if (h >= world.seaLevel || world.hasSea === false) ramp(HYPSO, Math.max(0, (h - world.seaLevel) / Math.max(1, eHi - world.seaLevel)), c);
      else {
        ramp(BATHY, (world.seaLevel - h) / Math.max(1, world.seaLevel - eLo), c);
        if (!inside) anySea = true;
      }
      col.setXYZ(v, lin(c[0]), lin(c[1]), lin(c[2]));
      sp.setXYZ(v, x, seaZ, -y);
    }
  }
  // normals from the height grid (non-uniform spacing)
  const nrm = geo.getAttribute('normal') as THREE.BufferAttribute;
  for (let b = 0; b < N; b++) {
    for (let a = 0; a < N; a++) {
      const a0 = Math.max(0, a - 1);
      const a1 = Math.min(N - 1, a + 1);
      const b0 = Math.max(0, b - 1);
      const b1 = Math.min(N - 1, b + 1);
      const hx2 = ((heights[b * N + a1] - heights[b * N + a0]) * vExag) / Math.max(1e-6, xs[a1] - xs[a0]);
      const hy2 = ((heights[b1 * N + a] - heights[b0 * N + a]) * vExag) / Math.max(1e-6, ys[b1] - ys[b0]);
      const inv = 1 / Math.hypot(hx2, 1, hy2);
      nrm.setXYZ(b * N + a, -hx2 * inv, inv, hy2 * inv);
    }
  }
  pos.needsUpdate = true;
  col.needsUpdate = true;
  nrm.needsUpdate = true;
  sp.needsUpdate = true;
  geo.computeBoundingSphere();
  sea.computeBoundingSphere();
  sea.userData.any = anySea;
}
