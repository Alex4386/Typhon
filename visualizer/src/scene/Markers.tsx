import { useFrame } from '@react-three/fiber';
import { useMemo, useRef } from 'react';
import * as THREE from 'three';
import type { SimEvent, WorldInfo, XY } from '../protocol/messages';
import { useStore } from '../store/store';
import { FEATURE_COLORS } from '../util/color';
import { displayZ } from './Terrain';

/** A polyline as a THREE.Line (1px; renderer-agnostic). */
function Polyline({ points, color, depthTest = true, opacity = 1 }: { points: [number, number, number][]; color: string; depthTest?: boolean; opacity?: number }) {
  const line = useMemo(() => {
    const geo = new THREE.BufferGeometry().setFromPoints(points.map((p) => new THREE.Vector3(...p)));
    const mat = new THREE.LineBasicMaterial({ color, depthTest, transparent: opacity < 1 || !depthTest, opacity });
    const l = new THREE.Line(geo, mat);
    l.renderOrder = depthTest ? 4 : 10;
    return l;
  }, [points, color, depthTest, opacity]);
  return <primitive object={line} />;
}

function drape(world: WorldInfo, pts: XY[], vExag: number, dExag: number, lift: number, step: number): [number, number, number][] {
  const out: [number, number, number][] = [];
  for (let s = 0; s + 1 < pts.length; s++) {
    const [a, b] = [pts[s], pts[s + 1]];
    const len = Math.hypot(b[0] - a[0], b[1] - a[1]);
    const n = Math.max(1, Math.ceil(len / step));
    for (let k = 0; k <= n; k++) {
      if (k === n && s + 2 < pts.length) continue;
      const x = a[0] + ((b[0] - a[0]) * k) / n;
      const y = a[1] + ((b[1] - a[1]) * k) / n;
      out.push([x, displayZ(world, x, y, vExag, dExag) + lift, -y]);
    }
  }
  return out;
}

/**
 * Shrinks its child markers as the camera approaches them (full size beyond ~600 m, down to a
 * quarter), and hides those within a few metres, so close-up and walk views are not crowded by
 * markers sized for an overview.
 */
function DistanceScaled({ children }: { children: React.ReactNode }) {
  const group = useRef<THREE.Group>(null);
  useFrame(({ camera }) => {
    const g = group.current;
    if (!g) return;
    for (const child of g.children) {
      const d = child.position.distanceTo(camera.position);
      const s = Math.min(1, Math.max(0.25, d / 600));
      child.scale.setScalar(s);
      child.visible = d > 15;
    }
  });
  return <group ref={group}>{children}</group>;
}

