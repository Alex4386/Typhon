import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { ArrowUpFromDot, ChevronDown, ChevronUp, Droplets, Flame, Pickaxe, Plus, Ruler, Square, Triangle, Wrench } from 'lucide-react';
import { SimpleSelect } from '@/components/fields';
import { Tip } from '@/components/tip';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DropdownMenu, DropdownMenuCheckboxItem, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { command } from '../net/connection';
import type { ParamSpec, ParamValue, SimCommand, VolcanoState, WorldInfo } from '../protocol/messages';
import { useStore, type Tool } from '../store/store';
import { ALERT_COLORS } from '../util/color';
import { formatDuration, formatFactor, worldExtent } from '../util/world';
import { SHORT_VIEWPORT, useMediaQuery } from '../util/useMediaQuery';
import { ALERT_LABEL, REGIME_LABEL, STYLE_LABEL } from './events';
import { FALLBACK_INJECT_FIELDS, MAGMA_PRESETS, fieldError, formatVolume, injectWarnings, mixPreview } from './inject';
import { injectFieldsFor } from './actions';
import { ParamInput } from './ParamInput';

function useVolcano(world: WorldInfo): { id: string | undefined; name: string; vs: VolcanoState | undefined } {
  const selected = useStore((s) => s.selectedVolcano);
  const state = useStore((s) => s.state);
  const id = selected ?? world.volcanoes[0]?.id;
  const name = world.volcanoes.find((v) => v.id === id)?.name ?? id ?? '';
  return { id, name, vs: id ? state?.volcanoes[id] : undefined };
}

/** Overlay card style over the 3D view. */
export const OVERLAY = 'pointer-events-auto rounded-xl border bg-card/90 shadow-lg backdrop-blur';

function StatRow({ label, help, children }: { label: string; help: string; children: ReactNode }) {
  return (
    <>
      <Tip content={help} side="right">
        <dt className="text-muted-foreground">{label}</dt>
      </Tip>
      <dd className="text-right tabular-nums">{children}</dd>
    </>
  );
}

