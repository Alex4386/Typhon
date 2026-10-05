import { useEffect, type ReactNode } from 'react';
import { Crosshair, Ruler, X } from 'lucide-react';
import { Tip } from '@/components/tip';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Kbd } from '@/components/ui/kbd';
import { Separator } from '@/components/ui/separator';
import { cn } from '@/lib/utils';
import { useCamera } from '../camera/cameraStore';
import { inspect } from '../net/connection';
import type { EntityProp, InspectionMessage, WorldInfo } from '../protocol/messages';
import { sectionThrough, selectionAnchor } from '../scene/picking';
import { KIND_LABEL, entityColor, formatPlace, type EntityView, type Selection } from '../store/entities';
import { useStore } from '../store/store';
import { FEATURE_COLORS } from '../util/color';
import { formatSimTime, worldExtent } from '../util/world';
import { OVERLAY } from './Overlay';
import { HIDDEN_PROPS, formatProp, propLabel } from './props';

/** The selection's column is asked about again this often while it stays selected (ms). */
const REFRESH_MS = 2000;

/** Where to ask the server about: the selection's map position, if it has one on the map. */
function inspectAt(sel: Selection | null, e: EntityView | undefined): [number, number] | null {
  if (!sel) return null;
  if (sel.type === 'point') return sel.at;
  if (sel.type === 'quake') return [sel.event.hypocenter[0], sel.event.hypocenter[1]];
  if (!e || e.kind === 'lavaField') return null;
  return [e.at[0], e.at[1]];
}

/**
 * Properties of what is selected in the 3D view or the Entities panel, live: the entity's own
 * values plus everything the server knows about the ground column there (layers, heat, water).
 */
export function Inspector({ world }: { world: WorldInfo }) {
  const selection = useStore((s) => s.selection);
  const entity = useStore((s) => (s.selection?.type === 'entity' ? s.entities[s.selection.id] : undefined));
  const inspection = useStore((s) => s.inspection);
  const at = inspectAt(selection, entity);
  const ax = at?.[0];
  const ay = at?.[1];

  useEffect(() => {
    if (ax === undefined || ay === undefined) return;
    inspect(ax, ay);
    const id = window.setInterval(() => {
      const c = useStore.getState().clock;
      if (c && c.mode !== 'PAUSED' && !document.hidden) inspect(ax, ay);
    }, REFRESH_MS);
    return () => window.clearInterval(id);
  }, [ax, ay]);

  if (!selection) return null;
  const close = () => useStore.getState().select(null);
  const frame = () => useCamera.getState().requestCamera({ kind: 'frameSelection' });
  const cut = () => {
    const s = useStore.getState();
    const ext = worldExtent(world);
    const span = Math.max(2000, Math.min(Math.max(ext.maxX - ext.minX, ext.maxY - ext.minY) * 0.5, 12000));
    const line = sectionThrough(selection, s.entities, entity?.kind === 'chamber' ? span * 1.5 : span);
    if (line) s.set({ sectionPolyline: line, drawer: 'section', tool: 'orbit' });
  };

  let title: string;
  let kind: string;
  let color = '#ffe14d';
  if (selection.type === 'entity') {
    title = entity?.label ?? 'Gone';
    kind = entity ? (KIND_LABEL[entity.kind] ?? entity.kind) : 'Removed';
    if (entity) color = entityColor(entity, FEATURE_COLORS);
  } else if (selection.type === 'quake') {
    title = `M ${selection.event.magnitude.toFixed(1)} ${selection.event.type} earthquake`;
    kind = 'Earthquake';
    color = '#ffd166';
  } else {
    title = 'Ground';
    kind = 'Point on the map';
  }
  const anchor = selectionAnchor(selection, useStore.getState().entities);

  return (
    <aside className={cn(OVERLAY, 'absolute top-3 right-3 z-10 flex max-h-[calc(100%-5.5rem)] w-80 flex-col text-sm')} aria-label="Inspector">
      <div className="flex items-start gap-2 p-3 pb-2">
        <span className="mt-1 size-3 shrink-0 rounded-full ring-1 ring-black/40" style={{ background: color }} aria-hidden />
        <div className="min-w-0 flex-1">
          <h2 className="truncate font-semibold" title={title}>
            {title}
          </h2>
          <p className="text-xs text-muted-foreground">
            {kind}
            {anchor && ` · ${formatPlace(anchor)}`}
          </p>
        </div>
        <Tip content={<span>Close <Kbd>Esc</Kbd></span>}>
          <Button variant="ghost" size="icon-xs" aria-label="Clear selection" onClick={close}>
            <X />
          </Button>
        </Tip>
      </div>
      <div className="flex gap-1.5 px-3 pb-2">
        <Tip content={<span>Fly to it and frame it <Kbd>F</Kbd></span>}>
          <Button size="xs" variant="secondary" onClick={frame}>
            <Crosshair /> Frame
          </Button>
        </Tip>
        <Tip content="Cut a cross-section through it (along a dike or fissure, else west–east)">
          <Button size="xs" variant="secondary" onClick={cut}>
            <Ruler /> Cross-section
          </Button>
        </Tip>
      </div>
      <Separator />
      <div className="flex min-h-0 flex-col gap-3 overflow-y-auto p-3">
        {selection.type === 'entity' && entity && <EntityProps e={entity} />}
        {selection.type === 'entity' && !entity && <p className="text-muted-foreground">This no longer exists.</p>}
        {selection.type === 'quake' && <QuakeProps q={selection.event} />}
        {inspection && inspection.inside && at && Math.hypot(inspection.at[0] - at[0], inspection.at[1] - at[1]) <= world.cellSize * 1.5 && <Column c={inspection} world={world} />}
        {inspection && !inspection.inside && <p className="text-muted-foreground">Outside the simulated area.</p>}
      </div>
    </aside>
  );
}