export function Markers({ world }: { world: WorldInfo }) {
  const vExag = useStore((s) => s.verticalExaggeration);
  const dExag = useStore((s) => s.deformationExaggeration);
  const showChambers = useStore((s) => s.showChambers);
  const showFeatures = useStore((s) => s.showFeatures);
  const polyline = useStore((s) => s.sectionPolyline);
  const events = useStore((s) => s.events);
  const state = useStore((s) => s.state);
  const rev = useStore((s) => Object.keys(s.tileRevision).length);

  const dikes = useMemo(() => {
    const latest = new Map<string, Extract<SimEvent, { kind: 'dikeAdvanced' }>>();
    for (const e of events) if (e.kind === 'dikeAdvanced') latest.set(`${e.volcanoId}:${e.dikeId}`, e);
    return [...latest.values()];
  }, [events]);

  // One marker per (feature, spot): a real server reports the same fumarole or deposit repeatedly as it grows.
  const features = useMemo(() => {
    const byKey = new Map<string, Extract<SimEvent, { kind: 'geothermalFeature' }>>();
    for (const e of events) {
      if (e.kind !== 'geothermalFeature') continue;
      byKey.set(`${e.feature}:${Math.round(e.at[0] / 20)}:${Math.round(e.at[1] / 20)}`, e);
    }
    return [...byKey.values()].slice(-150);
  }, [events]);
  const fissures = useMemo(() => events.filter((e): e is Extract<SimEvent, { kind: 'fissureOpened' }> => e.kind === 'fissureOpened'), [events]);

  const sectionPts = useMemo(() => (polyline.length > 0 ? drape(world, polyline.length === 1 ? [polyline[0], polyline[0]] : polyline, vExag, dExag, 6 * vExag, world.cellSize * 2) : []), [world, polyline, vExag, dExag, rev]);

  const vents = world.volcanoes.flatMap((v) => v.vents.map((vent) => ({ v, vent }))).concat(fissures.map((f) => ({ v: world.volcanoes.find((x) => x.id === f.volcanoId)!, vent: f.vent })));

  return (
    <group>
      {vents.map(({ vent }) => {
        const z = displayZ(world, vent.at[0], vent.at[1], vExag, dExag);
        return (
          <mesh key={vent.id} position={[vent.at[0], z + 4, -vent.at[1]]} rotation={[-Math.PI / 2, 0, 0]}>
            <torusGeometry args={[Math.max(30, vent.radius), 4, 6, 48]} />
            <meshBasicMaterial color="#ffb347" />
          </mesh>
        );
      })}

      {showChambers &&
        world.volcanoes.map((v) => {
          const st = state?.volcanoes[v.id];
          const hot = st ? Math.min(1, st.chamber.overpressureMPa / st.chamber.tensileStrengthMPa) : 0.5;
          const [cx, cy, cz] = v.chamber.center;
          return (
            <group key={v.id}>
              <mesh position={[cx, cz * vExag, -cy]} scale={[v.chamber.radius, (v.chamber.radius / 1.8) * vExag, v.chamber.radius]} renderOrder={9}>
                <sphereGeometry args={[1, 32, 16]} />
                <meshBasicMaterial color={new THREE.Color().setHSL(0.06 - hot * 0.05, 1, 0.45 + hot * 0.1)} transparent opacity={0.18} depthTest={false} depthWrite={false} />
              </mesh>
              {v.vents.map((vent) => (
                <Polyline key={vent.id} points={[[cx, (cz + v.chamber.radius / 1.8) * vExag, -cy], [vent.at[0], displayZ(world, vent.at[0], vent.at[1], vExag, dExag), -vent.at[1]]]} color={st && st.chamber.eruptionRate > 0 ? '#ff6a2a' : '#7a4a3a'} depthTest={false} opacity={0.8} />
              ))}
            </group>
          );
        })}

      {dikes.map((d) => (
        <Polyline key={`${d.volcanoId}:${d.dikeId}`} points={d.path.map((p) => [p[0], p[2] * vExag, -p[1]])} color="#ff3b6b" depthTest={false} opacity={0.9} />
      ))}

      {state &&
        Object.values(state.volcanoes).flatMap((v) =>
          v.deformation.stations.map((st) => {
            const z = displayZ(world, st.at[0], st.at[1], vExag, dExag);
            const arrow = 4000; // displacement exaggeration for display
            return (
              <group key={st.id}>
                <mesh position={[st.at[0], z + 25, -st.at[1]]}>
                  <coneGeometry args={[18, 50, 4]} />
                  <meshBasicMaterial color="#e6e6ff" />
                </mesh>
                <Polyline points={[[st.at[0], z + 60, -st.at[1]], [st.at[0] + st.east * arrow, z + 60 + st.up * arrow * vExag, -(st.at[1] + st.north * arrow)]]} color="#7cf" depthTest={false} />
              </group>
            );
          }),
        )}

      {showFeatures && (
        <DistanceScaled>
          {features.map((f, k) => (
            <mesh key={k} position={[f.at[0], displayZ(world, f.at[0], f.at[1], vExag, dExag) + 12, -f.at[1]]}>
              <octahedronGeometry args={[16, 0]} />
              <meshBasicMaterial color={FEATURE_COLORS[f.feature] ?? '#ffffff'} />
            </mesh>
          ))}
        </DistanceScaled>
      )}

      {sectionPts.length > 1 && <Polyline points={sectionPts} color="#ffe14d" depthTest={false} />}
      {polyline.map((p, k) => (
        <mesh key={k} position={[p[0], displayZ(world, p[0], p[1], vExag, dExag) + 10 * vExag, -p[1]]}>
          <sphereGeometry args={[22, 12, 8]} />
          <meshBasicMaterial color="#ffe14d" depthTest={false} transparent />
        </mesh>
      ))}
    </group>
  );
}
