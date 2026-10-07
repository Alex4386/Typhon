import { currentTier } from '../util/device';
import { create } from 'zustand';
import { Field, type FieldId } from '../protocol/fields';
import { DETAIL_TOLERANCE_M, detailMismatch } from '../scene/detail';
import type { SectionFrame, TileFrame } from '../protocol/frames';
import type {
  CatalogMessage,
  ClockMessage,
  EntitiesMessage,
  InspectionMessage,
  ReplayInfoMessage,
  SchemaMessage,
  ServerInfo,
  SessionInfo,
  SimEvent,
  StateMessage,
  UnitInfo,
  WelcomeMessage,
  WorldInfo,
  XY,
} from '../protocol/messages';
import { isBool, isClarity, isFlags, isFraction, loadPref, PREF_KEYS, savePref } from './prefs';
import { loadQuakeFilter, saveQuakeFilter, type QuakeFilter } from './quakeFilter';
import { isImportant, mergeByTime } from '../panels/events';
import { applyEntities, pruneEntities, type EntityMap, type EntityView, type Selection } from './entities';

/** Side drawer pages; only one is open at a time (progressive disclosure). */
export type DrawerTab = 'sims' | 'build' | 'entities' | 'monitor' | 'events' | 'section' | 'view' | 'tune';

export type ToastTone = 'info' | 'warn' | 'alert';
export interface ToastAction {
  label: string;
  onClick: () => void;
}

/** A notification shown over the 3D view (HUD toasts); the same text replaces an older one. */
export interface HudToast {
  id: string;
  text: string;
  tone: ToastTone;
  action?: ToastAction;
  /** How long it stays (ms). */
  duration: number;
  /** Bumped when the same text is raised again, so its timer restarts. */
  serial: number;
}

/** What the pointer is over in the 3D view (label shown next to the cursor). */
export interface Hover {
  label: string;
  detail?: string;
  x: number;
  y: number;
}

export type Tool = 'orbit' | 'section' | 'water' | 'dig' | 'chamber';
export type SurfaceColorMode =
  | 'natural'
  | 'surfaceTemperature'
  | 'waterTable'
  | 'topUnit'
  | 'uplift'
  | 'ash'
  | 'steam';
export type ConnectionStatus = 'connecting' | 'open' | 'closed';

const EVENT_CAP = 4000;
const KEY_EVENT_CAP = 5000;

const HISTORY_CAP = 6000;

/** One sample of 0D state per volcano, kept for charts. */
export interface HistorySample {
  time: number;
  overpressure: number;
  strength: number;
  eruptionRate: number;
  rsam: number;
  vt: number;
  lp: number;
  temperature: number;
  silica: number;
  water: number;
  crystals: number;
  alertIndex: number;
  stations: Record<string, { east: number; north: number; up: number }>;
}

export const ALERT_LEVELS = ['EXTINCT', 'DORMANT', 'MINOR_ACTIVITY', 'MAJOR_ACTIVITY', 'ERUPTION_IMMINENT', 'ERUPTING'] as const;

/**
 * Tile data lives outside React state (it is large and mutated in place); `tileRevision`
 * signals consumers that something changed.
 */
export const tileStore = new Map<FieldId, Map<string, TileFrame>>();

export function tileKey(tx: number, ty: number): string {
  return `${tx},${ty}`;
}

export function getTile(field: FieldId, tx: number, ty: number): TileFrame | undefined {
  return tileStore.get(field)?.get(tileKey(tx, ty));
}

/**
 * Tiles of the other pyramid levels (§5.5) by level: coarse context (> 0) and crater detail (< 0).
 * Only the surface elevation is kept: the rest of what those levels carry (lava, ash, … means) is
 * already drawn from the core's own tiles.
 */
export const lodStore = new Map<number, Map<string, TileFrame>>();

/** Revision key of a pyramid tile. */
export function lodKey(level: number, tx: number, ty: number): string {
  return `${level}:${tx},${ty}`;
}

/** Detail tiles ignored because they did not match the core's columns (diagnostics). */
export const detailRejected = { count: 0, lastMismatchM: Number.NaN };

export function getLodTile(level: number, tx: number, ty: number): TileFrame | undefined {
  return lodStore.get(level)?.get(tileKey(tx, ty));
}