function PropTable({ rows }: { rows: [string, string][] }) {
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
      {rows.map(([k, v]) => (
        <div key={k} className="contents">
          <dt className="text-muted-foreground">{k}</dt>
          <dd className="text-right tabular-nums">{v}</dd>
        </div>
      ))}
    </dl>
  );
}

function Heading({ children }: { children: ReactNode }) {
  return <h3 className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{children}</h3>;
}

function EntityProps({ e }: { e: EntityView }) {
  const rows: [string, string][] = Object.entries(e.props)
    .filter(([k, v]) => !HIDDEN_PROPS.has(k) && v !== null && v !== undefined)
    .map(([k, v]) => [propLabel(k), formatProp(k, v as EntityProp)]);
  const removed = e.removedAt !== undefined;
  return (
    <section className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
        <span>Appeared {formatSimTime(typeof e.props.startedAt === 'number' ? e.props.startedAt : e.createdAt)}</span>
        <span>· updated {formatSimTime(e.updatedAt)}</span>
        {removed && <Badge variant="destructive">removed</Badge>}
      </div>
      {e.kind === 'dike' && e.path && e.path.length > 1 && (
        <p className="text-xs text-muted-foreground">
          From {Math.round(-e.path[0][2])} m below sea level up to {e.path[e.path.length - 1][2] >= 0 ? `${Math.round(e.path[e.path.length - 1][2])} m above` : `${Math.round(-e.path[e.path.length - 1][2])} m below`} sea level
        </p>
      )}
      <PropTable rows={rows} />
    </section>
  );
}

function QuakeProps({ q }: { q: { magnitude: number; type: string; time: number; hypocenter: [number, number, number]; durationSeconds: number; swarm: boolean } }) {
  return (
    <PropTable
      rows={[
        ['Magnitude', `M ${q.magnitude.toFixed(1)}`],
        ['Type', q.type],
        ['When', formatSimTime(q.time)],
        ['Depth', `${Math.round(-q.hypocenter[2])} m below sea level`],
        ['Duration', `${q.durationSeconds.toFixed(1)} s`],
        ['Part of a swarm', q.swarm ? 'yes' : 'no'],
      ]}
    />
  );
}

/** Colour of a material in the layer log: the world's suggestion, else a stable muted hue. */
function materialColor(name: string, world: WorldInfo): string {
  const m = world.materials.find((x) => x.name.toLowerCase() === name.toLowerCase());
  if (m?.color) return m.color;
  let h = 0;
  for (let k = 0; k < name.length; k++) h = (h * 31 + name.charCodeAt(k)) >>> 0;
  return `hsl(${h % 360} 28% ${38 + (h % 17)}%)`;
}

