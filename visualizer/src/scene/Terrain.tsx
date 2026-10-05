import { useFrame, type ThreeEvent } from '@react-three/fiber';
import { useEffect, useMemo, useRef } from 'react';
import * as THREE from 'three';
import { Field, type FieldId } from '../protocol/fields';
import type { WorldInfo, XY } from '../protocol/messages';
import { QUALITY, getTile, tileKey, useStore, type SurfaceColorMode } from '../store/store';
import { BATHY, BLACKBODY, DIVERGING, HYPSO, THERMAL, hexToRgb, ramp, shadeFor, type RGB } from '../util/color';
import { sampleColumn } from '../util/world';

/** Tiles rebuilt per rendered frame, to keep the UI responsive while data streams in. */
const REBUILDS_PER_FRAME = 8;
const rebuildQueue = new Map<string, () => void>();

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

/** sRGB → linear, so ramps defined in sRGB display as intended. */
function lin(c: number): number {
  return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

/** Geometry of a (T+1)×(T+1) vertex grid; vertex (a, b) sits on column (tx·T + a, ty·T + b). */
function gridGeometry(t: number): THREE.BufferGeometry {
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

interface TileProps {
  world: WorldInfo;
  tx: number;
  ty: number;
  onPick: (xy: XY, e: ThreeEvent<MouseEvent>) => void;
}

const DEPOSIT_RGB = new Map<number, RGB>();

function depositRgb(world: WorldInfo, depositType: number): RGB {
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
  const water = useMemo(() => gridGeometry(t), [t]);
  const flow = useMemo(() => gridGeometry(t), [t]);
  const lavaMesh = useRef<THREE.Mesh>(null);
  const waterMesh = useRef<THREE.Mesh>(null);
  const flowMesh = useRef<THREE.Mesh>(null);
  const groundMesh = useRef<THREE.Mesh>(null);

  const rev = useStore((s) => {
    const r = s.tileRevision;
    return (r[tileKey(tx, ty)] ?? 0) + (r[tileKey(tx + 1, ty)] ?? 0) * 7 + (r[tileKey(tx, ty + 1)] ?? 0) * 13 + (r[tileKey(tx + 1, ty + 1)] ?? 0) * 17;
  });
  const vExag = useStore((s) => s.verticalExaggeration);
  const dExag = useStore((s) => s.deformationExaggeration);
  const mode = useStore((s) => s.colorMode);
  const units = useStore((s) => s.units);
  const smoothR = useStore((s) => (s.smoothTerrain ? QUALITY[s.quality].smoothRadius : 0));
  const shadows = useStore((s) => QUALITY[s.quality].shadows);

  useEffect(() => {
    const key = tileKey(tx, ty);
    rebuildQueue.set(key, () => {
      // nothing to draw until this tile's elevation has arrived
      const loaded = getTile(Field.SurfaceElevation, tx, ty) !== undefined;
      if (groundMesh.current) groundMesh.current.visible = loaded;
      if (!loaded) return;
      const [eLo, eHi] = world.elevationRange;
      const R = (f: FieldId, fb = 0) => reader(f, tx, ty, t, fb);
      const rawElev = R(Field.SurfaceElevation, 0);
      const elevR = smoothedReader(rawElev, n, smoothR);
      const upR = R(Field.Uplift);
      const lavaR = R(Field.LavaDepth);
      const lavaT = R(Field.LavaTemperature);
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
      const fp = flow.getAttribute('position') as THREE.BufferAttribute;
      const fc = flow.getAttribute('color') as THREE.BufferAttribute;
      const c: RGB = [0, 0, 0];
      let anyLava = false;
      let anyWater = false;
      let anyFlow = false;
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
          const z = (elev + upR(a, b) * (dExag - 1)) * vExag;
          gp.setXYZ(v, x, z, -y);
          // normal from central differences across tile edges (no seams)
          const hx = ((elevR(a + 1, b) - elevR(a - 1, b)) * vExag) / (2 * world.cellSize);
          const hy = ((elevR(a, b + 1) - elevR(a, b - 1)) * vExag) / (2 * world.cellSize);
          const inv = 1 / Math.hypot(hx, 1, hy);
          gn.setXYZ(v, -hx * inv, inv, hy * inv);
          colourGround(mode, world, fields, a, b, elev, eLo, eHi, maxUplift, units, c);
          gc.setXYZ(v, lin(c[0]), lin(c[1]), lin(c[2]));

          const ld = lavaR(a, b);
          if (ld > 0.02) {
            anyLava = true;
            lp.setXYZ(v, x, z + (ld + 0.3) * vExag, -y);
            ramp(BLACKBODY, lavaT(a, b), c);
            lc.setXYZ(v, lin(c[0]), lin(c[1]), lin(c[2]));
          } else {
            lp.setXYZ(v, x, z - 2, -y);
            lc.setXYZ(v, 0.1, 0.05, 0.04);
          }

          const wd = waterR(a, b);
          if (wd > 0.03) {
            anyWater = true;
            wp.setXYZ(v, x, z + wd * vExag, -y);
            // shallow turquoise → deep navy (Beer–Lambert-ish with depth)
            const deep = 1 - Math.exp(-wd / 25);
            wc.setXYZ(v, lin(0.22 - deep * 0.18), lin(0.52 - deep * 0.36), lin(0.6 - deep * 0.3));
          } else {
            wp.setXYZ(v, x, z - 2, -y);
            wc.setXYZ(v, 0.2, 0.45, 0.65);
          }

          const pdc = pdcR(a, b);
          const lah = laharR(a, b);
          if (pdc > 0.05 || lah > 0.05) {
            anyFlow = true;
            fp.setXYZ(v, x, z + Math.max(pdc, lah) * vExag + 1, -y);
            if (pdc >= lah) fc.setXYZ(v, lin(0.62), lin(0.55), lin(0.5));
            else fc.setXYZ(v, lin(0.45), lin(0.33), lin(0.2));
          } else {
            fp.setXYZ(v, x, z - 2, -y);
            fc.setXYZ(v, 0.5, 0.45, 0.4);
          }
        }
      }
      gn.needsUpdate = true;
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
    });
  }, [rev, vExag, dExag, mode, units, world, tx, ty, t, n, ground, lava, water, flow, smoothR]);

  useEffect(() => () => void rebuildQueue.delete(tileKey(tx, ty)), [tx, ty]);

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
        <meshStandardMaterial vertexColors roughness={0.95} metalness={0} />
      </mesh>
      <mesh ref={waterMesh} geometry={water} visible={false} renderOrder={2} receiveShadow={shadows}>
        <meshStandardMaterial vertexColors transparent opacity={0.82} roughness={0.06} metalness={0.35} depthWrite={false} />
      </mesh>
      <mesh ref={lavaMesh} geometry={lava} visible={false} renderOrder={1}>
        <meshBasicMaterial vertexColors toneMapped={false} />
      </mesh>
      <mesh ref={flowMesh} geometry={flow} visible={false} renderOrder={3}>
        <meshStandardMaterial vertexColors transparent opacity={0.85} roughness={1} />
      </mesh>
    </group>
  );
}

