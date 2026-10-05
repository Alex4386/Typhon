// JSON (text frame) messages of the sim-server ↔ visualizer protocol v1.
// Keep in sync with docs/protocol.md §3–4.

import type { FieldId } from './fields';

export const PROTOCOL_VERSION = 1;
/** Maximum unacknowledged tile frames in flight per client (see the `flow` message). */
export const TILE_WINDOW = 48;
export const WS_SUBPROTOCOL = 'typhon.v1';

/** World coordinates: x east, y north (m, horizontal), z up (m above datum). */
export type XY = [number, number];

// ───────────────────────── Client → server ─────────────────────────

/** Runner modes, named as in the engine's `EngineRunner.Mode`. */
export type TransportMode = 'REALTIME' | 'UNBOUNDED' | 'PAUSED';

export type ClientMessage =
  | { type: 'hello'; protocol: number; client: string }
  | { type: 'listSessions' }
  | { type: 'createSession'; preset?: string; world?: string; seed?: number }
  | { type: 'attach'; sessionId: string }
  | { type: 'subscribe'; fields: FieldId[]; bounds?: TileBounds }
  /**
   * Flow control: total number of tile frames the client has received and processed since it
   * subscribed. The server keeps at most `TILE_WINDOW` unacknowledged tile frames in flight.
   */
  | { type: 'flow'; tilesProcessed: number }
  /** REALTIME runs at `speed` × wall clock (0.1–1000); UNBOUNDED runs as fast as possible. */
  | { type: 'transport'; mode: TransportMode; speed?: number }
  /** Pause, then advance exactly `steps` engine base steps, or the smallest number of steps covering `seconds`. */
  | { type: 'step'; steps?: number; seconds?: number }
  /** Pause automatically once simulation time reaches `time` (s); null clears it. */
  | { type: 'pauseAt'; time: number | null }
  | { type: 'command'; requestId?: number; command: SimCommand }
  /** `datum: 'surface'` makes zMin/zMax and every z in the reply relative to each column's ground. */
  | { type: 'section'; requestId: number; polyline: XY[]; zMin: number; zMax: number; nu: number; nz: number; datum?: SectionDatum }
  | { type: 'save'; name: string }
  | { type: 'load'; name: string }
  | { type: 'replay'; action: 'enter' | 'exit' }
  | { type: 'seek'; time: number };

export type SimCommand =
  | { kind: 'startEruption'; volcanoId: string }
  | { kind: 'stopEruption'; volcanoId: string }
  | { kind: 'forceDike'; volcanoId: string }
  | { kind: 'injectMagma'; volcanoId: string; volumeM3: number }
  /** Rain over the whole world (mm/h); 0 stops. */
  | { kind: 'rain'; mmPerHour: number }
  /** Pour water at a point (m³, released over `seconds`). */
  | { kind: 'addWater'; at: XY; volumeM3: number; seconds?: number }
  /** Excavate a vertical shaft/pit: radius (m), from the surface down `depth` m. */
  | { kind: 'dig'; at: XY; radius: number; depth: number }
  /** Wind speed (m/s) and the bearing it blows towards (degrees clockwise from north). */
  | { kind: 'setWind'; speed: number; bearingDeg: number };

export interface TileBounds {
  minTx: number;
  minTy: number;
  maxTx: number;
  maxTy: number;
}

// ───────────────────────── Server → client ─────────────────────────

export type ServerMessage =
  | WelcomeMessage
  | { type: 'sessions'; sessions: SessionInfo[] }
  | AttachedMessage
  | ClockMessage
  | StateMessage
  | EventsMessage
  | { type: 'ack'; requestId: number; ok: boolean; message?: string }
  | ReplayInfoMessage
  | { type: 'replayReset'; time: number }
  /** Stratigraphic unit table: full list on attach (`replace: true`), then appended units. */
  | { type: 'units'; units: UnitInfo[]; replace: boolean }
  | { type: 'error'; code: ErrorCode; message: string; requestId?: number };

export type ErrorCode =
  | 'protocol'
  | 'badRequest'
  | 'noSession'
  | 'unknownVolcano'
  | 'unsupported'
  | 'internal';

export interface WelcomeMessage {
  type: 'welcome';
  protocol: number;
  server: string;
  /** Codecs/fields the server can produce. */
  fields: FieldId[];
  /** True for the development mock. */
  mock?: boolean;
}

export interface SessionInfo {
  id: string;
  name: string;
  preset?: string;
  time: number;
}

export interface AttachedMessage {
  type: 'attached';
  sessionId: string;
  world: WorldInfo;
}

export interface WorldInfo {
  name: string;
  /** World coordinates of tile (0,0)'s south-west corner (m). */
  origin: XY;
  /** Surface cell size (m). */
  cellSize: number;
  /** Columns per tile edge (tiles are square). */
  tileSize: number;
  /** Inclusive tile range present in this world. */
  tiles: TileBounds;
  seaLevel: number;
  /** Elevation range of the world (m), for colour ramps. */
  elevationRange: [number, number];
  volcanoes: VolcanoInfo[];
  materials: MaterialInfo[];
  depositTypes: DepositTypeInfo[];
}

export interface VolcanoInfo {
  id: string;
  name: string;
  vents: VentInfo[];
  chamber: { center: [number, number, number]; radius: number };
}

export interface VentInfo {
  id: string;
  kind: 'crater' | 'fissure';
  at: XY;
  z: number;
  radius: number;
  /** Fissure end points (fissures only). */
  line?: [XY, XY];
}

