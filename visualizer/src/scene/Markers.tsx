import { useMemo } from 'react';
import * as THREE from 'three';
import type { WorldInfo, XY } from '../protocol/messages';
import { useStore } from '../store/store';
import { pickHandlers } from './EntityMarkers';
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
 * Magma chambers with their conduits, GNSS displacement arrows and the cross-section line.
 * Vents, dikes, springs, stations and the rest are entities (EntityMarkers).
 */
export function Markers({ world }: { world: WorldInfo }) {
  const vExag = useStore((s) => s.verticalExaggeration);
  const dExag = useStore((s) => s.deformationExaggeration);
  const showChambers = useStore((s) => s.showChambers);
  const polyline = useStore((s) => s.sectionPolyline);
  const state = useStore((s) => s.state);
  const selection = useStore((s) => s.selection);
  const hoverId = useStore((s) => s.hoverId);
  const rev = useStore((s) => Object.keys(s.tileRevision).length);

  const sectionPts = useMemo(
    () => (polyline.length > 0 ? drape(world, polyline.length === 1 ? [polyline[0], polyline[0]] : polyline, vExag, dExag, 6 * vExag, world.cellSize * 2) : []),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [world, polyline, vExag, dExag, rev],
  );

  return (
    <group>
      {showChambers &&
        world.volcanoes.map((v) => {
          const st = state?.volcanoes[v.id];
          const hot = st ? Math.min(1, st.chamber.overpressureMPa / st.chamber.tensileStrengthMPa) : 0.5;
          const [cx, cy, cz] = v.chamber.center;
          const id = `chamber:${v.id}`;
          const lit = (selection?.type === 'entity' && selection.id === id) || hoverId === id;
          return (
            <group key={v.id}>
              <mesh
                position={[cx, cz * vExag, -cy]}
                scale={[v.chamber.radius, (v.chamber.radius / 1.8) * vExag, v.chamber.radius]}
                renderOrder={9}
                {...pickHandlers({ pick: { type: 'entity', id }, label: `${v.name} magma chamber`, detail: st ? `${st.chamber.overpressureMPa.toFixed(1)} MPa overpressure` : undefined })}
              >
                <sphereGeometry args={[1, 32, 16]} />
                <meshBasicMaterial color={new THREE.Color().setHSL(0.06 - hot * 0.05, 1, 0.45 + hot * 0.1 + (lit ? 0.15 : 0))} transparent opacity={lit ? 0.32 : 0.18} depthTest={false} depthWrite={false} />
              </mesh>
              {v.vents.map((vent) => (
                <Polyline
                  key={vent.id}
                  points={[
                    [cx, (cz + v.chamber.radius / 1.8) * vExag, -cy],
                    [vent.at[0], displayZ(world, vent.at[0], vent.at[1], vExag, dExag), -vent.at[1]],
                  ]}
                  color={st && st.chamber.eruptionRate > 0 ? '#ff6a2a' : '#7a4a3a'}
                  depthTest={false}
                  opacity={0.8}
                />
              ))}
            </group>
          );
        })}

      {state &&
        Object.values(state.volcanoes).flatMap((v) =>
          v.deformation.stations.map((st) => {
            const z = displayZ(world, st.at[0], st.at[1], vExag, dExag);
            const arrow = 4000; // displacement exaggeration for display
            return (
              <Polyline
                key={st.id}
                points={[
                  [st.at[0], z + 60, -st.at[1]],
                  [st.at[0] + st.east * arrow, z + 60 + st.up * arrow * vExag, -(st.at[1] + st.north * arrow)],
                ]}
                color="#7cf"
                depthTest={false}
              />
            );
          }),
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