interface Store {
  status: ConnectionStatus;
  serverUrl: string;
  welcome: WelcomeMessage | null;
  /** Every session loaded on the server (pushed by the server). */
  sessions: SessionInfo[];
  serverInfo: ServerInfo | null;
  catalog: CatalogMessage | null;
  /** Session this client is attached to. */
  sessionId: string | null;
  /** Open drawer page, or null when the drawer is closed. */
  drawer: DrawerTab | null;
  drawerWidth: number;
  /** Things in the world with a lifetime (vents, dikes, hot springs, …), by id (§4.8). */
  entities: EntityMap;
  /** What the user selected in the view or the Entities panel. */
  selection: Selection | null;
  /** Latest column inspection for the selection (§3.7). */
  inspection: InspectionMessage | null;
  inspectPending: number | null;
  hover: Hover | null;
  /** Entity under the pointer (3D view or Entities panel), highlighted in both. */
  hoverId: string | null;
  /** Show the minimap (off by default to keep the view clean). */
  showMinimap: boolean;
  toasts: HudToast[];
  /** A change the server wants confirmed (it resets something), with what to do on each answer. */
  configPrompt: { result: import('../protocol/messages').ConfigResult; confirm: () => void; cancel: () => void } | null;
  /** Where the user clicked with the chamber tool: the placement dialog is open for it. */
  /** Build mode: the chamber being placed or edited (its ghost is drawn; fields and gizmos stay in sync). */
  buildDraft: import('../panels/builder').BuildDraft | null;
  /** Build mode: the pathway being drawn between two chambers. */
  connectDraft: import('../panels/builder').ConnectDraft | null;
  /** Build mode's undo/redo history (definition snapshots replayed through the configuration API). */
  buildHistory: import('../panels/builder').BuildHistory;
  dismissToast: (id: string) => void;
  /** Show the full camera toolbar (follow, tour, bookmarks, framing). */
  showCameraTools: boolean;
  guideOpen: boolean;
  /** Command palette (Ctrl+K). */
  paletteOpen: boolean;
  /** Tunable parameters and command fields of the attached session. */
  schema: SchemaMessage | null;
  world: WorldInfo | null;
  clock: (ClockMessage & { receivedAt: number }) | null;
  state: StateMessage | null;
  history: Record<string, HistorySample[]>;
  events: SimEvent[];
  /**
   * Key-event candidates ({@link isImportant}), kept apart from `events`: quakes and bombs arrive by
   * the thousand and would push the eruption start out of the capped event list.
   */
  keyEvents: SimEvent[];
  droppedEvents: number;
  /** Revision per tile key, bumped when any field of that tile changes. */
  tileRevision: Record<string, number>;
  /** Revision per pyramid tile ({@link lodKey}), bumped when its elevation changes. */
  lodRevision: Record<string, number>;
  /** Bumped whenever any coarse context tile changes (the far field rebuilds from them). */
  contextRevision: number;
  section: SectionFrame | null;
  sectionPending: number | null;
  /** Surface-datum companion of `section`: the top metres along the same line. */
  sectionShallow: SectionFrame | null;
  sectionShallowPending: number | null;
  replayInfo: ReplayInfoMessage | null;
  units: Record<number, UnitInfo>;
  errors: string[];
  renderer: string;

  // UI
  tool: Tool;
  colorMode: SurfaceColorMode;
  selectedVolcano: string | null;
  verticalExaggeration: number;
  deformationExaggeration: number;
  sectionPolyline: XY[];
  showHypocentres: boolean;
  showChambers: boolean;
  showAtmosphere: boolean;
  showFeatures: boolean;
  /** Draw the groundwater table as a translucent sheet (always on while the camera is underground). */
  showWaterTable: boolean;
  /** Make the ground translucent while the camera is below it. */
  xray: boolean;
  /** Outline the simulated ground (the rest is generated landscape, simulated on demand; persisted). */
  showSimulatedArea: boolean;
  /** Set by the scene: the camera is below the displayed ground. */
  underground: boolean;
  waterVolume: number;
  digRadius: number;
  digDepth: number;
  /** Rendering quality: shadows, smoothing radius, particle counts. */
  quality: Quality;
  /** Smooth block-quantised elevations for display (terrace removal). */
  smoothTerrain: boolean;
  toolboxOpen: boolean;
  /** Which earthquakes are drawn and listed (persisted). */
  quakeFilter: QuakeFilter;
  /** Entity categories hidden in the 3D view (persisted). */
  hiddenCategories: Record<string, boolean>;
  /** Volcano the "Add magma" dialog is open for (null: closed). */
  injectFor: string | null;
  /** Frame statistics overlay (persisted). */
  showPerf: boolean;
  /**
   * How far you see into the water (m): the light attenuation length of the display water. Open ocean is
   * ~20–30 m; coastal or eruption-clouded water 5–10 m. Visual only.
   */
  waterClarity: number;
  /** How much the display water lets you see what lies under it, 0 (physical) … 1 (glass). Visual only. */
  waterSeeThrough: number;
  /** Lower the resolution (and then quality) when frames get slow (persisted). */
  autoQuality: boolean;

