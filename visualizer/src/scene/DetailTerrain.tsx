import { currentTier } from '../util/device';
import { notATap } from '../camera/gestures';
import { useFrame, type ThreeEvent } from '@react-three/fiber';
import { useEffect, useMemo, useRef, useState } from 'react';
import * as THREE from 'three';
import { setDetailLevels } from '../net/connection';
import { Field, type FieldId } from '../protocol/fields';
import type { LodLevelInfo, WorldInfo, XY } from '../protocol/messages';
import { QUALITY, getLodTile, getTile, lodKey, tileKey, useStore } from '../store/store';
import type { RGB } from '../util/color';
import { sampleColumn } from '../util/world';
import { CRUST_RGB, crackPattern, crustLight, lavaSurfaceColor } from './lavaColor';

import { detailHeights, detailLevels, levelRect, refinement, wantsDetail } from './detail';
import { cachedElevation, cancelRebuild, colourGround, forgetElevation, gridGeometry, groundMaterial, lin, queueRebuild, requeueCore, sceneProbe, worldQuantum, type GroundFields } from './Terrain';

/** How often (ms) the camera's distance to the crater regions is checked. */
const CHECK_MS = 500;

interface Props {
  world: WorldInfo;
  onPick: (xy: XY, e: ThreeEvent<MouseEvent>) => void;
}

/**
 * Crater-resolving ground (protocol §5.5, levels < 0): subscribed only while the camera is near a
 * crater region, drawn in place of the core's coarser ground where its tiles have arrived. The ground
 * colours come from the core column under each detail cell.
 */
export function DetailTerrain({ world, onPick }: Props) {
  const levels = useMemo(() => detailLevels(world), [world]);
  const [active, setActive] = useState<number[]>([]);
  const last = useRef(0);

  useFrame(({ camera }) => {
    if (levels.length === 0) return;
    const now = performance.now();
    if (now - last.current < CHECK_MS) return;
    last.current = now;
    const st = useStore.getState();
    const ground = Number.isFinite(sceneProbe.cameraGround) ? sceneProbe.cameraGround : 0;
    const above = (camera.position.y - ground) / st.verticalExaggeration;
    const x = camera.position.x;
    const y = -camera.position.z;
    // mobile budget: detail only closer in, and only the coarsest levels wanted (each finer level is 4× the tiles)
    const tier = currentTier();
    const want = levels
      .filter((l) => wantsDetail(levelRect(world, l), x, y, above, (active.includes(l.level) ? 1.3 : 1) * tier.detailReach))
      .map((l) => l.level)
      .sort((a, b) => b - a)
      .slice(0, tier.maxDetailLevels)
      .sort((a, b) => a - b);
    if (want.join(',') !== active.join(',')) {
      setActive(want);
      setDetailLevels(want);
    }
  });

  // leaving the world (or unmounting) drops the detail subscription
  useEffect(() => () => setDetailLevels([]), [world]);

  return (
    <group>
      {levels
        .filter((l) => active.includes(l.level))
        .map((l) => (
          <DetailLevel key={l.level} world={world} level={l} onPick={onPick} />
        ))}
    </group>
  );
}

function DetailLevel({ world, level, onPick }: { world: WorldInfo; level: LodLevelInfo; onPick: Props['onPick'] }) {
  const tiles: [number, number][] = [];
  for (let ty = level.tiles.minTy; ty <= level.tiles.maxTy; ty++) for (let tx = level.tiles.minTx; tx <= level.tiles.maxTx; tx++) tiles.push([tx, ty]);
  return (
    <group>
      {tiles.map(([tx, ty]) => (
        <DetailTile key={`${tx},${ty}`} world={world} level={level} tx={tx} ty={ty} onPick={onPick} />
      ))}
    </group>
  );
}

