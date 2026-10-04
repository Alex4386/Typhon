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
  waterVolume: number;
  digRadius: number;
  digDepth: number;

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
  waterVolume: 50000,
  digRadius: 40,
  digDepth: 30,

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

function defaultServerUrl(): string {
  const q = new URLSearchParams(window.location.search).get('server');
  if (q) return q;
  return `ws://${window.location.hostname || 'localhost'}:8787/ws`;
}

/** Simulation time "now", extrapolated from the last clock message while playing. */
export function simNow(): number {
  const c = useStore.getState().clock;
  if (!c) return 0;
  if (c.mode === 'PAUSED' || c.replay) return c.time;
  return c.time + (c.rate * (performance.now() - c.receivedAt)) / 1000;
}