  set: (partial: Partial<Store>) => void;
  setStatus: (s: ConnectionStatus) => void;
  applyTiles: (frames: TileFrame[]) => void;
  /** Forgets the tiles of pyramid levels no longer subscribed (the core's own ground shows again). */
  dropLodLevels: (levels: number[]) => void;
  applyState: (s: StateMessage) => void;
  addEvents: (events: SimEvent[], dropped: number) => void;
  clearForReplay: (time: number) => void;
  pushError: (msg: string) => void;
  /** Forgets everything about the attached session (switching worlds). */
  resetSession: () => void;
  openDrawer: (tab: DrawerTab | null) => void;
  toast: (text: string, tone?: ToastTone, action?: ToastAction) => void;
  /** Applies an entity delta; returns the entities that appeared. */
  applyEntities: (m: EntitiesMessage) => EntityView[];
  select: (sel: Selection | null) => void;
}

export const useStore = create<Store>((set, get) => ({
  status: 'connecting',
  serverUrl: defaultServerUrl(),
  welcome: null,
  sessions: [],
  serverInfo: null,
  catalog: null,
  sessionId: null,
  drawer: null,
  drawerWidth: initialDrawerWidth(),
  entities: {},
  selection: null,
  inspection: null,
  inspectPending: null,
  hover: null,
  hoverId: null,
  showMinimap: false,
  toasts: [],
  configPrompt: null,
  buildDraft: null,
  connectDraft: null,
  buildHistory: { undo: [], redo: [] },
  showCameraTools: false,
  guideOpen: !guideSeen(),
  paletteOpen: false,
  schema: null,
  world: null,
  clock: null,
  state: null,
  history: {},
  events: [],
  keyEvents: [],
  droppedEvents: 0,
  tileRevision: {},
  lodRevision: {},
  contextRevision: 0,
  section: null,
  sectionPending: null,
  sectionShallow: null,
  sectionShallowPending: null,
  replayInfo: null,
  units: {},
  errors: [],
  renderer: '…',

  tool: 'orbit',
  colorMode: 'natural',
  selectedVolcano: null,
  verticalExaggeration: 1.5,
  deformationExaggeration: 1,
  sectionPolyline: [],
  showHypocentres: true,
  showChambers: true,
  showAtmosphere: true,
  showFeatures: true,
  showWaterTable: false,
  xray: true,
  showSimulatedArea: loadPref(PREF_KEYS.showSimulatedArea, true, isBool),
  underground: false,
  waterVolume: 50000,
  digRadius: 40,
  digDepth: 30,
  quality: initialQuality(),
  smoothTerrain: true,
  toolboxOpen: false,
  quakeFilter: loadQuakeFilter(),
  injectFor: null,
  hiddenCategories: loadPref(PREF_KEYS.hiddenCategories, {}, isFlags),
  showPerf: loadPref(PREF_KEYS.showPerf, false, isBool),
  waterClarity: loadPref(PREF_KEYS.waterClarity, 25, isClarity),
  waterSeeThrough: loadPref(PREF_KEYS.waterSeeThrough, 0.5, isFraction),
  autoQuality: loadPref(PREF_KEYS.autoQuality, true, isBool),

  set: (partial) => set(partial),
  setStatus: (status) => set({ status }),

  applyTiles: (frames) => {
    const rev = { ...get().tileRevision };
    let changed = false;
    let lodRev: Record<string, number> | null = null;
    let context = 0;
    for (const f of frames) {
      if (f.level !== 0) {
        if (f.field !== Field.SurfaceElevation) continue;
        // crater detail must refine the core's columns (§5.5); a stale tile would draw the wrong ground
        const w = get().world;
        if (f.level < 0 && w) {
          const r = Math.max(1, Math.round(2 ** -f.level));
          const off = detailMismatch(f.values, w.tileSize, r, f.tileX, f.tileY, (i, j) => {
            const tile = getTile(Field.SurfaceElevation, Math.floor(i / w.tileSize), Math.floor(j / w.tileSize));
            return tile ? tile.values[(j - Math.floor(j / w.tileSize) * w.tileSize) * w.tileSize + (i - Math.floor(i / w.tileSize) * w.tileSize)] : undefined;
          });
          if (!(off <= DETAIL_TOLERANCE_M)) {
            detailRejected.count++;
            detailRejected.lastMismatchM = off;
            // an older accepted tile there is now stale too: drop it, so the core ground (which is cut
            // wherever detail is present) takes the area back instead of leaving holes or old ground
            const held = lodStore.get(f.level);
            const tk = tileKey(f.tileX, f.tileY);
            if (held?.delete(tk)) {
              lodRev ??= { ...get().lodRevision };
              delete lodRev[lodKey(f.level, f.tileX, f.tileY)];
            }
            continue;
          }
        }
        let byTile = lodStore.get(f.level);
        if (!byTile) {
          byTile = new Map();
          lodStore.set(f.level, byTile);
        }
        const key = tileKey(f.tileX, f.tileY);
        const cur = byTile.get(key);
        if (cur && cur.version > f.version) continue;
        byTile.set(key, f);
        lodRev ??= { ...get().lodRevision };
        const lk = lodKey(f.level, f.tileX, f.tileY);
        lodRev[lk] = (lodRev[lk] ?? 0) + 1;
        if (f.level > 0) context++;
        continue;
      }
      let byTile = tileStore.get(f.field);
      if (!byTile) {
        byTile = new Map();
        tileStore.set(f.field, byTile);
      }
      const key = tileKey(f.tileX, f.tileY);
      const cur = byTile.get(key);
      if (cur && cur.version > f.version) continue;
      byTile.set(key, f);
      rev[key] = (rev[key] ?? 0) + 1;
      changed = true;
    }
    if (changed) set({ tileRevision: rev });
    if (lodRev) set({ lodRevision: lodRev, contextRevision: get().contextRevision + context });
  },

  dropLodLevels: (levels) => {
    if (levels.length === 0) return;
    for (const l of levels) lodStore.delete(l);
    const prefixes = levels.map((l) => `${l}:`);
    const rev: Record<string, number> = {};
    for (const [k, v] of Object.entries(get().lodRevision)) if (!prefixes.some((p) => k.startsWith(p))) rev[k] = v;
    set({ lodRevision: rev });
  },

  applyState: (s) => {
    const history = { ...get().history };
    for (const [id, v] of Object.entries(s.volcanoes)) {
      const arr = history[id] ? history[id].slice() : [];
      const last = arr[arr.length - 1];
      if (last && s.time < last.time) arr.length = 0; // replay jump backwards
      if (!last || s.time > last.time) {
        const stations: HistorySample['stations'] = {};
        for (const st of v.deformation.stations) stations[st.id] = { east: st.east, north: st.north, up: st.up };
        arr.push({
          time: s.time,
          overpressure: v.chamber.overpressureMPa,
          strength: v.chamber.tensileStrengthMPa,
          eruptionRate: v.chamber.eruptionRate,
          rsam: v.seismic.rsam,
          vt: v.seismic.vtPerMinute,
          lp: v.seismic.lpPerMinute,
          temperature: v.chamber.temperatureC,
          silica: v.chamber.silicaWt,
          water: v.chamber.waterWt,
          crystals: v.chamber.crystalFraction,
          alertIndex: ALERT_LEVELS.indexOf(v.alert.level),
          stations,
        });
        if (arr.length > HISTORY_CAP) arr.splice(0, arr.length - HISTORY_CAP);
      }
      history[id] = arr;
    }
    const selected = get().selectedVolcano ?? Object.keys(s.volcanoes)[0] ?? null;
    set({ state: s, history, selectedVolcano: selected });
  },

  addEvents: (events, dropped) => {
    if (events.length === 0 && dropped === 0) return;
    const all = get().events.concat(events);
    if (all.length > EVENT_CAP) all.splice(0, all.length - EVENT_CAP);
    const key = events.filter(isImportant);
    let keyEvents = get().keyEvents;
    if (key.length > 0) {
      keyEvents = mergeByTime(keyEvents, key);
      if (keyEvents.length > KEY_EVENT_CAP) keyEvents.splice(0, keyEvents.length - KEY_EVENT_CAP);
    }
    set({ events: all, keyEvents, droppedEvents: get().droppedEvents + dropped });
  },

  clearForReplay: (time) => {
    const history: Record<string, HistorySample[]> = {};
    for (const [id, arr] of Object.entries(get().history)) history[id] = arr.filter((h) => h.time <= time);
    set({ history, events: get().events.filter((e) => e.time <= time), keyEvents: get().keyEvents.filter((e) => e.time <= time) });
  },

  pushError: (msg) => {
    set({ errors: [...get().errors.slice(-4), msg] });
    get().toast(msg, 'warn');
  },

  resetSession: () => {
    tileStore.clear();
    lodStore.clear();
    set({
      world: null,
      schema: null,
      clock: null,
      state: null,
      history: {},
      events: [],
      keyEvents: [],
      droppedEvents: 0,
      tileRevision: {},
      lodRevision: {},
      contextRevision: 0,
      section: null,
      sectionPending: null,
      sectionShallow: null,
      sectionShallowPending: null,
      sectionPolyline: [],
      replayInfo: null,
      units: {},
      selectedVolcano: null,
      tool: 'orbit',
      entities: {},
      selection: null,
      inspection: null,
      inspectPending: null,
      hover: null,
    });
  },

  openDrawer: (tab) => set({ drawer: get().drawer === tab ? null : tab }),

  toast: (text, tone = 'info', action) => {
    set((s) => {
      const previous = s.toasts.find((t) => t.id === text);
      const t: HudToast = { id: text, text, tone, action, duration: tone === 'alert' ? 9000 : 6000, serial: (previous?.serial ?? 0) + 1 };
      return { toasts: [...s.toasts.filter((x) => x.id !== text), t].slice(-6) };
    });
  },

  dismissToast: (id) => set((s) => ({ toasts: s.toasts.filter((t) => t.id !== id) })),

  applyEntities: (m) => {
    const u = applyEntities(get().entities, m, performance.now());
    const sel = get().selection;
    // a selected entity that disappears for good leaves the selection at its last place
    const lost = sel?.type === 'entity' && !u.entities[sel.id];
    set({ entities: u.entities, ...(lost ? { selection: null } : {}) });
    return u.added;
  },

  select: (selection) => {
    // selecting something of a volcano makes it the volcano the action bar works on
    const e = selection?.type === 'entity' ? get().entities[selection.id] : undefined;
    const v = e?.volcanoId ?? (selection?.type === 'quake' ? selection.event.volcanoId : undefined);
    set({
      selection,
      inspection: selection && sameSelection(selection, get().selection) ? get().inspection : null,
      ...(v && get().world?.volcanoes.some((w) => w.id === v) ? { selectedVolcano: v } : {}),
    });
  },
}));

