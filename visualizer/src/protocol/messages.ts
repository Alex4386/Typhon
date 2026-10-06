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
  /** Presets and the world directories under the server's worlds dir. */
  | { type: 'listCatalog' }
  /**
   * Starts (or, for a world that is already loaded, re-uses) a session. From a preset the server
   * writes a new world directory `name` (default: the preset name, made unique) unless `inMemory`.
   * `timeCompression` overrides the world's dormant/eruptive compression (a hot change).
   */
  | {
      type: 'createSession';
      requestId?: number;
      preset?: string;
      world?: string;
      seed?: number;
      name?: string;
      timeCompression?: { dormant?: number; eruptive?: number };
      /** Start paused (default: running at the server's initial speed). */
      paused?: boolean;
      /** Attach this client to the new session (default true). */
      attach?: boolean;
      /** Preset only: run it in memory without writing a world directory. */
      inMemory?: boolean;
    }
  /** Pause/resume any loaded session, or unload it (`close` saves world sessions first). */
  | { type: 'sessionControl'; requestId?: number; sessionId: string; action: SessionAction }
  /** Deletes a world directory under the worlds dir; it must not be loaded. */
  | { type: 'deleteWorld'; requestId?: number; name: string }
  | { type: 'attach'; sessionId: string }
  | { type: 'subscribe'; fields: FieldId[]; bounds?: TileBounds }
  /**
   * Flow control: total number of tile frames the client has received and processed since it
   * subscribed. The server keeps at most `TILE_WINDOW` unacknowledged tile frames in flight.
   */
  | { type: 'flow'; tilesProcessed: number }
  /**
   * REALTIME runs at `speed` × wall clock (0.1–1000); UNBOUNDED runs as fast as possible.
   * `sessionId` controls another loaded session than the attached one.
   */
  | { type: 'transport'; mode: TransportMode; speed?: number; sessionId?: string }
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
  | { type: 'seek'; time: number }
  /** Asks for the attached session's parameter schema (also pushed on attach and after every change). */
  | { type: 'getSchema' }
  /**
   * Changes parameters of the attached session by schema id; `null` resets one to its default.
   * Values whose spec says `apply: 'restart'` are refused unless `restart` is true (the affected
   * volcanoes, or the whole world for world-level ones, are rebuilt from their definition).
   */
  | { type: 'setParams'; requestId?: number; values: Record<string, ParamValue | null>; restart?: boolean }
  /** Everything known about the column at (x, y); answered with an `inspection` (§3.7). */
  | { type: 'inspect'; requestId: number; x: number; y: number };

export type ParamValue = number | boolean | string;

export type SimCommand =
  | { kind: 'startEruption'; volcanoId: string }
  | { kind: 'stopEruption'; volcanoId: string }
  | { kind: 'forceDike'; volcanoId: string }
  /**
   * Adds magma to the chamber. Optional fields set the batch's properties (otherwise the volcano's
   * configured recharge magma); the server's `schema.commands.injectMagma` lists every accepted field.
   */
  | { kind: 'injectMagma'; volcanoId: string; volumeM3: number; temperatureC?: number; silicaWt?: number; waterWt?: number; [field: string]: string | number | undefined }
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

export type SessionAction = 'pause' | 'resume' | 'close' | 'closeWithoutSaving';

export type ServerMessage =
  | WelcomeMessage
  /** Sent on request and pushed to every client whenever the list changes (and every few seconds). */
  | { type: 'sessions'; sessions: SessionInfo[]; server?: ServerInfo }
  | CatalogMessage
  /** The attached session was closed (by this or another client); attach to another one. */
  | { type: 'detached'; sessionId: string; reason: string }
  | AttachedMessage
  | ClockMessage
  | StateMessage
  | EventsMessage
  | { type: 'ack'; requestId: number; ok: boolean; message?: string }
  | ReplayInfoMessage
  | { type: 'replayReset'; time: number }
  /** Stratigraphic unit table: full list on attach (`replace: true`), then appended units. */
  | { type: 'units'; units: UnitInfo[]; replace: boolean }
  | { type: 'error'; code: ErrorCode; message: string; requestId?: number }
  | SchemaMessage
  | EntitiesMessage
  | InspectionMessage;

