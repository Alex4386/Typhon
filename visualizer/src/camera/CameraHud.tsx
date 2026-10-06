import { useEffect, useRef, useState } from 'react';
import { Bookmark, CircleHelp, Crosshair, Grid2x2, MoveUp, Plus, Settings, SplitSquareVertical, Trash2, X } from 'lucide-react';
import { SimpleSelect, SliderRow, SwitchRow } from '@/components/fields';
import { Tip } from '@/components/tip';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { Input } from '@/components/ui/input';
import { Kbd } from '@/components/ui/kbd';
import { Label } from '@/components/ui/label';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Table, TableBody, TableCell, TableRow } from '@/components/ui/table';
import { cn } from '@/lib/utils';
import { OVERLAY } from '../panels/Overlay';
import { Field } from '../protocol/fields';
import type { WorldInfo } from '../protocol/messages';
import { useStore } from '../store/store';
import { HYPSO, ramp, type RGB } from '../util/color';
import { sampleColumn, worldExtent } from '../util/world';
import { saveBookmarks, useCamera } from './cameraStore';
import { CAMERA_MODES, compassPoint, decodePose, FOLLOW_TARGETS, type CameraMode, type FollowTarget } from './math';
import { builtinBookmarks, type SceneInfo } from './targets';

const MODE_LABEL: Record<CameraMode, { label: string; key: string; title: string }> = {
  orbit: { label: 'Orbit', key: '1', title: 'Orbit around a point: drag to rotate, right-drag to pan, wheel zooms to the cursor' },
  fly: { label: 'Fly', key: '2', title: 'Free flight: WASD + Q/E, mouse look (click to capture), wheel = speed' },
  walk: { label: 'Walk', key: '3', title: 'Walk on the ground at eye height: WASD, Space jumps' },
  follow: { label: 'Follow', key: '4', title: 'Track a target with damped motion; orbit around it as usual' },
  tour: { label: 'Tour', key: '5', title: 'Slow automatic fly-around; any input stops it' },
};

const FOLLOW_LABEL: Record<FollowTarget, string> = {
  vent: 'vent',
  lavaFront: 'lava front',
  plumeTop: 'plume top',
  volcano: 'volcano',
};

function sceneInfo(world: WorldInfo): SceneInfo {
  const s = useStore.getState();
  const r = useCamera.getState().readout;
  return {
    world,
    state: s.state,
    volcanoId: s.selectedVolcano,
    vExag: s.verticalExaggeration,
    dExag: s.deformationExaggeration,
    sectionPolyline: s.sectionPolyline,
    fov: r?.fov ?? 38,
    aspect: r?.aspect ?? 1.6,
  };
}