function sameSelection(a: Selection, b: Selection | null): boolean {
  if (!b || a.type !== b.type) return false;
  if (a.type === 'entity' && b.type === 'entity') return a.id === b.id;
  if (a.type === 'point' && b.type === 'point') return a.at[0] === b.at[0] && a.at[1] === b.at[1];
  return a.type === 'quake' && b.type === 'quake' && a.event === b.event;
}

/** Finished fade-outs are dropped every few seconds. */
if (typeof window !== 'undefined') {
  window.setInterval(() => {
    const s = useStore.getState();
    const next = pruneEntities(s.entities, performance.now());
    if (next !== s.entities) s.set({ entities: next });
  }, 1000);
}

/** View preferences are remembered across visits. */
if (typeof window !== 'undefined') {
  useStore.subscribe((s, prev) => {
    if (s.quakeFilter !== prev.quakeFilter) saveQuakeFilter(s.quakeFilter);
    if (s.hiddenCategories !== prev.hiddenCategories) savePref(PREF_KEYS.hiddenCategories, s.hiddenCategories);
    if (s.showPerf !== prev.showPerf) savePref(PREF_KEYS.showPerf, s.showPerf);
    if (s.waterClarity !== prev.waterClarity) savePref(PREF_KEYS.waterClarity, s.waterClarity);
    if (s.waterSeeThrough !== prev.waterSeeThrough) savePref(PREF_KEYS.waterSeeThrough, s.waterSeeThrough);
    if (s.autoQuality !== prev.autoQuality) savePref(PREF_KEYS.autoQuality, s.autoQuality);
    if (s.showSimulatedArea !== prev.showSimulatedArea) savePref(PREF_KEYS.showSimulatedArea, s.showSimulatedArea);
  });
}

