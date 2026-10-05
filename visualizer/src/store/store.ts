import { create } from 'zustand';
import type { FieldId } from '../protocol/fields';
import type { SectionFrame, TileFrame } from '../protocol/frames';
import type {
  ClockMessage,
  ReplayInfoMessage,
  SimEvent,
  StateMessage,
  UnitInfo,
  WelcomeMessage,
  WorldInfo,
  XY,
} from '../protocol/messages';

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
  world: WorldInfo | null;
  clock: (ClockMessage & { receivedAt: number }) | null;
  state: StateMessage | null;
  history: Record<string, HistorySample[]>;
  events: SimEvent[];
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

  set: (partial: Partial<Store>) => void;
  setStatus: (s: ConnectionStatus) => void;
  applyTiles: (frames: TileFrame[]) => void;
  applyState: (s: StateMessage) => void;
  addEvents: (events: SimEvent[], dropped: number) => void;
  clearForReplay: (time: number) => void;
  pushError: (msg: string) => void;
}

export const useStore = create<Store>((set, get) => ({
  status: 'connecting',
  serverUrl: defaultServerUrl(),
  welcome: null,
  world: null,
  clock: null,
  state: null,
  history: {},
  events: [],
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
    set({ events: all, droppedEvents: get().droppedEvents + dropped });
  },

  clearForReplay: (time) => {
    const history: Record<string, HistorySample[]> = {};
    for (const [id, arr] of Object.entries(get().history)) history[id] = arr.filter((h) => h.time <= time);
    set({ history, events: get().events.filter((e) => e.time <= time) });
  },

  pushError: (msg) => set({ errors: [...get().errors.slice(-4), msg] }),
}));

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
export const QUALITY: Record<Quality, { smoothRadius: number; shadows: boolean; plume: number; ash: number; glow: number; dpr: [number, number] }> = {
  low: { smoothRadius: 2, shadows: false, plume: 500, ash: 300, glow: 0, dpr: [1, 1] },
  medium: { smoothRadius: 4, shadows: false, plume: 1200, ash: 800, glow: 2000, dpr: [1, 1.5] },
  high: { smoothRadius: 5, shadows: true, plume: 2400, ash: 1600, glow: 6000, dpr: [1, 2] },
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