/** Camera toolbar: modes, follow target, framing, bookmarks, settings and help (bottom right). */
export function CameraBar({ world }: { world: WorldInfo }) {
  const mode = useCamera((c) => c.mode);
  const followTarget = useCamera((c) => c.followTarget);
  const speed = useCamera((c) => c.speedMultiplier);
  const clearance = useCamera((c) => c.clearance);
  const underground = useCamera((c) => c.allowUnderground);
  const bookmarks = useCamera((c) => c.bookmarks);
  const locked = useCamera((c) => c.pointerLocked);
  const set = useCamera((c) => c.set);
  const req = useCamera((c) => c.requestCamera);
  const hasPlume = useStore((s) => Object.values(s.state?.volcanoes ?? {}).some((v) => !!v.plume && v.plume.topZ > 0));
  const hasSection = useStore((s) => s.sectionPolyline.length >= 2);
  const hasSelection = useStore((s) => s.selection !== null);
  const [saving, setSaving] = useState(false);
  const [name, setName] = useState('');
  const full = useStore((s) => s.showCameraTools);
  const modes = full ? CAMERA_MODES : CAMERA_MODES.filter((m) => m === 'orbit' || m === 'fly' || m === 'walk' || m === mode);

  const builtins = builtinBookmarks(sceneInfo(world));
  const savePose = (label: string) => {
    const pose = decodePose(new URL(window.location.href).searchParams.get('cam'));
    if (!label || !pose) return;
    const list = [...bookmarks.filter((b) => b.name !== label), { name: label, pose }];
    set({ bookmarks: list });
    saveBookmarks(world.name, list);
  };
  const removeBookmark = (label: string) => {
    const list = bookmarks.filter((b) => b.name !== label);
    set({ bookmarks: list });
    saveBookmarks(world.name, list);
  };

  return (
    <div className={cn(OVERLAY, 'flex max-w-full shrink-0 flex-col gap-1 p-1.5')}>
      <div className="flex flex-wrap items-center justify-end gap-1" role="toolbar" aria-label="Camera">
        {modes.map((m) => (
          <Tip key={m} content={`${MODE_LABEL[m].title} [${MODE_LABEL[m].key}]`} side="top">
            <Button size="xs" variant={mode === m ? 'default' : 'ghost'} aria-pressed={mode === m} onClick={() => req({ kind: 'mode', mode: m })}>
              {MODE_LABEL[m].label}
            </Button>
          </Tip>
        ))}
        {mode === 'follow' && (
          <SimpleSelect label="Follow target" value={followTarget} onChange={(v) => set({ followTarget: v })} options={FOLLOW_TARGETS.map((t) => [t, FOLLOW_LABEL[t]] as const)} />
        )}
        <span className="mx-0.5 h-5 w-px bg-border" />
        <Tip content={hasSelection ? 'Frame the selection [F] (Shift+F: the volcano)' : 'Frame the selected volcano [F]'} side="top">
          <Button size="xs" variant="ghost" onClick={() => req(hasSelection ? { kind: 'frameSelection' } : { kind: 'frame', what: 'volcano' })}>
            <Crosshair /> {hasSelection ? 'selection' : 'volcano'}
          </Button>
        </Tip>
        {full && (
          <>
            <Tip content="Fit the eruption column [P]" side="top">
              <Button size="xs" variant="ghost" disabled={!hasPlume} onClick={() => req({ kind: 'frame', what: 'plume' })}>
                <MoveUp /> plume
              </Button>
            </Tip>
            <Tip content="Whole world [O]" side="top">
              <Button size="xs" variant="ghost" onClick={() => req({ kind: 'frame', what: 'overview' })}>
                <Grid2x2 /> overview
              </Button>
            </Tip>
            <Tip content="Look across the cross-section line" side="top">
              <Button size="xs" variant="ghost" disabled={!hasSection} onClick={() => req({ kind: 'frame', what: 'section' })}>
                <SplitSquareVertical /> section
              </Button>
            </Tip>
            <span className="mx-0.5 h-5 w-px bg-border" />
            <DropdownMenu>
              <Tip content="Camera bookmarks" side="top">
                <DropdownMenuTrigger render={<Button size="xs" variant="ghost" />}>
                  <Bookmark /> views
                </DropdownMenuTrigger>
              </Tip>
              <DropdownMenuContent side="top" align="end" className="w-auto min-w-56">
                <DropdownMenuGroup>
                  <DropdownMenuLabel>Built in</DropdownMenuLabel>
                  {builtins.map((b) => (
                    <DropdownMenuItem key={`b-${b.name}`} onClick={() => req({ kind: 'pose', pose: b.pose })}>
                      {b.name}
                    </DropdownMenuItem>
                  ))}
                </DropdownMenuGroup>
                {bookmarks.length > 0 && (
                  <DropdownMenuGroup>
                    <DropdownMenuLabel>Saved</DropdownMenuLabel>
                    {bookmarks.map((b) => (
                      <DropdownMenuItem key={`u-${b.name}`} onClick={() => req({ kind: 'pose', pose: b.pose })}>
                        <span className="flex-1">{b.name}</span>
                        <Button
                          size="icon-xs"
                          variant="ghost"
                          aria-label={`Delete ${b.name}`}
                          onClick={(e) => {
                            e.stopPropagation();
                            removeBookmark(b.name);
                          }}
                        >
                          <Trash2 />
                        </Button>
                      </DropdownMenuItem>
                    ))}
                  </DropdownMenuGroup>
                )}
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  onClick={() => {
                    setName(`View ${bookmarks.length + 1}`);
                    setSaving(true);
                  }}
                >
                  <Plus /> Save current view…
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
            <Popover>
              <Tip content="Camera settings: fly speed, ground clearance, underground" side="top">
                <PopoverTrigger render={<Button size="xs" variant="ghost" />}>
                  <Settings /> settings
                </PopoverTrigger>
              </Tip>
              <PopoverContent side="top" align="end" className="flex w-72 flex-col gap-3">
                <SliderRow
                  label="Fly speed"
                  help="Fly speed multiplier (wheel or [ ] while flying)"
                  value={Math.log2(speed)}
                  display={`×${speed.toFixed(2)}`}
                  min={-3}
                  max={3}
                  step={0.1}
                  onChange={(v) => set({ speedMultiplier: 2 ** v })}
                />
                <div className="flex items-center justify-between gap-2">
                  <Label htmlFor="cam-clearance" className="font-normal">
                    Ground clearance (m)
                  </Label>
                  <Input id="cam-clearance" type="number" className="h-7 w-20" min={0} max={500} step={1} value={clearance} onChange={(e) => set({ clearance: Math.max(0, Number(e.target.value) || 0) })} />
                </div>
                <SwitchRow id="cam-underground" label="Fly underground [G]" help="Allow flying below the surface to look at the chamber and subsurface" checked={underground} onChange={(v) => set({ allowUnderground: v })} />
              </PopoverContent>
            </Popover>
          </>
        )}
        <Tip content="Keyboard and mouse controls [?]" side="top">
          <Button size="icon-xs" variant="ghost" aria-label="Camera controls help" onClick={() => set({ helpOpen: true })}>
            <CircleHelp />
          </Button>
        </Tip>
        <Tip content={full ? 'Hide the extra camera tools' : 'More camera tools: follow, tour, plume/overview framing, saved views, settings'} side="top">
          <Button size="xs" variant="ghost" aria-expanded={full} onClick={() => useStore.getState().set({ showCameraTools: !full })}>
            {full ? 'Less' : 'More…'}
          </Button>
        </Tip>
      </div>
      {(mode === 'fly' || mode === 'walk') && (
        <div className="pointer-events-none truncate px-1 text-[11px] text-muted-foreground">
          {locked ? 'Esc releases the mouse · ' : 'Click the view to capture the mouse (or drag to look) · '}
          WASD move{mode === 'fly' ? ' · Q/E down/up · Shift fast · Ctrl/Alt slow · wheel speed' : ' · Space jump · Shift run'}
        </div>
      )}
      <Dialog open={saving} onOpenChange={setSaving}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>Save this view</DialogTitle>
            <DialogDescription>It appears under “views” for this world.</DialogDescription>
          </DialogHeader>
          <form
            className="flex gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              savePose(name.trim());
              setSaving(false);
            }}
          >
            <Input autoFocus aria-label="View name" value={name} onChange={(e) => setName(e.target.value)} />
            <Button type="submit" disabled={!name.trim()}>
              Save
            </Button>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}