/** The ground column: surface, layers, water and temperature with depth. */
function Column({ c, world }: { c: InspectionMessage; world: WorldInfo }) {
  const rows: [string, string][] = [];
  if (c.surfaceZ !== undefined) rows.push(['Ground elevation', `${Math.round(c.surfaceZ)} m`]);
  if (c.surfaceMaterial) rows.push(['Surface', formatProp('material', c.surfaceMaterial)]);
  if (c.groundTemperatureC !== undefined) rows.push(['Ground temperature', `${Math.round(c.groundTemperatureC)} °C`]);
  if (c.water) {
    rows.push(['Water table', c.water.tableDepthM <= 0 ? 'at the surface' : `${c.water.tableDepthM.toFixed(1)} m down`]);
    if (c.water.surfaceWaterDepthM > 0) rows.push(['Standing water', `${c.water.surfaceWaterDepthM.toFixed(2)} m`]);
    if (c.water.steamFluxKgPerSm2 > 0) rows.push(['Steam flux', formatProp('steamFluxKgPerSm2', c.water.steamFluxKgPerSm2)]);
  }
  if (c.lava) rows.push(['Lava', `${c.lava.thicknessM.toFixed(1)} m thick · ${Math.round(c.lava.temperatureC)} °C · crust ${c.lava.crustM.toFixed(2)} m`]);
  if (c.pdc) rows.push(['Pyroclastic flow', `${c.pdc.depthM.toFixed(1)} m · ${c.pdc.speedMPerS.toFixed(0)} m/s · ${Math.round(c.pdc.temperatureC)} °C`]);
  if (c.lahar) rows.push(['Lahar', `${c.lahar.depthM.toFixed(1)} m · ${c.lahar.speedMPerS.toFixed(1)} m/s`]);
  const layers = c.layers ?? [];
  const top = layers[0]?.top ?? 0;
  const bottom = layers[layers.length - 1]?.bottom ?? top;
  const span = Math.max(1, top - bottom);
  const profile = c.temperatureProfile ?? [];
  const tMax = Math.max(100, ...profile.map((p) => p.temperatureC));
  const dMax = Math.max(1, ...profile.map((p) => p.depthM));
  return (
    <section className="flex flex-col gap-2">
      <Heading>Ground here</Heading>
      <PropTable rows={rows} />
      {layers.length > 0 && (
        <>
          <Heading>
            Layers, top down {c.layerCount && c.layerCount > layers.length ? `(top ${layers.length} of ${c.layerCount})` : ''}
          </Heading>
          <div className="flex gap-2">
            <div className="flex w-5 shrink-0 flex-col overflow-hidden rounded-sm ring-1 ring-border" style={{ height: 160 }} aria-hidden>
              {layers.map((l, k) => (
                <div key={k} style={{ flexGrow: Math.max(0.02, (l.top - l.bottom) / span), background: materialColor(l.material, world) }} />
              ))}
            </div>
            <ol className="flex min-w-0 flex-1 flex-col gap-0.5 text-xs">
              {layers.slice(0, 10).map((l, k) => (
                <li key={k} className="flex gap-1.5" title={`${l.label ?? ''} porosity ${(l.porosity * 100).toFixed(0)}%`}>
                  <span className="mt-0.5 size-2 shrink-0 rounded-[2px]" style={{ background: materialColor(l.material, world) }} />
                  <span className="min-w-0 flex-1 truncate">
                    {formatProp('material', l.material)}
                    {l.label && <span className="text-muted-foreground"> · {l.label}</span>}
                  </span>
                  <span className="text-muted-foreground tabular-nums">{(l.top - l.bottom).toFixed(l.top - l.bottom < 10 ? 1 : 0)} m</span>
                </li>
              ))}
              {layers.length > 10 && <li className="text-muted-foreground">… {layers.length - 10} more</li>}
            </ol>
          </div>
        </>
      )}
      {profile.length > 1 && (
        <>
          <Heading>Temperature with depth</Heading>
          <svg viewBox="0 0 280 110" className="w-full" role="img" aria-label="Temperature with depth">
            <line x1="30" y1="5" x2="30" y2="95" stroke="currentColor" strokeOpacity={0.25} />
            <line x1="30" y1="95" x2="275" y2="95" stroke="currentColor" strokeOpacity={0.25} />
            <text x="2" y="10" fontSize="9" fill="currentColor" opacity={0.6}>
              0 m
            </text>
            <text x="2" y="95" fontSize="9" fill="currentColor" opacity={0.6}>
              {dMax >= 1000 ? `${(dMax / 1000).toFixed(1)} km` : `${Math.round(dMax)} m`}
            </text>
            <text x="275" y="106" fontSize="9" fill="currentColor" opacity={0.6} textAnchor="end">
              {Math.round(tMax)} °C
            </text>
            <polyline
              fill="none"
              stroke="#ff7b39"
              strokeWidth={1.5}
              points={profile.map((p) => `${30 + (p.temperatureC / tMax) * 245},${5 + (p.depthM / dMax) * 90}`).join(' ')}
            />
            {profile.map((p, k) =>
              p.steam ? <circle key={k} cx={30 + (p.temperatureC / tMax) * 245} cy={5 + (p.depthM / dMax) * 90} r={2 + p.steam * 2} fill="#cfe8ff" opacity={0.8} /> : null,
            )}
          </svg>
        </>
      )}
    </section>
  );
}