/** Compact summary of the selected volcano, top-left over the 3D view. */
export function StatusCard({ world }: { world: WorldInfo }) {
  const { id, name, vs } = useVolcano(world);
  const setStore = useStore((s) => s.set);
  const set = useStore((s) => s.set);
  const short = useMediaQuery(SHORT_VIEWPORT);
  const [collapsed, setCollapsed] = useState(short);
  useEffect(() => setCollapsed(short), [short]); // short viewports start with the one-line summary
  if (!id) {
    // an empty world: nothing here until the user places a magma chamber
    return (
      <section className={`${OVERLAY} flex w-72 max-w-full flex-col gap-2 p-3 text-sm`} aria-label="Empty world">
        <strong>Nothing here yet</strong>
        <p className="text-muted-foreground">
          This world has no volcano. Place a magma chamber below the ground (or the sea floor) and let its eruptions build whatever forms.
        </p>
        <Button
          size="sm"
          onClick={() => {
            const e = worldExtent(world);
            setStore({ buildDraft: { kind: 'chamber', volcanoId: null, at: [(e.minX + e.maxX) / 2, (e.minY + e.maxY) / 2], values: {} }, drawer: 'build', tool: 'chamber' });
          }}
        >
          <Plus /> Place a magma chamber to start
        </Button>
        <p className="text-xs text-muted-foreground">Then click the map to move it, or type its position; set its depth, size and magma in the Build panel.</p>
      </section>
    );
  }
  const level = vs?.alert.level ?? 'DORMANT';
  const erupting = (vs?.chamber.eruptionRate ?? 0) > 0 || level === 'ERUPTING';
  const pressure = vs && vs.chamber.tensileStrengthMPa > 0 ? vs.chamber.overpressureMPa / vs.chamber.tensileStrengthMPa : null;
  const tc = vs?.timeCompression;
  return (
    <section className={`${OVERLAY} flex max-h-full min-h-0 w-72 max-w-full flex-col p-3 text-sm`} aria-label="Volcano status">
      <div className="flex shrink-0 items-center gap-2">
        {world.volcanoes.length > 1 ? (
          <SimpleSelect label="Volcano" value={id} onChange={(v) => set({ selectedVolcano: v })} options={world.volcanoes.map((v) => [v.id, v.name] as const)} />
        ) : (
          <strong className="truncate">{name}</strong>
        )}
        <Badge className="text-white" style={{ background: ALERT_COLORS[level] ?? '#444' }}>
          {ALERT_LABEL[level] ?? level}
        </Badge>
        <span className="flex-1" />
        <Tip content={collapsed ? 'Show details' : 'Hide details'}>
          <Button variant="ghost" size="icon-xs" aria-label={collapsed ? 'Expand status' : 'Collapse status'} aria-expanded={!collapsed} onClick={() => setCollapsed(!collapsed)}>
            {collapsed ? <ChevronDown /> : <ChevronUp />}
          </Button>
        </Tip>
      </div>
      {!collapsed && vs && (
        <div className="-mr-2 min-h-0 overflow-y-auto overscroll-contain pr-2">
          <p className="mt-2 font-medium">
            {erupting
              ? vs.alert.style
                ? `${STYLE_LABEL[vs.alert.style]?.split(' (')[0] ?? vs.alert.style} eruption — ${REGIME_LABEL[vs.chamber.regime] ?? vs.chamber.regime}`
                : `Eruption (type still being estimated) — ${REGIME_LABEL[vs.chamber.regime] ?? vs.chamber.regime}`
              : vs.seismic.swarm
                ? 'Earthquake swarm under the volcano'
                : vs.seismic.tremor
                  ? 'Volcanic tremor: magma or gas on the move'
                  : 'No eruption'}
          </p>
          <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs">
            {erupting && (
              <StatRow label="Lava output" help="Dense-rock-equivalent volume of magma leaving the vent, per second of volcano time (comparable with real eruptions)">
                {formatRate(vs.chamber.physicalEruptionRate ?? vs.chamber.eruptionRate)} m³/s
              </StatRow>
            )}
            {vs.plume && vs.plume.topZ > 0 && (
              <StatRow label="Ash column" help="Height of the eruption column above sea level">
                {(vs.plume.topZ / 1000).toFixed(1)} km
              </StatRow>
            )}
            {pressure !== null && (
              <StatRow label="Chamber pressure" help="Magma pressure as a share of what the rock around the chamber can hold before it cracks open">
                <span className="flex items-center justify-end gap-2">
                  <span className="h-1.5 w-16 overflow-hidden rounded-full bg-muted" aria-hidden>
                    <i className="block h-full" style={{ width: `${Math.min(100, Math.max(0, pressure * 100))}%`, background: pressure > 0.9 ? '#f85149' : pressure > 0.6 ? '#d29922' : '#3fb950' }} />
                  </span>
                  {Math.round(pressure * 100)}% of limit
                </span>
              </StatRow>
            )}
            <StatRow label="Quakes" help="Rock-breaking (VT) earthquakes per minute">
              {vs.seismic.vtPerMinute.toFixed(1)} / min
            </StatRow>
            <StatRow label="Ground uplift" help="Largest ground uplift measured by the GPS stations">
              {(vs.deformation.maxUpliftM * 100).toFixed(1)} cm
            </StatRow>
            {tc && (
              <StatRow label="Time scale" help={`Volcano time runs ${formatFactor(tc.dormant)} faster than simulated time while quiet and ${formatFactor(tc.eruptive)} while erupting.`}>
                {formatFactor(tc.current)}
                {vs.physicalTime !== undefined && <span className="text-muted-foreground"> · ≈ {formatDuration(vs.physicalTime)}</span>}
              </StatRow>
            )}
          </dl>
          <div className="mt-2 flex gap-1">
            <Button variant="link" size="xs" className="h-auto p-0" onClick={() => set({ drawer: 'monitor' })}>
              Instruments →
            </Button>
            <span className="flex-1" />
            <Button variant="link" size="xs" className="h-auto p-0" onClick={() => useStore.getState().select({ type: 'entity', id: `chamber:${id}` })}>
              Inspect chamber
            </Button>
          </div>
        </div>
      )}
    </section>
  );
}