// ───────────────────────── Entities (§4.8) ─────────────────────────

export type EntityKind =
  | 'chamber'
  | 'vent'
  | 'fissure'
  | 'dike'
  | 'feature'
  | 'plume'
  | 'station'
  | 'quake'
  | 'lavaFront'
  | 'lavaField'
  | 'pdc'
  | 'lahar';

export type EntityProp = string | number | boolean | null;

/** A thing in the world with a lifetime: created, updated, removed (ids are stable). */
export interface Entity {
  id: string;
  kind: EntityKind | string;
  volcanoId?: string;
  label: string;
  /** Representative point, protocol metres [x, y, z]. */
  at: [number, number, number];
  /** Optional geometry (dikes: origin → tip). */
  path?: [number, number, number][];
  props: Record<string, EntityProp>;
  createdAt: number;
  updatedAt: number;
  /** Statistics only, not drawn. */
  hidden?: boolean;
}

/** Entity delta: `replace` = full set (attach, replay seek), else upserts and removals since the last one. */
export interface EntitiesMessage {
  type: 'entities';
  time: number;
  replace: boolean;
  upsert: Entity[];
  remove: string[];
}

export interface InspectionLayer {
  top: number;
  bottom: number;
  material: string;
  unit: number;
  depositType?: string;
  label?: string;
  porosity: number;
  voidFraction?: number;
  loose?: boolean;
}

/** Reply to `inspect`: one column of the world. */
export interface InspectionMessage {
  type: 'inspection';
  requestId?: number;
  at: XY;
  column: string;
  inside: boolean;
  surfaceZ?: number;
  surfaceMaterial?: string;
  layers?: InspectionLayer[];
  layerCount?: number;
  water?: { tableZ: number; tableDepthM: number; surfaceWaterDepthM: number; vadoseM: number; steamFluxKgPerSm2: number };
  temperatureProfile?: { depthM: number; temperatureC: number; steam?: number }[];
  groundTemperatureC?: number;
  lava?: { thicknessM: number; temperatureC: number; crustM: number };
  pdc?: { depthM: number; speedMPerS: number; temperatureC: number };
  lahar?: { depthM: number; speedMPerS: number; temperatureC: number };
}

/** One tunable value. Ids are dotted paths into the world/volcano definition (see docs/protocol.md). */
export interface ParamSpec {
  id: string;
  label: string;
  /** Display unit ("°C", "wt%", "m³/s"); values are always in this unit. */
  unit?: string;
  help?: string;
  /** Heading the panel groups it under ("World · Weather", "Kīlauea · Magma"). */
  group: string;
  type: 'number' | 'boolean' | 'choice';
  min?: number;
  max?: number;
  step?: number;
  /** Suggest a logarithmic slider (values spanning decades). */
  log?: boolean;
  choices?: string[];
  default?: ParamValue;
  value?: ParamValue;
  /** `hot`: applies to the running simulation; `restart`: rebuilds the volcano (or world). */
  apply: 'hot' | 'restart';
  /** Volcano the parameter belongs to (absent for world-level ones). */
  volcanoId?: string;
}

export interface ParamChange {
  /** Wall-clock time (ms since epoch). */
  at: number;
  simTime: number;
  id: string;
  label: string;
  from: ParamValue | null;
  to: ParamValue | null;
  apply: 'hot' | 'restart';
}

export interface SchemaMessage {
  type: 'schema';
  sessionId: string;
  /** False for sessions whose definition cannot be changed (in-memory presets); `reason` says why. */
  tunable: boolean;
  reason?: string;
  params: ParamSpec[];
  /** Fields each command accepts, e.g. `injectMagma`: volume, temperature, composition. */
  commands: Record<string, ParamSpec[]>;
  /** Recent changes, newest last. */
  audit: ParamChange[];
}

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