const GUIDE_KEY = 'typhon.guideSeen';
const DRAWER_KEY = 'typhon.drawerWidth';

function guideSeen(): boolean {
  try {
    return window.localStorage.getItem(GUIDE_KEY) === '1';
  } catch {
    return true; // no storage (screenshots, sandboxes): do not nag every load
  }
}

/** Remembers that the first-run guide was dismissed. */
export function rememberGuideSeen(): void {
  try {
    window.localStorage.setItem(GUIDE_KEY, '1');
  } catch {
    // ignore
  }
}

function initialDrawerWidth(): number {
  try {
    const w = Number(window.localStorage.getItem(DRAWER_KEY));
    if (w >= 300 && w <= 1200) return w;
  } catch {
    // ignore
  }
  return 440;
}

export function rememberDrawerWidth(w: number): void {
  try {
    window.localStorage.setItem(DRAWER_KEY, String(Math.round(w)));
  } catch {
    // ignore
  }
}

const SESSION_KEY = 'typhon.session';

/** The world/session the user last looked at (reattached after reloads). */
export function rememberedSession(): string | null {
  try {
    return window.localStorage.getItem(SESSION_KEY);
  } catch {
    return null;
  }
}

export function rememberSession(key: string): void {
  try {
    window.localStorage.setItem(SESSION_KEY, key);
  } catch {
    // ignore
  }
}