/** Magma injection: volume, temperature and composition (fields come from the server schema). */
export function InjectDialog({ world, volcanoId, open, onOpenChange }: { world: WorldInfo; volcanoId: string | null; open: boolean; onOpenChange: (o: boolean) => void }) {
  const fallback = useVolcano(world);
  const id = volcanoId ?? fallback.id;
  const name = world.volcanoes.find((v) => v.id === id)?.name ?? id ?? '';
  const vs = useStore((s) => (id ? s.state?.volcanoes[id] : undefined));
  const schema = useStore((s) => s.schema);
  // this volcano's own fields: defaults are its supply magma, not the first volcano's
  const fields: ParamSpec[] = injectFieldsFor(schema, id) ?? FALLBACK_INJECT_FIELDS;
  const defaults = useMemo(() => {
    const d: Record<string, ParamValue> = {};
    for (const f of fields) if (f.value !== undefined && f.value !== null) d[f.id] = f.value;
    else if (f.default !== undefined) d[f.id] = f.default;
    return d;
  }, [fields]);
  const [values, setValues] = useState<Record<string, ParamValue>>(defaults);
  // a new target volcano starts from its own defaults; schema refreshes keep the user's edits
  const lastId = useRef(id);
  useEffect(() => {
    const switched = lastId.current !== id;
    lastId.current = id;
    setValues((v) => (switched ? { ...defaults, volumeM3: v.volumeM3 ?? defaults.volumeM3 } : { ...defaults, ...v }));
  }, [defaults, id]);
  const has = (k: string) => fields.some((f) => f.id === k);
  const errors = Object.fromEntries(fields.map((f) => [f.id, fieldError(f, values[f.id])]));
  const ok = Object.values(errors).every((e) => e === null) && !!id;
  const preview = vs ? mixPreview(vs, values) : null;
  const warnings = injectWarnings(values, vs);
  const groups = [...new Set(fields.map((f) => f.group))];
  const inject = () => {
    if (!ok || !id) return;
    const c: Record<string, string | number> = { kind: 'injectMagma', volcanoId: id };
    for (const f of fields) {
      const v = values[f.id];
      if (typeof v === 'number' || typeof v === 'string') c[f.id] = v;
      else if (typeof v === 'boolean') c[f.id] = v ? 1 : 0;
    }
    command(c as unknown as SimCommand);
    useStore.getState().toast(`Injecting ${formatVolume(Number(values.volumeM3))} of magma into ${name}`, 'info');
    onOpenChange(false);
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-lg">
        <form
          className="flex flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault();
            inject();
          }}
        >
          <DialogHeader>
            <DialogTitle>Add magma to {name}</DialogTitle>
            <DialogDescription>A new batch of magma rises into the chamber and mixes with what is there. More pressure means more quakes, swelling and eventually an eruption.</DialogDescription>
          </DialogHeader>
          {has('temperatureC') && (
            <div className="flex flex-wrap gap-1.5" role="group" aria-label="Magma type">
              {vs && (
                <Tip content="Same temperature and composition as the magma in the chamber now">
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => setValues({ ...values, temperatureC: Math.round(vs.chamber.temperatureC), silicaWt: Number(vs.chamber.silicaWt.toFixed(1)), waterWt: Number(vs.chamber.waterWt.toFixed(1)) })}
                  >
                    Like the chamber
                  </Button>
                </Tip>
              )}
              {MAGMA_PRESETS.map((p) => (
                <Tip key={p.name} content={p.help}>
                  <Button type="button" variant="outline" size="sm" onClick={() => setValues({ ...values, ...Object.fromEntries(Object.entries(p.values).filter(([k]) => has(k))) })}>
                    {p.name}
                  </Button>
                </Tip>
              ))}
            </div>
          )}
          {groups.map((g) => (
            <fieldset key={g} className="flex flex-col gap-3">
              {groups.length > 1 && <legend className="mb-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">{g}</legend>}
              {fields
                .filter((f) => f.group === g)
                .map((f) => (
                  <div className="flex flex-col gap-1.5" key={f.id}>
                    <Label htmlFor={`p-${f.id}`}>{f.label}</Label>
                    <ParamInput spec={f} value={values[f.id]} invalid={!!errors[f.id]} onChange={(v) => setValues({ ...values, [f.id]: v })} />
                    {errors[f.id] ? <p className="text-xs text-destructive">{errors[f.id]}</p> : f.help ? <p className="text-xs text-muted-foreground">{f.help}</p> : null}
                  </div>
                ))}
            </fieldset>
          ))}
          {preview && vs && (
            <div aria-live="polite" className="rounded-lg border p-2">
              <p className="px-2 text-sm">
                <b>After mixing</b> <span className="text-xs text-muted-foreground">(new magma is {(preview.fraction * 100).toPrecision(2)}% of the chamber; rough estimate)</span>
              </p>
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead />
                    <TableHead className="text-right">now</TableHead>
                    <TableHead className="text-right">after</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody className="tabular-nums">
                  <TableRow>
                    <TableCell>Temperature</TableCell>
                    <TableCell className="text-right">{vs.chamber.temperatureC.toFixed(0)} °C</TableCell>
                    <TableCell className="text-right">{preview.temperatureC.toFixed(0)} °C</TableCell>
                  </TableRow>
                  <TableRow>
                    <TableCell>Silica</TableCell>
                    <TableCell className="text-right">{vs.chamber.silicaWt.toFixed(1)} wt%</TableCell>
                    <TableCell className="text-right">{preview.silicaWt.toFixed(1)} wt%</TableCell>
                  </TableRow>
                  <TableRow>
                    <TableCell>Water</TableCell>
                    <TableCell className="text-right">{vs.chamber.waterWt.toFixed(2)} wt%</TableCell>
                    <TableCell className="text-right">{preview.waterWt.toFixed(2)} wt%</TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </div>
          )}
          {warnings.length > 0 && (
            <ul className="list-disc rounded-lg border border-amber-500/40 bg-amber-500/10 py-2 pr-3 pl-7 text-sm text-amber-200">
              {warnings.map((w) => (
                <li key={w}>{w}</li>
              ))}
            </ul>
          )}
          {!has('temperatureC') && <p className="text-xs text-muted-foreground">This server only accepts a volume; temperature and composition use the volcano's configured recharge magma.</p>}
          <DialogFooter>
            <DialogClose render={<Button type="button" variant="outline" />}>Cancel</DialogClose>
            <Button type="submit" disabled={!ok}>
              Inject {Number.isFinite(Number(values.volumeM3)) ? formatVolume(Number(values.volumeM3)) : ''}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

const TOOL_HINT: Partial<Record<Tool, string>> = {
  water: 'Click the map to pour water there.',
  dig: 'Click the map to dig a pit there.',
  section: 'Click points on the map to draw a cross-section line, then open “Section” and press Cut.',
  chamber: 'Click the map where the magma chamber should be: it goes below that point, at the depth you choose next.',
};

/** Volcano actions and map tools, bottom-left over the 3D view. */
export function ActionBar({ world }: { world: WorldInfo }) {
  const { id, vs } = useVolcano(world);
  const tool = useStore((s) => s.tool);
  const set = useStore((s) => s.set);
  const replay = useStore((s) => s.clock?.replay ?? false);
  const injectFor = useStore((s) => s.injectFor);
  const erupting = (vs?.chamber.eruptionRate ?? 0) > 0 || vs?.alert.level === 'ERUPTING';
  const pick = (t: Tool) => set({ tool: tool === t ? 'orbit' : t });
  return (
    <>
      <div className={`${OVERLAY} flex max-w-full shrink-0 flex-wrap gap-1.5 p-1.5`} role="toolbar" aria-label="Volcano actions">
        {!id ? null : erupting ? (
          <Tip content="End the eruption now" side="top">
            <Button variant="destructive" size="sm" disabled={replay} onClick={() => command({ kind: 'stopEruption', volcanoId: id })}>
              <Square /> <span className="short:hidden">Stop eruption</span>
            </Button>
          </Tip>
        ) : (
          <Tip content="Open a vent and start an eruption now, whatever the pressure" side="top">
            <Button size="sm" disabled={replay} onClick={() => command({ kind: 'startEruption', volcanoId: id })}>
              <Triangle /> <span className="short:hidden">Start eruption</span>
            </Button>
          </Tip>
        )}
        {id && (
          <Tip content="Add a batch of magma with chosen temperature and composition" side="top">
            <Button variant="secondary" size="sm" disabled={replay} onClick={() => set({ injectFor: id })}>
              <Plus /> <span className="short:hidden">Add magma…</span>
            </Button>
          </Tip>
        )}
        <DropdownMenu>
          <Tip content="More actions and map tools" side="top">
            <DropdownMenuTrigger render={<Button variant="secondary" size="sm" />}>
              <Wrench /> <span className="short:hidden">Tools</span> <ChevronUp data-icon="inline-end" />
            </DropdownMenuTrigger>
          </Tip>
          <DropdownMenuContent side="top" className="w-auto min-w-60">
            <DropdownMenuCheckboxItem checked={tool === 'chamber'} disabled={replay} onClick={() => pick('chamber')}>
              <Flame /> Place magma chamber
            </DropdownMenuCheckboxItem>
            {id && (
              <DropdownMenuItem disabled={replay} onClick={() => command({ kind: 'forceDike', volcanoId: id })}>
                <ArrowUpFromDot /> Push magma up (dike)
              </DropdownMenuItem>
            )}
            <DropdownMenuSeparator />
            <DropdownMenuCheckboxItem checked={tool === 'water'} disabled={replay} onClick={() => pick('water')}>
              <Droplets /> Pour water
            </DropdownMenuCheckboxItem>
            <DropdownMenuCheckboxItem checked={tool === 'dig'} disabled={replay} onClick={() => pick('dig')}>
              <Pickaxe /> Dig a pit
            </DropdownMenuCheckboxItem>
            <DropdownMenuCheckboxItem checked={tool === 'section'} onClick={() => pick('section')}>
              <Ruler /> Draw a cross-section line
            </DropdownMenuCheckboxItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      {id && <InjectDialog world={world} volcanoId={injectFor} open={injectFor !== null} onOpenChange={(o) => !o && set({ injectFor: null })} />}
    </>
  );
}

/** What the active map tool does, with its settings (above the action bar). */
export function ToolHint() {
  const tool = useStore((s) => s.tool);
  const waterVolume = useStore((s) => s.waterVolume);
  const digRadius = useStore((s) => s.digRadius);
  const digDepth = useStore((s) => s.digDepth);
  const set = useStore((s) => s.set);
  if (tool === 'orbit') return null;
  return (
      <div className={`${OVERLAY} flex max-w-full shrink-0 flex-wrap items-center gap-2 px-3 py-2 text-sm`} role="status">
        <span>{TOOL_HINT[tool]}</span>
        {tool === 'water' && (
          <Label className="font-normal">
            Volume <Input className="h-7 w-24" type="number" value={waterVolume} min={100} step={1000} onChange={(e) => set({ waterVolume: Number(e.target.value) })} /> m³
          </Label>
        )}
        {tool === 'dig' && (
          <>
            <Label className="font-normal">
              Radius <Input className="h-7 w-16" type="number" value={digRadius} min={5} step={5} onChange={(e) => set({ digRadius: Number(e.target.value) })} /> m
            </Label>
            <Label className="font-normal">
              Depth <Input className="h-7 w-16" type="number" value={digDepth} min={1} step={5} onChange={(e) => set({ digDepth: Number(e.target.value) })} /> m
            </Label>
          </>
        )}
        <Button size="sm" variant="outline" onClick={() => set({ tool: 'orbit' })}>
          Done <kbd className="text-xs text-muted-foreground">Esc</kbd>
        </Button>
      </div>
  );
}

function formatRate(r: number): string {
  return r >= 10 ? r.toFixed(0) : r.toFixed(1);
}