export interface TimeCompression {
  /** Physical seconds per simulated second while the volcano is quiet. */
  dormant: number;
  /** Physical seconds per simulated second while it erupts. */
  eruptive: number;
}

export interface SessionInfo {
  id: string;
  name: string;
  preset?: string;
  /** World directory name (world sessions). */
  world?: string;
  time: number;
  mode?: TransportMode;
  speed?: number;
  /** Measured simulated seconds per wall second. */
  rate?: number;
  replay?: boolean;
  /** Clients currently attached. */
  clients?: number;
  volcanoes?: { id: string; alert: AlertLevel; erupting: boolean; timeCompression: TimeCompression & { current?: number } }[];
}

export interface ServerInfo {
  maxSessions: number;
  cpus: number;
  heapUsedMB: number;
  heapMaxMB: number;
  worldsDir: string;
}

export interface PresetInfo {
  name: string;
  title: string;
  description: string;
  /** Real-volcano scale (km domains) rather than a compact demo. */
  realScale: boolean;
}

export interface WorldListing {
  /** Directory name under the worlds dir (what `createSession.world` takes). */
  name: string;
  title: string;
  volcanoes: number;
  /** A saved state exists: opening resumes it. */
  hasState: boolean;
  timeCompression?: TimeCompression;
  /** Set when the world is loaded. */
  sessionId?: string;
  /** The definition could not be read. */
  error?: string;
}

export interface CatalogMessage {
  type: 'catalog';
  presets: PresetInfo[];
  worlds: WorldListing[];
  server?: ServerInfo;
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
  /** Current time compression of the first volcano (physical s per simulated s). */
  compression?: number;
  /** Approximate physical (volcano) time elapsed for the first volcano since the session started (s). */
  physicalTime?: number;
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

export type EruptionStyle =
  | 'HAWAIIAN' | 'STROMBOLIAN' | 'VULCANIAN' | 'PELEAN' | 'PLINIAN' | 'LAVA_DOME'
  | 'SUBPLINIAN' | 'SURTSEYAN' | 'PHREATIC' | 'MIXED';

export type EruptiveRegime = 'NONE' | 'FOUNTAINING' | 'OPEN_VENT' | 'EFFUSIVE' | 'DOME' | 'EXPLOSIVE' | 'SURTSEYAN';

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
    /** Magma volume in the chamber (m³). */
    volumeM3?: number;
    regime: EruptiveRegime;
  };
  seismic: { rsam: number; vtPerMinute: number; lpPerMinute: number; tremor: boolean; swarm: boolean };
  /** style: estimated from the eruption (or, with styleForecast, forecast for the next one); null until estimated. */
  alert: { level: AlertLevel; style: EruptionStyle | null; vei?: number; styleForecast?: boolean };
  deformation: { maxUpliftM: number; stations: StationReading[] };
  plume?: { topZ: number; massRateKgS: number };
  /** Time compression now in force (`current`) and its dormant/eruptive settings. */
  timeCompression?: TimeCompression & { current: number };
  /** Approximate physical time elapsed for this volcano since the session started (s). */
  physicalTime?: number;
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
  | {
      kind: 'styleEstimated';
      time: number;
      volcanoId: string;
      previous: EruptionStyle | null;
      current: EruptionStyle;
      vei: number;
      forecast: boolean;
      probabilities: Partial<Record<EruptionStyle, number>>;
    }
  | { kind: 'dikeStarted'; time: number; volcanoId: string; dikeId: number; origin: [number, number, number]; overpressureMPa: number }
  | { kind: 'dikeAdvanced'; time: number; volcanoId: string; dikeId: number; path: [number, number, number][] }
  | { kind: 'dikeStalled'; time: number; volcanoId: string; dikeId: number; tip: [number, number, number]; depthM: number; volumeM3: number; reason: string }
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