/** Compass rose and position readout (bottom centre). */
export function CameraReadoutPanel() {
  const r = useCamera((c) => c.readout);
  const mode = useCamera((c) => c.mode);
  if (!r) return null;
  const deg = (r.heading * 180) / Math.PI;
  return (
    <Tip content="Camera position (world metres)" side="top">
      <div className={cn(OVERLAY, 'flex shrink-0 items-center gap-2 py-1.5 pr-3 pl-1.5 text-[11px] tabular-nums')}>
        <button
          type="button"
          className="relative size-11 shrink-0 rounded-full border bg-[radial-gradient(circle,#24303d_0%,#161b22_70%)]"
          onClick={() => useCamera.getState().requestCamera({ kind: 'frame', what: 'volcano' })}
          aria-label="Heading (click: frame volcano)"
        >
          <svg viewBox="-22 -22 44 44" className="absolute inset-0" style={{ transform: `rotate(${-deg}deg)` }} aria-hidden>
            <polygon points="0,-13 -5,0 5,0" className="fill-primary" />
            <polygon points="0,13 -5,0 5,0" fill="#6b7785" />
            <text x="0" y="-15" textAnchor="middle" fontSize="8" fontWeight="700" className="fill-foreground">
              N
            </text>
          </svg>
        </button>
        <div className="leading-snug">
          <div>
            <b>{compassPoint(r.heading)}</b> {deg.toFixed(0)}° · pitch {((r.pitch * 180) / Math.PI).toFixed(0)}° · <span className="text-muted-foreground">{mode}</span>
          </div>
          <div>
            E {fmtM(r.x)} · N {fmtM(r.y)}
          </div>
          <div>
            alt {fmtM(r.altitude)} · AGL {fmtM(r.aboveGround)}
            {r.speed > 0 ? ` · ${fmtSpeed(r.speed)}` : ''}
          </div>
        </div>
      </div>
    </Tip>
  );
}

