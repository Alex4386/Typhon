import { toast as sonner } from 'sonner';
import { create } from 'zustand';
import type { FieldId } from '../protocol/fields';
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
import { isBool, isFlags, loadPref, PREF_KEYS, savePref } from './prefs';
import { loadQuakeFilter, saveQuakeFilter, type QuakeFilter } from './quakeFilter';
import { isImportant, mergeByTime } from '../panels/events';
import { applyEntities, pruneEntities, type EntityMap, type EntityView, type Selection } from './entities';

/** Side drawer pages; only one is open at a time (progressive disclosure). */
export type DrawerTab = 'sims' | 'entities' | 'monitor' | 'events' | 'section' | 'view' | 'tune';

export type ToastTone = 'info' | 'warn' | 'alert';
export interface ToastAction {
  label: string;
  onClick: () => void;
}

/** What the pointer is over in the 3D view (label shown next to the cursor). */
export interface Hover {
  label: string;
  detail?: string;
  x: number;
  y: number;
}

export type Tool = 'orbit' | 'section' | 'water' | 'dig';
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
  /** Lower the resolution (and then quality) when frames get slow (persisted). */
  autoQuality: boolean;

  set: (partial: Partial<Store>) => void;
  setStatus: (s: ConnectionStatus) => void;
  applyTiles: (frames: TileFrame[]) => void;
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
  autoQuality: loadPref(PREF_KEYS.autoQuality, true, isBool),

  set: (partial) => set(partial),
  setStatus: (status) => set({ status }),

  applyTiles: (frames) => {
    const rev = { ...get().tileRevision };
    let changed = false;
    for (const f of frames) {
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
    const opts = { id: text, duration: tone === 'alert' ? 9000 : 6000, ...(action ? { action } : {}) };
    if (tone === 'alert') sonner.error(text, opts);
    else if (tone === 'warn') sonner.warning(text, opts);
    else sonner(text, opts);
  },

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
    if (s.autoQuality !== prev.autoQuality) savePref(PREF_KEYS.autoQuality, s.autoQuality);
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
  return 'medium';
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

/** Simulation time "now", extrapolated from the last clock message while playing. */
export function simNow(): number {
  const c = useStore.getState().clock;
  if (!c) return 0;
  if (c.mode === 'PAUSED' || c.replay) return c.time;
  return c.time + (c.rate * (performance.now() - c.receivedAt)) / 1000;
}