interface GroundFields {
  ash: Reader;
  temp: Reader;
  wt: Reader;
  unit: Reader;
  uplift: Reader;
  steam: Reader;
}

function colourGround(
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
      if (elev >= world.seaLevel) ramp(HYPSO, (elev - world.seaLevel) / Math.max(1, eHi - world.seaLevel), c);
      else ramp(BATHY, (world.seaLevel - elev) / Math.max(1, world.seaLevel - eLo), c);
      // fresh ash blankets the surface
      const ash = f.ash(i, j);
      if (ash > 0.002) {
        const k = Math.min(0.85, Math.log10(1 + ash * 200) / 2.2);
        c[0] += (0.72 - c[0]) * k;
        c[1] += (0.7 - c[1]) * k;
        c[2] += (0.67 - c[2]) * k;
      }
      // fresh lava rock / PDC deposits from the unit table
      const u = f.unit(i, j);
      const info = units[u];
      if (info && info.time !== null && info.depositType !== 2) {
        const d = depositRgb(world, info.depositType);
        c[0] += (d[0] - c[0]) * 0.8;
        c[1] += (d[1] - c[1]) * 0.8;
        c[2] += (d[2] - c[2]) * 0.8;
      }
      // hot ground glows faintly
      const st = f.temp(i, j);
      if (st > 60) {
        const k = Math.min(0.7, (st - 60) / 400);
        c[0] += (0.95 - c[0]) * k;
        c[1] += (0.35 - c[1]) * k;
        c[2] += (0.1 - c[2]) * k;
      }
      return;
    }
  }
}

export function Terrain({ world, onPick }: { world: WorldInfo; onPick: TileProps['onPick'] }) {
  useFrame(() => {
    let k = 0;
    for (const [key, rebuild] of rebuildQueue) {
      rebuildQueue.delete(key);
      rebuild();
      if (++k >= REBUILDS_PER_FRAME) break;
    }
  });
  const tiles: [number, number][] = [];
  for (let ty = world.tiles.minTy; ty <= world.tiles.maxTy; ty++) for (let tx = world.tiles.minTx; tx <= world.tiles.maxTx; tx++) tiles.push([tx, ty]);
  return (
    <group>
      {tiles.map(([tx, ty]) => (
        <TerrainTile key={`${tx},${ty}`} world={world} tx={tx} ty={ty} onPick={onPick} />
      ))}
    </group>
  );
}

/** Elevation of world point (x, y) as displayed (with exaggerations), for placing overlays. */
export function displayZ(world: WorldInfo, x: number, y: number, vExag: number, dExag: number): number {
  const i = Math.floor((x - world.origin[0]) / world.cellSize);
  const j = Math.floor((y - world.origin[1]) / world.cellSize);
  const e = sampleColumn(world, Field.SurfaceElevation, i, j, 0);
  const u = sampleColumn(world, Field.Uplift, i, j, 0);
  return (e + u * (dExag - 1)) * vExag;
}