function fmtM(v: number): string {
  const a = Math.abs(v);
  return a >= 10000 ? `${(v / 1000).toFixed(1)} km` : `${v.toFixed(0)} m`;
}

function fmtSpeed(v: number): string {
  return v >= 1000 ? `${(v / 1000).toFixed(1)} km/s` : `${v.toFixed(v < 10 ? 1 : 0)} m/s`;
}

const MAP_PX = 168;

/** Minimap of the world elevation with vents, the section line and the camera frustum; click to fly there. */
export function Minimap({ world }: { world: WorldInfo }) {
  const base = useRef<HTMLCanvasElement>(null);
  const overlay = useRef<HTMLCanvasElement>(null);
  const tileRevision = useStore((s) => s.tileRevision);
  const readout = useCamera((c) => c.readout);
  const section = useStore((s) => s.sectionPolyline);
  const open = useStore((s) => s.showMinimap);
  const setOpen = (v: boolean) => useStore.getState().set({ showMinimap: v });
  const lastDraw = useRef(0);
  const pending = useRef<number | null>(null);
  const ext = worldExtent(world);
  const w = ext.maxX - ext.minX;
  const h = ext.maxY - ext.minY;
  const scale = MAP_PX / Math.max(w, h);
  const toPx = (x: number, y: number): [number, number] => [(x - ext.minX) * scale, MAP_PX - (y - ext.minY) * scale];

  // base layer: elevation shading, redrawn at most every 2 s as tiles stream in
  useEffect(() => {
    if (!open) return;
    const draw = () => {
      pending.current = null;
      lastDraw.current = performance.now();
      const c = base.current;
      if (!c) return;
      const g = c.getContext('2d');
      if (!g) return;
      const img = g.createImageData(MAP_PX, MAP_PX);
      const [lo, hi] = world.elevationRange;
      const rgb: RGB = [0, 0, 0];
      for (let py = 0; py < MAP_PX; py++) {
        for (let px = 0; px < MAP_PX; px++) {
          const x = ext.minX + (px + 0.5) / scale;
          const y = ext.minY + (MAP_PX - py - 0.5) / scale;
          const i = Math.floor((x - world.origin[0]) / world.cellSize);
          const j = Math.floor((y - world.origin[1]) / world.cellSize);
          const e = sampleColumn(world, Field.SurfaceElevation, i, j, Number.NaN);
          const k = (py * MAP_PX + px) * 4;
          if (!Number.isFinite(e)) {
            img.data[k + 3] = 0;
            continue;
          }
          const water = sampleColumn(world, Field.WaterDepth, i, j, 0);
          const lava = sampleColumn(world, Field.LavaDepth, i, j, 0);
          if (lava > 0.1) rgb.splice(0, 3, 1, 0.43, 0.12);
          else if (water > 0.3 || e < world.seaLevel) rgb.splice(0, 3, 0.16, 0.35, 0.55);
          else ramp(HYPSO, (e - lo) / Math.max(1, hi - lo), rgb);
          // light hillshade from the west-east gradient
          const e2 = sampleColumn(world, Field.SurfaceElevation, i + 1, j, e);
          const shade = Math.max(0.55, Math.min(1.25, 1 - ((e2 - e) / world.cellSize) * 0.8));
          img.data[k] = Math.min(255, rgb[0] * 255 * shade);
          img.data[k + 1] = Math.min(255, rgb[1] * 255 * shade);
          img.data[k + 2] = Math.min(255, rgb[2] * 255 * shade);
          img.data[k + 3] = 255;
        }
      }
      g.putImageData(img, 0, 0);
    };
    const since = performance.now() - lastDraw.current;
    if (since > 2000) draw();
    else if (pending.current == null) pending.current = window.setTimeout(draw, 2000 - since);
    return () => {
      if (pending.current != null) {
        window.clearTimeout(pending.current);
        pending.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tileRevision, open, world]);

  // overlay: vents, section line, camera and frustum
  useEffect(() => {
    if (!open) return;
    const c = overlay.current;
    const g = c?.getContext('2d');
    if (!c || !g) return;
    g.clearRect(0, 0, MAP_PX, MAP_PX);
    if (section.length >= 2) {
      g.strokeStyle = '#ffd36e';
      g.lineWidth = 1.5;
      g.beginPath();
      section.forEach(([x, y], k) => {
        const [px, py] = toPx(x, y);
        if (k === 0) g.moveTo(px, py);
        else g.lineTo(px, py);
      });
      g.stroke();
    }
    for (const v of world.volcanoes) {
      for (const vent of v.vents) {
        const [px, py] = toPx(vent.at[0], vent.at[1]);
        g.fillStyle = '#ff4d2e';
        g.beginPath();
        g.arc(px, py, 2.5, 0, Math.PI * 2);
        g.fill();
      }
    }
    if (readout) {
      const [px, py] = toPx(readout.x, readout.y);
      const hfov = Math.atan(Math.tan(((readout.fov / 2) * Math.PI) / 180) * readout.aspect);
      const len = 26;
      // screen y is flipped (north up): bearing θ → (sin θ, −cos θ)
      const a1 = readout.heading - hfov;
      const a2 = readout.heading + hfov;
      g.fillStyle = 'rgba(255, 255, 255, 0.22)';
      g.strokeStyle = 'rgba(255, 255, 255, 0.85)';
      g.lineWidth = 1;
      g.beginPath();
      g.moveTo(px, py);
      g.lineTo(px + Math.sin(a1) * len, py - Math.cos(a1) * len);
      g.lineTo(px + Math.sin(a2) * len, py - Math.cos(a2) * len);
      g.closePath();
      g.fill();
      g.stroke();
      g.fillStyle = '#4fc3f7';
      g.beginPath();
      g.arc(px, py, 3, 0, Math.PI * 2);
      g.fill();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [readout, section, open, world]);

  if (!open) return null;
  return (
    <div className={cn(OVERLAY, 'relative shrink-0 p-1 short:hidden')} title="Click to fly there">
      <div className="relative" style={{ width: MAP_PX, height: MAP_PX }}>
        <canvas ref={base} width={MAP_PX} height={MAP_PX} className="absolute inset-0 rounded-md" />
        <canvas
          className="absolute inset-0 cursor-crosshair rounded-md"
          ref={overlay}
          width={MAP_PX}
          height={MAP_PX}
          onClick={(e) => {
            const rect = (e.target as HTMLCanvasElement).getBoundingClientRect();
            const x = ext.minX + (e.clientX - rect.left) / scale;
            const y = ext.minY + (MAP_PX - (e.clientY - rect.top)) / scale;
            useCamera.getState().requestCamera({ kind: 'flyTo', at: [x, y] });
          }}
        />
      </div>
      <Button size="icon-xs" variant="secondary" className="absolute top-2 right-2 opacity-80" aria-label="Hide minimap" onClick={() => setOpen(false)}>
        <X />
      </Button>
    </div>
  );
}

const HELP: [string, string][] = [
  ['1 – 5', 'Orbit · Fly · Walk · Follow · Tour'],
  ['Click', 'Select a vent, dike, spring, quake, station or the ground'],
  ['F / Shift+F', 'Frame the selection / the volcano'],
  ['P', 'Fit the eruption column (plume)'],
  ['O', 'Overview of the whole world'],
  ['Double-click', 'Focus on a point of the terrain'],
  ['Minimap click', 'Fly to that place'],
  ['Orbit: drag', 'Rotate · right-drag / two fingers pan · wheel / pinch zooms to the cursor'],
  ['W A S D', 'Move (fly / walk; gamepad left stick)'],
  ['Q / E, Space', 'Down / up (fly) · Space jumps (walk)'],
  ['Mouse', 'Click the view to capture the mouse and look around; Esc releases (drag also looks)'],
  ['Arrows', 'Look around without a mouse'],
  ['Shift / Ctrl, Alt', 'Faster / slower'],
  ['Wheel, [ ]', 'Fly speed'],
  ['G', 'Toggle flying underground'],
  ['Gamepad', 'Left stick move · right stick look · triggers down/up · A boost'],
  ['?', 'This help · Esc closes'],
];

export function CameraHelp() {
  const open = useCamera((c) => c.helpOpen);
  return (
    <Dialog open={open} onOpenChange={(o) => useCamera.getState().set({ helpOpen: o })}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Camera controls</DialogTitle>
          <DialogDescription>The view is kept in the URL (?cam=…), so copying the address shares exactly this view.</DialogDescription>
        </DialogHeader>
        <Table>
          <TableBody>
            {HELP.map(([k, v]) => (
              <TableRow key={k}>
                <TableCell className="w-36 align-top">
                  <Kbd>{k}</Kbd>
                </TableCell>
                <TableCell className="whitespace-normal">{v}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </DialogContent>
    </Dialog>
  );
}