export type Quality = 'low' | 'medium' | 'high';

const QUALITY_KEY = 'typhon.quality';

function initialQuality(): Quality {
  const q = new URLSearchParams(window.location.search).get('quality');
  if (q === 'low' || q === 'medium' || q === 'high') return q;
  try {
    const saved = window.localStorage.getItem(QUALITY_KEY);
    if (saved === 'low' || saved === 'medium' || saved === 'high') return saved;
  } catch {
    // storage unavailable (private mode, sandbox): fall through
  }
  return currentTier().quality; // first visit: what this device can afford
}

/** Persists the quality choice for the next visit (best effort). */
export function rememberQuality(q: Quality): void {
  try {
    window.localStorage.setItem(QUALITY_KEY, q);
  } catch {
    // ignore
  }
}

/** Per-quality rendering settings. */
/** `glow`: halo sprites over incandescent lava (0 = off). */
/** `ambientFps`: frame rate of ambient animation (plumes, pulses) while nothing else asks for frames. */
export const QUALITY: Record<Quality, { smoothRadius: number; shadows: boolean; plume: number; ash: number; glow: number; dpr: [number, number]; ambientFps: number }> = {
  low: { smoothRadius: 2, shadows: false, plume: 500, ash: 300, glow: 0, dpr: [1, 1], ambientFps: 15 },
  medium: { smoothRadius: 4, shadows: false, plume: 1200, ash: 800, glow: 1200, dpr: [1, 1.5], ambientFps: 30 },
  high: { smoothRadius: 5, shadows: true, plume: 2400, ash: 1600, glow: 3000, dpr: [1, 2], ambientFps: 60 },
};


function defaultServerUrl(): string {
  const q = new URLSearchParams(window.location.search).get('server');
  if (q) return q;
  const { protocol, hostname, host, port } = window.location;
  // Served by the sim-server itself (--ui): talk to the same origin. The Vite dev server (5180) and
  // file:// pages default to a local sim-server on 8787.
  if ((protocol === 'http:' || protocol === 'https:') && port !== '5180' && host) {
    return `${protocol === 'https:' ? 'wss' : 'ws'}://${host}/ws`;
  }
  return `ws://${hostname || 'localhost'}:8787/ws`;
}

/**
 * The clock "now", extrapolated from the last clock message while playing: at the playback speed
 * (or the measured rate when the computer falls behind it, or plays at Max).
 */
export function simNow(): number {
  const c = useStore.getState().clock;
  if (!c) return 0;
  if (c.mode === 'PAUSED' || c.replay) return c.time;
  const rate = c.mode === 'REALTIME' && !(c.rate > 0 && c.rate < 0.7 * c.speed) ? c.speed : c.rate;
  return c.time + (rate * (performance.now() - c.receivedAt)) / 1000;
}
