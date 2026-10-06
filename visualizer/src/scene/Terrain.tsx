import { useFrame, type ThreeEvent } from '@react-three/fiber';
import { useEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import { Field, type FieldId } from '../protocol/fields';
import type { WorldInfo, XY } from '../protocol/messages';
import { QUALITY, getTile, lodKey, tileKey, useStore, type SurfaceColorMode } from '../store/store';
import { waterMaterial } from './water';
import { BATHY, DIVERGING, HYPSO, THERMAL, hexToRgb, ramp, shadeFor, type RGB } from '../util/color';
import { interpolateGrid } from '../util/grid';
import { sampleColumn } from '../util/world';
import type { TileFrame } from '../protocol/frames';
import { bakedReader, clampedReader, elevationQuantum, gridReader, rebuildOrder, viewFocus } from './terrainMath';
import { perfStats } from './perf';
import { POND, SEA, compactWater, groundZ, seaSurfaceZ } from './waterIndex';
import { CRUST_RGB, crackPattern, crustLight, lavaSurfaceColor, weightedTemperature } from './lavaColor';
import { detailCovers, detailHeights, detailLevels, overlappingDetailTiles, refinement } from './detail';

/**
 * Time a frame may spend on tile rebuilds (ms; at least one runs per frame), to keep the view
 * responsive while data streams in. A slow machine rebuilds fewer tiles per frame instead of
 * dropping frames.
 */
const REBUILD_BUDGET_MS = 4;
/** A pending mesh rebuild and where it is on the map (world metres; nearest the view first). */
interface PendingRebuild {
  run: () => void;
  at?: [number, number];
}
const rebuildQueue = new Map<string, PendingRebuild>();

/**
 * Queues a mesh rebuild (replacing any pending one for `key`); drained within a time budget per frame,
 * nearest to where the camera looks first, so the ground the user sees and clicks is built first.
 */
export function queueRebuild(key: string, rebuild: () => void, at?: [number, number]): void {
  rebuildQueue.set(key, { run: rebuild, at });
}

export function cancelRebuild(key: string): void {
  rebuildQueue.delete(key);
}

/** The latest rebuild of each core tile, so a crater-detail tile can requeue the core under it. */
const coreJobs = new Map<string, PendingRebuild>();

/**
 * Requeues core tile `key`'s rebuild: its lava, lahar and water sheets sit on the drawn ground, which
 * under crater detail is the detail mesh, so they follow when a detail tile is rebuilt.
 */
export function requeueCore(key: string): void {
  const job = coreJobs.get(key);
  if (job) rebuildQueue.set(key, job);
}

/**
 * Height of the drawn crater-detail ground at world (x, y) (scene units), or undefined where no
 * detail tile is built (with these exaggerations).
 */
export function detailGroundAt(world: WorldInfo, x: number, y: number, vExag: number, dExag: number): number | undefined {
  if (detailHeights.size === 0) return undefined;
  const t = world.tileSize;
  for (const l of detailLevels(world)) {
    const c = world.cellSize / refinement(world, l);
    const di = (x - world.origin[0]) / c - 0.5;
    const dj = (y - world.origin[1]) / c - 0.5;
    const dtx = Math.floor(di / t);
    const dty = Math.floor(dj / t);
    const d = detailHeights.get(lodKey(l.level, dtx, dty));
    if (d && d.vExag === vExag && d.dExag === dExag) return interpolateGrid(d.z, t, di - dtx * t, dj - dty * t);
  }
  return undefined;
}

/**
 * Displayed ground heights per tile (scene units, after smoothing and exaggeration), by tile key:
 * vertex (a, b) ∈ [0, T]² sits on column (tx·T + a, ty·T + b). Camera collision and overlay placement
 * sample these so nothing ends up inside the smoothed surface that is actually drawn.
 */
const displayHeights = new Map<string, { z: Float32Array; vExag: number; dExag: number }>();

/**
 * Shared ground material. Double-sided so the surface stays visible from below; while the camera
 * is underground and x-ray is on it turns translucent so the chamber, conduits, dikes, hypocentres
 * and water table behind it can be seen. Lambert (diffuse only): the ground is rough rock and soil,
 * and per-pixel PBR shading of a screen-filling terrain was the largest fill cost of a frame.
 */
export const groundMaterial = new THREE.MeshLambertMaterial({ vertexColors: true, side: THREE.DoubleSide });
/** Per-frame facts about the camera that other scene parts read without store traffic. */
export const sceneProbe = { cameraGround: Number.NaN };

/** Lava thinner than this (m) tints the ground; thicker lava is drawn as a sheet over it. */
const LAVA_SHEET_MIN_M = 0.5;
/** Extra lift of the lava sheet above ground + thickness (m), so it never z-fights the ground. */
const LAVA_SHEET_LIFT_M = 0.6;
/** Sheet margin vertices sit this far under the ground (m). */
const LAVA_SHEET_TUCK_M = 0.5;

/** Ponded and flowing water: depth-aware water shading (see water.ts). */
const pondWater = waterMaterial({ perVertexDepth: true });
/**
 * Open sea over a core tile whose meshes are not built yet (at load, or while the rebuild queue
 * catches up): without it the background showed through in tile-shaped holes.
 */
const pendingSea = waterMaterial({ perVertexDepth: false, depthM: 60 });
/** Lahar: opaque mud (a translucent sheet only added overdraw and sorting trouble). */
const mudMaterial = new THREE.MeshLambertMaterial({ vertexColors: true });
/** Lahar sheet: shown from this depth (m), lifted at least this much over the ground (m). */
const LAHAR_MIN_M = 0.05;
/**
 * A tile whose sea is at least this deep everywhere (m) draws its sea as the tile's single quad instead
 * of a full grid: through 35 m of water almost nothing of the bed shows (transmittance e^−3.5 ≈ 0.03),
 * so the flat 2-triangle surface looks the same at a 4000th of the triangles.
 */
const DEEP_SEA_M = 35;
const LAHAR_LIFT_M = 0.35;

/** Groundwater table: a translucent cyan sheet `depth` below the ground. */
const waterTableMaterial = new THREE.MeshBasicMaterial({
  color: '#4cc9f0',
  transparent: true,
  opacity: 0.32,
  side: THREE.DoubleSide,
  depthWrite: false,
});

/** Reader for vertex (a, b) ∈ [−1, T+1]² of a tile, reaching into the 8 neighbouring tiles. */
type Reader = (a: number, b: number) => number;

function reader(field: FieldId, tx: number, ty: number, t: number, fallback: number): Reader {
  const near: (Float32Array | undefined)[] = [];
  for (let oy = -1; oy <= 1; oy++) for (let ox = -1; ox <= 1; ox++) near.push(getTile(field, tx + ox, ty + oy)?.values);
  const own = near[4];
  return (a, b) => {
    const ox = a < 0 ? -1 : a >= t ? 1 : 0;
    const oy = b < 0 ? -1 : b >= t ? 1 : 0;
    const v = near[(oy + 1) * 3 + ox + 1];
    if (v) return v[(b - oy * t) * t + (a - ox * t)];
    if (!own) return fallback;
    const ca = Math.min(t - 1, Math.max(0, a));
    const cb = Math.min(t - 1, Math.max(0, b));
    return own[cb * t + ca];
  };
}

/**
 * Display elevations for vertices (a, b) ∈ [−1, n]²: the field smoothed with a separable Gaussian
 * (σ = r columns, radius 2r). The engine's ground is stepped in whole blocks, which renders as
 * terraces on gentle slopes; smoothing over neighbouring tiles keeps tile seams continuous.
 */
export function smoothedReader(raw: Reader, n: number, r: number): Reader {
  if (r <= 0) return raw;
  const K = 2 * r;
  const lo = -1 - K;
  const m = n + 2 + 2 * K; // samples a ∈ [lo, n + K]
  const w: number[] = [];
  let wsum = 0;
  for (let k = -K; k <= K; k++) {
    const v = Math.exp(-(k * k) / (2 * r * r));
    w.push(v);
    wsum += v;
  }
  for (let k = 0; k < w.length; k++) w[k] /= wsum;
  const src = new Float32Array(m * m);
  for (let b = 0; b < m; b++) for (let a = 0; a < m; a++) src[b * m + a] = raw(a + lo, b + lo);
  // horizontal pass over all rows, columns [K, m − K)
  const tmp = new Float32Array(m * m);
  for (let b = 0; b < m; b++) {
    for (let a = K; a < m - K; a++) {
      let s = 0;
      for (let k = -K; k <= K; k++) s += w[k + K] * src[b * m + a + k];
      tmp[b * m + a] = s;
    }
  }
  const outN = n + 2; // vertices −1 … n
  const out = new Float32Array(outN * outN);
  for (let b = 0; b < outN; b++) {
    const sb = b + K; // row in src/tmp
    for (let a = 0; a < outN; a++) {
      const sa = a + K;
      let s = 0;
      for (let k = -K; k <= K; k++) s += w[k + K] * tmp[(sb + k) * m + sa];
      out[b * outN + a] = s;
    }
  }
  return (a, b) => {
    const ca = Math.min(outN - 1, Math.max(0, a + 1));
    const cb = Math.min(outN - 1, Math.max(0, b + 1));
    return out[cb * outN + ca];
  };
}

/**
 * Smoothed display elevations of a tile's vertices, by tile key, with the 3×3 elevation tiles they
 * were made from: lava, ash or temperature updates rebuild a tile far more often than its ground
 * changes, and the smoothing is the costliest part of a rebuild.
 */
const elevationCache = new Map<string, { deps: (TileFrame | undefined)[]; smoothR: number; q: number; grid: Float32Array }>();

/**
 * Display elevations for vertices [−1, n]² under `key`: `raw` smoothed by radius `smoothR` without
 * moving further than one step `q` from the data, baked into a grid. Reused while `deps` (the
 * elevation tiles `raw` reads) are the same objects.
 */
export function cachedElevation(key: string, deps: (TileFrame | undefined)[], n: number, smoothR: number, q: number, raw: Reader): Reader {
  const hit = elevationCache.get(key);
  if (hit && hit.smoothR === smoothR && hit.q === q && hit.deps.length === deps.length && hit.deps.every((d, k) => d === deps[k])) return gridReader(hit.grid, n);
  const shown = smoothR > 0 && q > 0 ? clampedReader(raw, smoothedReader(raw, n, smoothR), q) : raw;
  const { grid, read } = bakedReader(shown, n);
  elevationCache.set(key, { deps, smoothR, q, grid });
  return read;
}

export function forgetElevation(key: string): void {
  elevationCache.delete(key);
}

/** Display elevations of core tile (tx, ty). */
function displayElevation(key: string, tx: number, ty: number, n: number, smoothR: number, q: number, raw: Reader): Reader {
  const deps: (TileFrame | undefined)[] = [];
  for (let oy = -1; oy <= 1; oy++) for (let ox = -1; ox <= 1; ox++) deps.push(getTile(Field.SurfaceElevation, tx + ox, ty + oy));
  return cachedElevation(key, deps, n, smoothR, q, raw);
}

/** One quantum per world (estimated from the first tile that shows one), so tile seams agree. */
const QUANTUM = new Map<string, number>();
export function worldQuantum(world: WorldInfo, values: Float32Array | undefined): number {
  const known = QUANTUM.get(world.name);
  if (known !== undefined) return known;
  const q = elevationQuantum(values);
  if (q > 0) QUANTUM.set(world.name, q);
  return q;
}

/** sRGB → linear, so ramps defined in sRGB display as intended. */
export function lin(c: number): number {
  return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

/** Geometry of a (T+1)×(T+1) vertex grid; vertex (a, b) sits on column (tx·T + a, ty·T + b). */
export function gridGeometry(t: number): THREE.BufferGeometry {
  const n = t + 1;
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(new Float32Array(n * n * 3), 3));
  geo.setAttribute('color', new THREE.BufferAttribute(new Float32Array(n * n * 3), 3));
  geo.setAttribute('normal', new THREE.BufferAttribute(new Float32Array(n * n * 3), 3));
  const idx = new Uint32Array(t * t * 6);
  let o = 0;
  for (let b = 0; b < t; b++) {
    for (let a = 0; a < t; a++) {
      const v = b * n + a;
      // world y (north) maps to scene −z, so wind triangles to face +y
      idx[o++] = v;
      idx[o++] = v + 1;
      idx[o++] = v + n;
      idx[o++] = v + 1;
      idx[o++] = v + n + 1;
      idx[o++] = v + n;
    }
  }
  geo.setIndex(new THREE.BufferAttribute(idx, 1));
  return geo;
}

/**
 * Rewrites an overlay's index buffer to the triangles whose three vertices are all "wet" (lava,
 * water, flow present), or with `margin` every triangle touching a wet vertex (its dry vertices must
 * then sit on the ground). Mixed wet/dry triangles over vertices sunk below the ground would slant
 * into it and show as spikes along shorelines. Unused slots become degenerate triangles.
 */
export function compactIndex(geo: THREE.BufferGeometry, wet: Uint8Array, n: number, margin = false): void {
  const index = geo.getIndex()!;
  const idx = index.array as Uint32Array;
  let o = 0;
  const tri = (p: number, q: number, r: number) => {
    if (margin ? wet[p] || wet[q] || wet[r] : wet[p] && wet[q] && wet[r]) {
      idx[o++] = p;
      idx[o++] = q;
      idx[o++] = r;
    }
  };
  for (let b = 0; b < n - 1; b++) {
    for (let a = 0; a < n - 1; a++) {
      const v = b * n + a;
      tri(v, v + 1, v + n);
      tri(v + 1, v + n + 1, v + n);
    }
  }
  idx.fill(0, o);
  index.needsUpdate = true;
  geo.setDrawRange(0, o);
}

interface TileProps {
  world: WorldInfo;
  tx: number;
  ty: number;
  onPick: (xy: XY, e: ThreeEvent<MouseEvent>) => void;
}

const DEPOSIT_RGB = new Map<number, RGB>();

/** Id of the lava deposit type (its colour is the colour of cooled crust). */
function lavaDepositId(world: WorldInfo): number {
  return world.depositTypes.find((d) => d.name === 'LAVA')?.id ?? -1;
}

/** Id of a deposit type by name (−1 if the server does not list it). */
function depositId(world: WorldInfo, name: string): number {
  return world.depositTypes.find((d) => d.name === name)?.id ?? -1;
}

/** Fresh tephra colour when the server does not list FALL (pale ash). */
const ASH_RGB: RGB = [0.72, 0.7, 0.67];

export function depositRgb(world: WorldInfo, depositType: number): RGB {
  let c = DEPOSIT_RGB.get(depositType);
  if (!c) {
    c = hexToRgb(world.depositTypes.find((d) => d.id === depositType)?.color ?? '#888888');
    DEPOSIT_RGB.set(depositType, c);
  }
  return c;
}

function TerrainTile({ world, tx, ty, onPick }: TileProps) {
  const t = world.tileSize;
  const n = t + 1;
  const ground = useMemo(() => gridGeometry(t), [t]);
  const lava = useMemo(() => gridGeometry(t), [t]);
  const water = useMemo(() => {
    const g = gridGeometry(t);
    // water depth (m) per vertex for the water shader's absorption and transparency
    g.setAttribute('depth', new THREE.BufferAttribute(new Float32Array((t + 1) * (t + 1)), 1));
    return g;
  }, [t]);
  const flow = useMemo(() => gridGeometry(t), [t]);
  const table = useMemo(() => gridGeometry(t), [t]);
  const tableMesh = useRef<THREE.Mesh>(null);
  const lavaMesh = useRef<THREE.Mesh>(null);
  const waterMesh = useRef<THREE.Mesh>(null);
  const flowMesh = useRef<THREE.Mesh>(null);
  const groundMesh = useRef<THREE.Mesh>(null);
  const fillMesh = useRef<THREE.Mesh>(null);
  // a flat piece of sea over the tile's footprint (vertex range: column centres tx·T … (tx+1)·T)
  const fillGeo = useMemo(() => {
    const span = t * world.cellSize;
    const g = new THREE.PlaneGeometry(span, span);
    g.rotateX(-Math.PI / 2);
    g.translate(world.origin[0] + (tx * t + 0.5) * world.cellSize + span / 2, 0, -(world.origin[1] + (ty * t + 0.5) * world.cellSize + span / 2));
    return g;
  }, [t, world, tx, ty]);
  useEffect(() => () => fillGeo.dispose(), [fillGeo]);

  const rev = useStore((s) => {
    const r = s.tileRevision;
    return (r[tileKey(tx, ty)] ?? 0) + (r[tileKey(tx + 1, ty)] ?? 0) * 7 + (r[tileKey(tx, ty + 1)] ?? 0) * 13 + (r[tileKey(tx + 1, ty + 1)] ?? 0) * 17;
  });
  // crater-detail tiles over this tile: where they have arrived they draw the ground instead
  const levels = useMemo(() => detailLevels(world), [world]);
  const overlapping = useMemo(() => overlappingDetailTiles(world, levels, tx, ty).map(([l, a, b]) => lodKey(l, a, b)), [world, levels, tx, ty]);
  const covered = useStore((s) => {
    let k = 0;
    for (const key of overlapping) if (s.lodRevision[key] !== undefined) k++;
    return k;
  });
  const vExag = useStore((s) => s.verticalExaggeration);
  const dExag = useStore((s) => s.deformationExaggeration);
  const mode = useStore((s) => s.colorMode);
  const units = useStore((s) => s.units);
  const smoothR = useStore((s) => (s.smoothTerrain ? QUALITY[s.quality].smoothRadius : 0));
  const shadows = useStore((s) => QUALITY[s.quality].shadows);

  useEffect(() => {
    const key = tileKey(tx, ty);
    const span = world.tileSize * world.cellSize;
    // until the rebuild below has run, open sea stands in where the tile has (or may have) sea
    const fill = fillMesh.current;
    if (fill && world.hasSea !== false && Number.isFinite(world.seaLevel) && !fill.userData.built) {
      const values = getTile(Field.SurfaceElevation, tx, ty)?.values;
      let low = values ? Infinity : world.elevationRange[0];
      if (values) for (let k = 0; k < values.length; k++) if (values[k] < low) low = values[k];
      fill.position.y = world.seaLevel * vExag;
      fill.visible = low < world.seaLevel;
    }
    const job: PendingRebuild = { run: () => {}, at: [world.origin[0] + (tx + 0.5) * span, world.origin[1] + (ty + 0.5) * span] };
    job.run = () => {
      // nothing to draw until this tile's elevation has arrived
      const loaded = getTile(Field.SurfaceElevation, tx, ty) !== undefined;
      if (groundMesh.current) groundMesh.current.visible = loaded;
      if (!loaded) return;
      if (fillMesh.current) {
        fillMesh.current.visible = false;
        fillMesh.current.userData.built = true;
      }
      const [eLo, eHi] = world.elevationRange;
      const R = (f: FieldId, fb = 0) => reader(f, tx, ty, t, fb);
      const rawElev = R(Field.SurfaceElevation, 0);
      // smoothing only removes the block terraces: the shown height stays within one elevation step of
      // the data, so crater rims, vents and scarps keep their real shape
      const q = worldQuantum(world, getTile(Field.SurfaceElevation, tx, ty)?.values);
      const elevR = displayElevation(key, tx, ty, n, smoothR, q, rawElev);
      const seaOn = world.hasSea !== false && Number.isFinite(world.seaLevel);
      const upR = R(Field.Uplift);
      // lava thickness varies by tens of metres between cells; smooth it lightly so the lake/flow top is not jagged
      const lavaRaw = R(Field.LavaDepth);
      const lavaTRaw = R(Field.LavaTemperature);
      const lavaSmooth = smoothR > 0 ? 2 : 0;
      const lavaR = smoothedReader(lavaRaw, n, lavaSmooth);
      // temperature averaged with the same kernel, weighted by thickness: cells the smoothing spreads
      // the sheet into (no lava of their own, temperature 0) take their neighbours' temperature
      const lavaTD = smoothedReader((a, b) => lavaRaw(a, b) * lavaTRaw(a, b), n, lavaSmooth);
      const lavaId = lavaDepositId(world);
      const crust = lavaId >= 0 ? depositRgb(world, lavaId) : CRUST_RGB;
      const waterR = R(Field.WaterDepth);
      const pdcR = R(Field.PdcDepth);
      const laharR = R(Field.LaharDepth);
      const fields: GroundFields = {
        ash: R(Field.AshDepth),
        temp: R(Field.SurfaceTemperature, 10),
        wt: R(Field.WaterTableDepth, 10),
        unit: R(Field.TopUnit),
        uplift: upR,
        steam: R(Field.SteamFraction),
      };
      const gp = ground.getAttribute('position') as THREE.BufferAttribute;
      const gc = ground.getAttribute('color') as THREE.BufferAttribute;
      const gn = ground.getAttribute('normal') as THREE.BufferAttribute;
      const lp = lava.getAttribute('position') as THREE.BufferAttribute;
      const lc = lava.getAttribute('color') as THREE.BufferAttribute;
      const wp = water.getAttribute('position') as THREE.BufferAttribute;
      const wc = water.getAttribute('color') as THREE.BufferAttribute;
      const wdA = water.getAttribute('depth') as THREE.BufferAttribute;
      const fp = flow.getAttribute('position') as THREE.BufferAttribute;
      const tp = table.getAttribute('position') as THREE.BufferAttribute;
      const wetT = new Uint8Array(n * n);
      let anyTable = false;
      const fc = flow.getAttribute('color') as THREE.BufferAttribute;
      const c: RGB = [0, 0, 0];
      let anyLava = false;
      let anyWater = false;
      let shallowest = Infinity;
      let anyPond = false;
      let maxLift = 0;
      let anyFlow = false;
      const wetL = new Uint8Array(n * n);
      const wetW = new Uint8Array(n * n);
      const wetF = new Uint8Array(n * n);
      const shown = new Float32Array(n * n);
      const hasLod = (l: number, a: number, b: number) => useStore.getState().lodRevision[lodKey(l, a, b)] !== undefined;
      const keepG = covered > 0 ? new Uint8Array(n * n) : null;
      let maxUplift = 1e-6;
      if (mode === 'uplift') for (let b = 0; b < n; b++) for (let a = 0; a < n; a++) maxUplift = Math.max(maxUplift, Math.abs(upR(a, b)));
      const maxI = (world.tiles.maxTx + 1) * t - 1;
      const maxJ = (world.tiles.maxTy + 1) * t - 1;
      for (let b = 0; b < n; b++) {
        for (let a = 0; a < n; a++) {
          const i = Math.min(maxI, tx * t + a);
          const j = Math.min(maxJ, ty * t + b);
          const v = b * n + a;
          const x = world.origin[0] + (i + 0.5) * world.cellSize;
          const y = world.origin[1] + (j + 0.5) * world.cellSize;
          const elev = elevR(a, b);
          const up = upR(a, b);
          const z = groundZ(elev, up, dExag, vExag);
          // the far-field sea has no deformation: the uplift on the sea surface tapers to 0 over the
          // outermost four columns, so the two surfaces meet at the same height
          const edgeCols = Math.min(i - world.tiles.minTx * t, j - world.tiles.minTy * t, maxI - i, maxJ - j);
          const seaZ = seaSurfaceZ(world.seaLevel, up * Math.min(1, Math.max(0, edgeCols / 4)), dExag, vExag);
          gp.setXYZ(v, x, z, -y);
          shown[v] = z;
          if (keepG && !detailCovers(world, levels, hasLod, i, j)) keepG[v] = 1;
          // overlays sit on the ground that is drawn: under crater detail that is the detail mesh
          const base = keepG && !keepG[v] ? (detailGroundAt(world, x, y, vExag, dExag) ?? z) : z;
          // normal from central differences across tile edges (no seams)
          const hx = ((elevR(a + 1, b) - elevR(a - 1, b)) * vExag) / (2 * world.cellSize);
          const hy = ((elevR(a, b + 1) - elevR(a, b - 1)) * vExag) / (2 * world.cellSize);
          const inv = 1 / Math.hypot(hx, 1, hy);
          gn.setXYZ(v, -hx * inv, inv, hy * inv);
          colourGround(mode, world, fields, a, b, elev, eLo, eHi, maxUplift, units, c);
          const g0 = c[0];
          const g1 = c[1];
          const g2 = c[2];
          gc.setXYZ(v, lin(g0), lin(g1), lin(g2));

          // Lava: lit crust blending into incandescence by temperature. Vertices just outside the sheet
          // sit on the ground in the ground's colour, so the margin fades into the terrain over one cell
          // instead of ending in a cell-stepped edge (the overlay draws every triangle touching lava).
          // the domain's outermost columns hold no lava sheet: a lake reaching the edge slopes down to
          // the ground there instead of ending in a cliff (the far field continues the ground)
          const atEdge = i <= world.tiles.minTx * t || j <= world.tiles.minTy * t || i >= maxI || j >= maxJ;
          const ld = atEdge ? 0 : lavaR(a, b);
          const light = crustLight(-hx * inv, inv, hy * inv);
          if (ld >= LAVA_SHEET_MIN_M) {
            // a sheet clearly above the ground (no coplanar layers: polygon offset is ineffective
            // with the logarithmic depth buffer, so a thin lift z-fights into stripes)
            anyLava = true;
            wetL[v] = 1;
            lp.setXYZ(v, x, base + (ld + LAVA_SHEET_LIFT_M) * vExag, -y);
            lavaSurfaceColor(weightedTemperature(lavaTD(a, b), ld), crust, light, c, crackPattern(x, y));
          } else {
            // thin films tint the ground instead of floating a sheet over it
            if (ld > 0.02) {
              lavaSurfaceColor(weightedTemperature(lavaTD(a, b), ld), crust, light, c, crackPattern(x, y));
              const f = Math.min(1, ld / LAVA_SHEET_MIN_M);
              gc.setXYZ(v, lin(g0 + (c[0] - g0) * f), lin(g1 + (c[1] - g1) * f), lin(g2 + (c[2] - g2) * f));
            }
            // sheet margin vertices tuck under the ground: the sheet meets it at a clean intersection
            lp.setXYZ(v, x, base - LAVA_SHEET_TUCK_M * vExag, -y);
            c[0] = Math.min(1, g0 * light);
            c[1] = Math.min(1, g1 * light);
            c[2] = Math.min(1, g2 * light);
          }
          lc.setXYZ(v, lin(c[0]), lin(c[1]), lin(c[2]));

          // Open sea: one flat surface at sea level wherever the ground is below it, whether or not
          // the surface-water field carries that column (it tracks ponds, rivers and poured water).
          // Ponds and rivers above sea level keep their own surface.
          const seaDepth = seaOn ? world.seaLevel - elev : 0;
          const pond = waterR(a, b);
          shallowest = Math.min(shallowest, seaDepth);
          maxLift = Math.max(maxLift, Math.abs(seaZ - world.seaLevel * vExag));
          // the surface-water field carries the open sea too (its depth can differ by a little from
          // sea level − ground): water that matches the sea is the sea, not a pond
          if (seaDepth > 0 && pond <= seaDepth + 2) {
            anyWater = true;
            wetW[v] = SEA;
            wp.setXYZ(v, x, seaZ, -y);
            wdA.setX(v, seaDepth);
          } else if (pond > 0.25) {
            // ignore thin sheet flow (rain films); show ponded and flowing water
            anyWater = true;
            anyPond = true;
            wetW[v] = POND;
            wp.setXYZ(v, x, base + pond * vExag, -y);
            wdA.setX(v, pond);
          } else {
            // dry: a sea triangle that reaches it keeps it at sea level (the ground's depth test
            // draws the coastline); pond triangles never use dry vertices
            wp.setXYZ(v, x, seaOn ? seaZ : base - 2, -y);
            wdA.setX(v, 0);
          }
          wc.setXYZ(v, 0.2, 0.45, 0.65);

          const wtd = fields.wt(a, b);
          if (Number.isFinite(wtd) && wtd > 0.5) {
            anyTable = true;
            wetT[v] = 1;
            tp.setXYZ(v, x, z - wtd * vExag, -y);
          } else {
            tp.setXYZ(v, x, z - 2, -y);
          }

          // A pyroclastic flow is shown by its ash cloud (SurgeClouds); on the ground it only greys
          // the surface it runs over, fading with depth (no flat sheet floating over the terrain).
          const pdc = pdcR(a, b);
          if (pdc > 0.05) {
            const f = Math.min(0.65, pdc / 3);
            const [r0, r1, r2] = [g0 + (0.5 - g0) * f, g1 + (0.46 - g1) * f, g2 + (0.42 - g2) * f];
            gc.setXYZ(v, lin(r0), lin(r1), lin(r2));
          }
          // A lahar is a mud sheet on the drawn ground; its margin vertices tuck under the ground, so
          // it ends in a smooth intersection instead of cell steps.
          const lah = laharR(a, b);
          if (lah > LAHAR_MIN_M) {
            anyFlow = true;
            wetF[v] = 1;
            fp.setXYZ(v, x, base + Math.max(lah, LAHAR_LIFT_M) * vExag, -y);
            const wet = Math.min(1, lah / 2);
            fc.setXYZ(v, lin(0.42 - 0.08 * wet), lin(0.32 - 0.06 * wet), lin(0.2 - 0.04 * wet));
          } else {
            fp.setXYZ(v, x, base - LAVA_SHEET_TUCK_M * vExag, -y);
            fc.setXYZ(v, lin(0.4), lin(0.31), lin(0.2));
          }
        }
      }
      gn.needsUpdate = true;
      displayHeights.set(key, { z: shown, vExag, dExag });
      // under loaded crater detail the coarse ground is left out (the detail mesh draws it)
      if (keepG || ground.userData.cut) {
        compactIndex(ground, keepG ?? new Uint8Array(n * n).fill(1), n, true);
        ground.userData.cut = keepG !== null;
      }
      if (anyLava) compactIndex(lava, wetL, n, true);
      // deep open sea over the whole tile: its quad stands in for the full water grid
      const deepQuad = seaOn && anyWater && !anyPond && shallowest >= DEEP_SEA_M && maxLift < 0.5 * vExag;
      if (deepQuad) anyWater = false;
      if (fillMesh.current) {
        fillMesh.current.visible = deepQuad;
        fillMesh.current.position.y = world.seaLevel * vExag;
      }
      if (anyWater) {
        compactWater(water, wetW, n);
        wdA.needsUpdate = true;
      }
      if (anyFlow) compactIndex(flow, wetF, n, true);
      if (anyTable) {
        compactIndex(table, wetT, n);
        tp.needsUpdate = true;
        table.computeBoundingSphere();
      }
      if (tableMesh.current) tableMesh.current.userData.has = anyTable;
      ground.computeBoundingSphere();
      ground.computeBoundingBox();
      for (const [geo, any] of [
        [ground, false],
        [lava, anyLava],
        [water, anyWater],
        [flow, anyFlow],
      ] as const) {
        geo.getAttribute('position').needsUpdate = true;
        geo.getAttribute('color').needsUpdate = true;
        if (geo === ground) continue;
        if (any) {
          geo.computeVertexNormals();
          geo.computeBoundingSphere();
          geo.computeBoundingBox();
        }
      }
      if (lavaMesh.current) lavaMesh.current.visible = anyLava;
      if (waterMesh.current) waterMesh.current.visible = anyWater;
      if (flowMesh.current) flowMesh.current.visible = anyFlow;
    };
    coreJobs.set(key, job);
    queueRebuild(key, job.run, job.at);
  }, [rev, vExag, dExag, mode, units, world, tx, ty, t, n, ground, lava, water, flow, smoothR, covered, levels]);

  useEffect(
    () => () => {
      rebuildQueue.delete(tileKey(tx, ty));
      coreJobs.delete(tileKey(tx, ty));
      displayHeights.delete(tileKey(tx, ty));
      elevationCache.delete(tileKey(tx, ty));
    },
    [tx, ty],
  );

  return (
    <group>
      <mesh
        ref={groundMesh}
        visible={false}
        geometry={ground}
        castShadow={shadows}
        receiveShadow={shadows}
        onClick={(e) => {
          if (e.delta > 4) return;
          e.stopPropagation();
          onPick([e.point.x, -e.point.z], e);
        }}
      >
        <primitive object={groundMaterial} attach="material" />
      </mesh>
      <mesh ref={tableMesh} geometry={table} visible={false} renderOrder={5} material={waterTableMaterial} />
      <mesh ref={waterMesh} geometry={water} visible={false} renderOrder={2} material={pondWater} />
      <mesh ref={lavaMesh} geometry={lava} visible={false} renderOrder={1}>
        <meshBasicMaterial vertexColors toneMapped={false} polygonOffset polygonOffsetFactor={-2} polygonOffsetUnits={-2} />
      </mesh>
      <mesh ref={flowMesh} geometry={flow} visible={false} material={mudMaterial} />
      <mesh ref={fillMesh} geometry={fillGeo} visible={false} renderOrder={2} material={pendingSea} raycast={() => null} />
    </group>
  );
}

export interface GroundFields {
  ash: Reader;
  temp: Reader;
  wt: Reader;
  unit: Reader;
  uplift: Reader;
  steam: Reader;
}

export function colourGround(
  mode: SurfaceColorMode,
  world: WorldInfo,
  f: GroundFields,
  i: number,
  j: number,
  elev: number,
  eLo: number,
  eHi: number,
  maxUplift: number,
  units: Record<number, { depositType: number; time: number | null }>,
  c: RGB,
): void {
  switch (mode) {
    case 'surfaceTemperature':
      ramp(THERMAL, f.temp(i, j), c);
      return;
    case 'waterTable': {
      const d = f.wt(i, j);
      if (d < 0) {
        c[0] = 0.2;
        c[1] = 0.85;
        c[2] = 0.95;
      } else {
        const tt = Math.min(1, Math.sqrt(d / 80));
        c[0] = 0.1 + tt * 0.75;
        c[1] = 0.35 + tt * 0.45;
        c[2] = 0.85 - tt * 0.35;
      }
      return;
    }
    case 'topUnit': {
      const u = f.unit(i, j);
      const info = units[u];
      const base = depositRgb(world, info?.depositType ?? 7);
      const s = shadeFor(base, u);
      c[0] = s[0];
      c[1] = s[1];
      c[2] = s[2];
      return;
    }
    case 'uplift':
      ramp(DIVERGING, f.uplift(i, j) / maxUplift, c);
      return;
    case 'ash': {
      const a = f.ash(i, j);
      const tt = Math.min(1, Math.log10(1 + a * 100) / 2.5);
      c[0] = 0.25 + tt * 0.6;
      c[1] = 0.27 + tt * 0.55;
      c[2] = 0.3 + tt * 0.5;
      return;
    }
    case 'steam': {
      const s = f.steam(i, j);
      c[0] = 0.2 + s * 0.8;
      c[1] = 0.22 + s * 0.78;
      c[2] = 0.28 + s * 0.72;
      return;
    }
    case 'natural': {
      if (elev >= world.seaLevel || world.hasSea === false) ramp(HYPSO, Math.max(0, (elev - world.seaLevel) / Math.max(1, eHi - world.seaLevel)), c);
      else ramp(BATHY, (world.seaLevel - elev) / Math.max(1, world.seaLevel - eLo), c);
      // tephra fall blankets the surface in proportion to its thickness (a few mm barely shows, decimetres
      // cover it): tinting by the FALL unit alone painted any dusting solid out to where the ash ends
      const ash = f.ash(i, j);
      const fall = depositId(world, 'FALL');
      if (ash > 0.002) {
        const k = Math.min(0.85, Math.log10(1 + ash * 200) / 2.2);
        const t = fall >= 0 ? depositRgb(world, fall) : ASH_RGB;
        c[0] += (t[0] - c[0]) * k;
        c[1] += (t[1] - c[1]) * k;
        c[2] += (t[2] - c[2]) * k;
      }
      // fresh lava rock / PDC / lahar / landslide deposits from the unit table (thick by nature)
      const u = f.unit(i, j);
      const info = units[u];
      const bedrock = info && (info.depositType === depositId(world, 'EDIFICE') || info.depositType === depositId(world, 'BASEMENT') || info.depositType === depositId(world, 'FILL'));
      if (info && info.time != null && !bedrock && info.depositType !== fall) {
        const d = depositRgb(world, info.depositType);
        c[0] += (d[0] - c[0]) * 0.8;
        c[1] += (d[1] - c[1]) * 0.8;
        c[2] += (d[2] - c[2]) * 0.8;
      }
      // steaming ground (> ~boiling) bleaches to ochre; only incandescent rock (> 450 °C) glows red
      const st = f.temp(i, j);
      if (st > 95) {
        const k = Math.min(0.45, (st - 95) / 300);
        c[0] += (0.78 - c[0]) * k;
        c[1] += (0.66 - c[1]) * k;
        c[2] += (0.38 - c[2]) * k;
      }
      if (st > 450) {
        const k = Math.min(0.8, (st - 450) / 500);
        c[0] += (0.95 - c[0]) * k;
        c[1] += (0.3 - c[1]) * k;
        c[2] += (0.08 - c[2]) * k;
      }
      return;
    }
  }
}

const focusDir = new THREE.Vector3();

export function Terrain({ world, onPick }: { world: WorldInfo; onPick: TileProps['onPick'] }) {
  const group = useRef<THREE.Group>(null);
  const lastTable = useRef<boolean | null>(null);
  useFrame(({ camera, invalidate }) => {
    const st = useStore.getState();
    const g = displayedGround(world, camera.position.x, -camera.position.z, st.verticalExaggeration, st.deformationExaggeration);
    sceneProbe.cameraGround = g ?? Number.NaN;
    const under = g !== undefined && camera.position.y < g - 0.5;
    if (under !== st.underground) st.set({ underground: under });
    const xray = under && st.xray;
    if (groundMaterial.transparent !== xray) {
      groundMaterial.transparent = xray;
      groundMaterial.opacity = xray ? 0.28 : 1;
      groundMaterial.depthWrite = !xray;
      groundMaterial.needsUpdate = true;
    }
    // water-table sheets: shown on request, and always while underground
    const showTable = st.showWaterTable || under;
    let k = 0;
    const t0 = performance.now();
    if (rebuildQueue.size > 0) {
      const focus = viewFocus(camera.position, camera.getWorldDirection(focusDir), ((world.elevationRange[0] + world.elevationRange[1]) / 2) * st.verticalExaggeration);
      for (const key of rebuildOrder(rebuildQueue, focus)) {
        const pending = rebuildQueue.get(key)!;
        rebuildQueue.delete(key);
        pending.run();
        k++;
        if (performance.now() - t0 >= REBUILD_BUDGET_MS) break;
      }
    }
    perfStats.rebuildMs = performance.now() - t0;
    perfStats.rebuildQueue = rebuildQueue.size;
    // (only when toggled or after rebuilds, not a scene-graph walk every frame)
    if (showTable !== lastTable.current || k > 0) {
      lastTable.current = showTable;
      group.current?.traverse((o) => {
        if (o instanceof THREE.Mesh && o.material === waterTableMaterial) o.visible = showTable && o.userData.has === true;
      });
    }
    // on-demand rendering: keep drawing until the rebuild backlog is done
    if (rebuildQueue.size > 0 || k > 0) invalidate();
  });
  const tiles: [number, number][] = [];
  for (let ty = world.tiles.minTy; ty <= world.tiles.maxTy; ty++) for (let tx = world.tiles.minTx; tx <= world.tiles.maxTx; tx++) tiles.push([tx, ty]);
  return (
    <group ref={group}>
      {tiles.map(([tx, ty]) => (
        <TerrainTile key={`${tx},${ty}`} world={world} tx={tx} ty={ty} onPick={onPick} />
      ))}
    </group>
  );
}

/**
 * Height of the drawn (smoothed) ground surface at world (x, y), bilinear between the vertices of
 * the tile meshes; undefined where the tile is not built yet or was built with other exaggerations.
 */
export function displayedGround(world: WorldInfo, x: number, y: number, vExag: number, dExag: number): number | undefined {
  const t = world.tileSize;
  const detail = detailGroundAt(world, x, y, vExag, dExag);
  if (detail !== undefined) return detail;
  const fi = (x - world.origin[0]) / world.cellSize - 0.5;
  const fj = (y - world.origin[1]) / world.cellSize - 0.5;
  const tx = Math.floor(fi / t);
  const ty = Math.floor(fj / t);
  const tile = displayHeights.get(tileKey(tx, ty));
  if (!tile || tile.vExag !== vExag || tile.dExag !== dExag) return undefined;
  return interpolateGrid(tile.z, t, fi - tx * t, fj - ty * t);
}

/** Elevation of world point (x, y) as displayed (with exaggerations), for placing overlays. */
export function displayZ(world: WorldInfo, x: number, y: number, vExag: number, dExag: number): number {
  const shown = displayedGround(world, x, y, vExag, dExag);
  if (shown !== undefined) return shown;
  const i = Math.floor((x - world.origin[0]) / world.cellSize);
  const j = Math.floor((y - world.origin[1]) / world.cellSize);
  const e = sampleColumn(world, Field.SurfaceElevation, i, j, 0);
  const u = sampleColumn(world, Field.Uplift, i, j, 0);
  return (e + u * (dExag - 1)) * vExag;
}