/** Reader for vertex (a, b) ∈ [−1, T+1]² of a detail tile, reaching into neighbouring tiles. */
function lodReader(level: number, tx: number, ty: number, t: number): (a: number, b: number) => number {
  const near: (Float32Array | undefined)[] = [];
  for (let oy = -1; oy <= 1; oy++) for (let ox = -1; ox <= 1; ox++) near.push(getLodTile(level, tx + ox, ty + oy)?.values);
  const own = near[4]!;
  return (a, b) => {
    const ox = a < 0 ? -1 : a >= t ? 1 : 0;
    const oy = b < 0 ? -1 : b >= t ? 1 : 0;
    const v = near[(oy + 1) * 3 + ox + 1];
    if (v) return v[(b - oy * t) * t + (a - ox * t)];
    const ca = Math.min(t - 1, Math.max(0, a));
    const cb = Math.min(t - 1, Math.max(0, b));
    return own[cb * t + ca];
  };
}

function DetailTile({ world, level, tx, ty, onPick }: { world: WorldInfo; level: LodLevelInfo; tx: number; ty: number; onPick: Props['onPick'] }) {
  const t = world.tileSize;
  const n = t + 1;
  const r = refinement(world, level);
  const geo = useMemo(() => gridGeometry(t), [t]);
  const mesh = useRef<THREE.Mesh>(null);
  const key = lodKey(level.level, tx, ty);
  // own tile and the east / north neighbours (their first row/column closes the seam)
  const rev = useStore((s) => {
    const lr = s.lodRevision;
    return (lr[key] ?? 0) + (lr[lodKey(level.level, tx + 1, ty)] ?? 0) * 7 + (lr[lodKey(level.level, tx, ty + 1)] ?? 0) * 13 + (lr[lodKey(level.level, tx + 1, ty + 1)] ?? 0) * 17;
  });
  // colours follow the core tile underneath (ash, deposits, temperature)
  const coreKey = tileKey(Math.floor((tx * t) / r / t), Math.floor((ty * t) / r / t));
  const coreRev = useStore((s) => s.tileRevision[coreKey] ?? 0);
  const vExag = useStore((s) => s.verticalExaggeration);
  const dExag = useStore((s) => s.deformationExaggeration);
  const mode = useStore((s) => s.colorMode);
  const units = useStore((s) => s.units);
  const shadows = useStore((s) => QUALITY[s.quality].shadows);

  useEffect(() => {
    queueRebuild(key, () => {
      const loaded = getLodTile(level.level, tx, ty) !== undefined;
      if (mesh.current) mesh.current.visible = loaded;
      if (!loaded) {
        detailHeights.delete(key);
        return;
      }
      const rawElev = lodReader(level.level, tx, ty, t);
      // the same edge-preserving smoothing as the core (block steps go, craters and rims stay), over
      // one column's worth of detail cells
      const q = worldQuantum(world, getTile(Field.SurfaceElevation, Math.floor(tx / r), Math.floor(ty / r))?.values);
      const deps = [];
      for (let oy = -1; oy <= 1; oy++) for (let ox = -1; ox <= 1; ox++) deps.push(getLodTile(level.level, tx + ox, ty + oy));
      const elevR = cachedElevation(key, deps, n, r, q, rawElev);
      const c = level.cellSize;
      // column fields interpolated between column centres, so colours do not come in column squares
      const fc = (a: number) => (tx * t + a + 0.5) / r - 0.5;
      const fr = (b: number) => (ty * t + b + 0.5) / r - 0.5;
      const smooth = (f: FieldId, fb = 0) => (a: number, b: number) => {
        const x = fc(a);
        const y = fr(b);
        const i = Math.floor(x);
        const j = Math.floor(y);
        const u = x - i;
        const v = y - j;
        const s00 = sampleColumn(world, f, i, j, fb);
        const s10 = sampleColumn(world, f, i + 1, j, fb);
        const s01 = sampleColumn(world, f, i, j + 1, fb);
        const s11 = sampleColumn(world, f, i + 1, j + 1, fb);
        return (s00 * (1 - u) + s10 * u) * (1 - v) + (s01 * (1 - u) + s11 * u) * v;
      };
      const nearest = (f: FieldId, fb = 0) => (a: number, b: number) => sampleColumn(world, f, Math.floor((tx * t + a) / r), Math.floor((ty * t + b) / r), fb);
      const fields: GroundFields = {
        ash: smooth(Field.AshDepth),
        temp: smooth(Field.SurfaceTemperature, 10),
        wt: smooth(Field.WaterTableDepth, 10),
        unit: nearest(Field.TopUnit),
        uplift: smooth(Field.Uplift),
        steam: smooth(Field.SteamFraction),
      };
      // lava films tint the detail ground as they tint the core's (thicker lava is the core's sheet)
      const lavaD = smooth(Field.LavaDepth);
      const lavaT = smooth(Field.LavaTemperature);
      const lavaRgb: RGB = [0, 0, 0];
      const [eLo, eHi] = world.elevationRange;
      const gp = geo.getAttribute('position') as THREE.BufferAttribute;
      const gc = geo.getAttribute('color') as THREE.BufferAttribute;
      const gn = geo.getAttribute('normal') as THREE.BufferAttribute;
      const shown = new Float32Array(n * n);
      const rgb: RGB = [0, 0, 0];
      let maxUplift = 1e-6;
      if (mode === 'uplift') for (let b = 0; b < n; b += r) for (let a = 0; a < n; a += r) maxUplift = Math.max(maxUplift, Math.abs(fields.uplift(a, b)));
      for (let b = 0; b < n; b++) {
        for (let a = 0; a < n; a++) {
          const v = b * n + a;
          const x = world.origin[0] + (tx * t + a + 0.5) * c;
          const y = world.origin[1] + (ty * t + b + 0.5) * c;
          const elev = elevR(a, b);
          const z = (elev + fields.uplift(a, b) * (dExag - 1)) * vExag;
          gp.setXYZ(v, x, z, -y);
          shown[v] = z;
          const hx = ((elevR(a + 1, b) - elevR(a - 1, b)) * vExag) / (2 * c);
          const hy = ((elevR(a, b + 1) - elevR(a, b - 1)) * vExag) / (2 * c);
          const inv = 1 / Math.hypot(hx, 1, hy);
          gn.setXYZ(v, -hx * inv, inv, hy * inv);
          colourGround(mode, world, fields, a, b, elev, eLo, eHi, maxUplift, units, rgb);
          const ld = mode === 'natural' ? lavaD(a, b) : 0;
          if (ld > 0.02) {
            lavaSurfaceColor(lavaT(a, b), CRUST_RGB, crustLight(-hx * inv, inv, hy * inv), lavaRgb, crackPattern(x, y));
            const f = Math.min(1, ld / 0.5);
            for (let k = 0; k < 3; k++) rgb[k] += (lavaRgb[k] - rgb[k]) * f;
          }
          gc.setXYZ(v, lin(rgb[0]), lin(rgb[1]), lin(rgb[2]));
        }
      }
      gp.needsUpdate = true;
      gc.needsUpdate = true;
      gn.needsUpdate = true;
      geo.computeBoundingSphere();
      geo.computeBoundingBox();
      detailHeights.set(key, { z: shown, vExag, dExag });
      // the core tile's lava, lahar and water sheets sit on this ground: let them follow
      requeueCore(coreKey);
    });
  }, [rev, coreRev, vExag, dExag, mode, units, world, level, tx, ty, t, n, r, geo, key]);

  useEffect(
    () => () => {
      cancelRebuild(key);
      detailHeights.delete(key);
      forgetElevation(key);
      geo.dispose();
    },
    [key, geo],
  );

  return (
    <mesh
      ref={mesh}
      visible={false}
      geometry={geo}
      castShadow={shadows}
      receiveShadow={shadows}
      onClick={(e) => {
        if (notATap(e)) return;
        e.stopPropagation();
        onPick([e.point.x, -e.point.z], e);
      }}
    >
      <primitive object={groundMaterial} attach="material" />
    </mesh>
  );
}