export interface MaterialInfo {
  id: number;
  name: string;
  /** CSS colour suggestion. */
  color: string;
  kind: 'rock' | 'tephra' | 'soil' | 'ice' | 'void' | 'water' | 'magma';
}

export interface DepositTypeInfo {
  id: number;
  name: string;
  color: string;
}

export interface ClockMessage {
  type: 'clock';
  /** Simulation time (s). */
  time: number;
  /** Completed engine base steps. */
  step: number;
  /** Engine base step (s), e.g. 0.05. */
  baseStep: number;
  mode: TransportMode;
  speed: number;
  /** Measured simulated seconds per wall second. */
  rate: number;
  replay: boolean;
}

export interface StateMessage {
  type: 'state';
  time: number;
  volcanoes: Record<string, VolcanoState>;
  world: { rainMmPerHour: number; wind: { speed: number; bearingDeg: number } };
}

export type AlertLevel =
  | 'EXTINCT'
  | 'DORMANT'
  | 'MINOR_ACTIVITY'
  | 'MAJOR_ACTIVITY'
  | 'ERUPTION_IMMINENT'
  | 'ERUPTING';

export type EruptionStyle = 'HAWAIIAN' | 'STROMBOLIAN' | 'VULCANIAN' | 'PELEAN' | 'PLINIAN' | 'LAVA_DOME';

export type EruptiveRegime = 'NONE' | 'FOUNTAINING' | 'OPEN_VENT' | 'DOME' | 'EXPLOSIVE' | 'SURTSEYAN';

export interface VolcanoState {
  chamber: {
    overpressureMPa: number;
    tensileStrengthMPa: number;
    temperatureC: number;
    silicaWt: number;
    waterWt: number;
    crystalFraction: number;
    /** DRE m³/s, 0 when not erupting. */
    eruptionRate: number;
    regime: EruptiveRegime;
  };
  seismic: { rsam: number; vtPerMinute: number; lpPerMinute: number; tremor: boolean; swarm: boolean };
  alert: { level: AlertLevel; style: EruptionStyle };
  deformation: { maxUpliftM: number; stations: StationReading[] };
  plume?: { topZ: number; massRateKgS: number };
}

export interface StationReading {
  id: string;
  at: XY;
  /** Displacement (m). */
  east: number;
  north: number;
  up: number;
  /** Tilt (µrad). */
  tiltX: number;
  tiltY: number;
}

export interface EventsMessage {
  type: 'events';
  events: SimEvent[];
  /** Events dropped by the server's ring buffer since the last message. */
  dropped: number;
}

/** Events carry simulation `time` (s). Unknown `kind`s must be ignored by clients. */
export type SimEvent =
  | { kind: 'seismic'; time: number; volcanoId: string; type: 'VT' | 'LP' | 'TREMOR' | 'EXPLOSION'; magnitude: number; hypocenter: [number, number, number]; durationSeconds: number; swarm: boolean }
  | { kind: 'eruptionStarted'; time: number; volcanoId: string; cause: string; ventIds: string[] }
  | { kind: 'eruptionEnded'; time: number; volcanoId: string; eruptedVolumeM3: number }
  | { kind: 'alertChanged'; time: number; volcanoId: string; previous: AlertLevel | null; current: AlertLevel }
  | { kind: 'regimeChanged'; time: number; volcanoId: string; regime: EruptiveRegime }
  | { kind: 'dikeAdvanced'; time: number; volcanoId: string; dikeId: number; path: [number, number, number][] }
  | { kind: 'fissureOpened'; time: number; volcanoId: string; vent: VentInfo }
  | { kind: 'bombLaunched'; time: number; volcanoId: string; id: number; start: [number, number, number]; velocity: [number, number, number]; dragK: number; flightSeconds: number; landing: [number, number, number] }
  | { kind: 'plume'; time: number; volcanoId: string; base: [number, number, number]; topZ: number; radius: number; massRateKgS: number }
  | { kind: 'lightning'; time: number; volcanoId: string; at: [number, number, number] }
  | { kind: 'massFlowFront'; time: number; volcanoId: string; flow: 'PDC' | 'LAHAR'; cells: XY[]; speed: number; temperatureC: number }
  | { kind: 'geothermalFeature'; time: number; volcanoId: string; feature: string; at: [number, number, number] }
  | { kind: 'oceanEntry'; time: number; at: XY; powerMW: number; littoralExplosion: boolean }
  | { kind: 'message'; time: number; text: string };

export interface ReplayInfoMessage {
  type: 'replayInfo';
  /** Recorded time range (s). */
  start: number;
  end: number;
  /** Times of full keyframes (s). */
  keyframes: number[];
}

/** Section metadata, carried as JSON inside the binary section frame (see frames.ts). */
/** Vertical reference of a section: absolute elevation, or metres relative to the local ground. */
export type SectionDatum = 'absolute' | 'surface';

export interface SectionMeta {
  requestId: number;
  /** Absent from older servers: absolute. */
  datum?: SectionDatum;
  /** Horizontal distance along the polyline (m) of column 0 and the last column. */
  length: number;
  zMin: number;
  zMax: number;
  units: UnitInfo[];
  overlays: SectionOverlay[];
}

export interface UnitInfo {
  id: number;
  volcanoId: string | null;
  depositType: number;
  eruption: number | null;
  /** Emplacement time (s), null for pre-existing geology. */
  time: number | null;
  label: string;
}

export type SectionOverlay =
  | { kind: 'chamber'; u: number; z: number; rx: number; rz: number; temperatureC: number }
  | { kind: 'conduit'; u: number; zTop: number; zBottom: number; width: number; active: boolean }
  | { kind: 'dike'; points: [number, number][]; active: boolean };
