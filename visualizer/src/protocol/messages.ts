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
      /** An empty world (terrain and sea only) from a server template: `ocean`, `flat`, `slope`. */
      template?: string;
      /** The template's fields (defaults from the catalog). */
      params?: Record<string, number>;
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
  | { type: 'subscribe'; fields: FieldId[]; bounds?: TileBounds; levels?: number[] }
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
   * Places a magma chamber below the ground at `at`: a new volcano whose vent forms where magma first
   * reaches the surface. Omitted fields take the server's defaults (`schema.commands.placeChamber`).
   */
  | { type: 'placeChamber'; requestId?: number; at: XY; name?: string; fields?: Record<string, number>; dryRun?: boolean }
  /** Removes a volcano (a reset: the first reply asks to confirm with a token). */
  | { type: 'removeVolcano'; requestId?: number; volcanoId: string; confirm?: string; dryRun?: boolean }
  /** Legacy alias of `setConfig` by schema id; `restart: true` confirms a reset. Prefer `setConfig`. */
  | { type: 'setParams'; requestId?: number; values: Record<string, ParamValue | null>; restart?: boolean }
  /**
   * The configuration API (docs/protocol.md §9): a patch of the world and/or volcano definitions
   * (dotted paths; `null` = back to the default or computed value), or full definitions with `replace`.
   * The server decides how to apply each change and answers with `configResult`.
   */
  | {
      type: 'setConfig';
      requestId?: number;
      world?: Record<string, ParamValue | null>;
      volcanoes?: Record<string, Record<string, ParamValue | null>>;
      replace?: boolean;
      dryRun?: boolean;
      /** The `token` of a `needsConfirmation` answer the user confirmed. */
      confirm?: string;
    }
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
  | { kind: 'setWind'; speed: number; bearingDeg: number }
  /** Plug a summit vent or fissure (magma leaves through the others) / open it again. */
  | { kind: 'sealVent' | 'unsealVent'; volcanoId: string; ventId: string }
  /** Delete a dike-fed fissure (summit vents can only be sealed). */
  | { kind: 'removeVent'; volcanoId: string; ventId: string }
  /** Delete a dike (its fissure stops being a vent; the intrusion stays in the rock). */
  | { kind: 'removeDike'; volcanoId: string; dikeId: number }
  /** Stop (or allow again) dikes nucleating on their own; forceDike still works. */
  | { kind: 'blockDikes'; volcanoId: string; blocked: boolean };

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
  | (ConfigResult & { type: 'configResult' })
  /** Sent on request and pushed to every client whenever the list changes (and every few seconds). */
  | { type: 'sessions'; sessions: SessionInfo[]; server?: ServerInfo }
  | CatalogMessage
  /** The attached session was closed (by this or another client); attach to another one. */
  | { type: 'detached'; sessionId: string; reason: string }
  | AttachedMessage
  | WorldExtentMessage
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
  /** Current value; `null` for an auto parameter while the engine computes it. */
  value?: ParamValue | null;
  /**
   * Computed by the engine from physics unless overridden: `setParams` with a number overrides it,
   * with `null` hands it back to the engine.
   */
  auto?: boolean;
  /** For an auto parameter: what the engine computes now (null when it cannot say). */
  computed?: number | null;
  /** The server's prediction of how a change is applied (a hint: `configResult` says what happened). */
  apply: ApplyKind;
  /** The server's prediction of the consequence, in words to show as they are. */
  impact?: Impact;
  /** Volcano the parameter belongs to (absent for world-level ones). */
  volcanoId?: string;
}

/** How the server applies a change: in place, rebuilt keeping state, or reset to its new initial state. */
export type ApplyKind = 'live' | 'reload' | 'reinit';

/** A consequence the server describes; clients show `message` and decide nothing from field names. */
export interface Impact {
  kind: ApplyKind;
  target: string;
  message: string;
  reason?: string;
}

/** One change in a `configResult`. */
export interface ConfigChangeReport {
  id: string;
  scope: string;
  volcanoId?: string;
  path: string;
  from: ParamValue | string | null;
  to: ParamValue | string | null;
  impact: Impact;
  /** What was actually done (absent on dry runs and confirmations). */
  applied?: ApplyKind;
}

/** The answer to `setConfig` (also the body of HTTP PATCH/PUT /api/sessions/{id}/config). */
export interface ConfigResult {
  type?: 'configResult';
  requestId?: number;
  ok: boolean;
  dryRun?: boolean;
  /** The most disruptive kind among the changes. */
  plan?: ApplyKind;
  applied?: ApplyKind;
  changes?: ConfigChangeReport[];
  /** Distinct consequences, most disruptive first. */
  consequences?: Impact[];
  warnings?: string[];
  /** A reset the user must confirm: re-send with `confirm: token`. */
  needsConfirmation?: boolean;
  token?: string;
  errors?: { path: string; message: string }[];
  note?: string;
  ms?: number;
  /** The volcano a `placeChamber` created. */
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
  apply: ApplyKind | 'hot' | 'restart';
  /** The consequence as the server described it. */
  message?: string;
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
  /** Parameter ids the server shows in Inspector panels (e.g. a chamber's settings, by volcano id). */
  panels?: { chamber?: Record<string, string[]> };
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

/** An empty-world template: terrain and sea only; the user places magma chambers into it. */
export interface TemplateInfo {
  name: string;
  title: string;
  description: string;
  /** Its parameters, with the server's defaults. */
  fields: ParamSpec[];
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
  /** Empty-world templates, offered first (the world-builder path). */
  templates?: TemplateInfo[];
  defaultTemplate?: string;
  presets: PresetInfo[];
  /** The preset new worlds start from unless the user picks another (the server's choice). */
  defaultPreset?: string;
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
  /** False when the world has no sea: `seaLevel` is then only the lowest ground (absent: assume a sea). */
  hasSea?: boolean;
  /** Elevation range of the world (m), for colour ramps. */
  elevationRange: [number, number];
  volcanoes: VolcanoInfo[];
  materials: MaterialInfo[];
  depositTypes: DepositTypeInfo[];
  /** The tile pyramid around the core (§5.5); absent on servers without one (the mock). */
  lod?: LodInfo;
  /**
   * Level-0 tiles holding simulated ground, as [tx, ty]; elsewhere inside `tiles` the ground is generated
   * and nothing happens until activity reaches it. Absent: every tile is simulated.
   */
  simulated?: [number, number][];
  /** On-demand growth of the simulated area (from `worldExtent`). */
  expansion?: ExpansionInfo;
}

export interface ExpansionInfo {
  addedTiles: number;
  maxTiles: number;
  enabled: boolean;
}

/** The simulated area grew: new tile range, simulated tiles and pyramid; origin and tile coordinates are unchanged. */
export interface WorldExtentMessage {
  type: 'worldExtent';
  sessionId: string;
  tiles: TileBounds;
  simulated: [number, number][];
  lod?: LodInfo;
  expansion?: ExpansionInfo;
}

/** One level of the tile pyramid: coarse context (level > 0) or crater detail (level < 0). */
export interface LodLevelInfo {
  level: number;
  /** Cell size of this level (m): the core's cellSize · 2^level. */
  cellSize: number;
  tiles: TileBounds;
  fields: FieldId[];
  kind: 'context' | 'detail';
}

export interface LodInfo {
  levels: LodLevelInfo[];
  /** Whole landscape the server describes [x0, y0, x1, y1] (m), core included. */
  extent: [number, number, number, number];
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
  /** Not formed yet: where a placed chamber's conduit will meet the ground. */
  emergent?: boolean;
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
    /** DRE m³/s per simulated second (time-compressed), 0 when not erupting: what the scene shows flowing. */
    eruptionRate: number;
    /** m³/s of volcano time: comparable with real volcanoes. */
    physicalEruptionRate?: number;
    /** Magma volume in the chamber (m³). */
    volumeM3?: number;
    /** Overpressure at which the walls rupture (MPa). */
    ruptureOverpressureMPa?: number;
    regime: EruptiveRegime;
    /** Magma budget (volcano time); absent from older servers. */
    budget?: MagmaBudget;
  };
  seismic: { rsam: number; vtPerMinute: number; lpPerMinute: number; tremor: boolean; swarm: boolean };
  /** style: estimated from the eruption (or, with styleForecast, forecast for the next one); null until estimated. */
  alert: { level: AlertLevel; style: EruptionStyle | null; vei?: number; styleForecast?: boolean };
  deformation: { maxUpliftM: number; stations: StationReading[] };
  plume?: { topZ: number; massRateKgS: number };
  /** Landscape change so far (absent on servers without geomorphology). */
  geomorph?: { failures: number; failedM3: number; avalanches: number; craters: number; maxCraterRadiusM: number; calderaSubsidenceM: number };
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
  | { kind: 'massFlowFront'; time: number; volcanoId: string; flow: 'PDC' | 'LAHAR' | 'DEBRIS_AVALANCHE'; cells: XY[]; speed: number; temperatureC: number }
  /** A vent or fissure changed state (fed, waning, frozen, sealed, removed …). */
  | { kind: 'ventState'; time: number; volcanoId: string; ventId: string; previous: string | null; state: string; feederWidthM?: number }
  /** A slope gave way: talus, or a debris avalanche / debris flow (style). */
  | { kind: 'slopeFailure'; time: number; volcanoId: string; at: [number, number, number]; volumeM3: number; style: 'TALUS' | 'DEBRIS_AVALANCHE' | 'DEBRIS_FLOW' | string; trigger: string; factorOfSafety: number }
  /** An explosion dug or enlarged a crater. */
  | { kind: 'craterExcavated'; time: number; volcanoId: string; at: [number, number, number]; radiusM: number; depthM: number }
  /** The chamber roof sank as a piston (caldera or pit-crater collapse); subsidenceM is the total so far. */
  | { kind: 'calderaCollapse'; time: number; volcanoId: string; at: [number, number, number]; radiusM: number; subsidenceM: number }
  | { kind: 'geothermalFeature'; time: number; volcanoId: string; feature: string; at: [number, number, number] }
  | { kind: 'oceanEntry'; time: number; at: XY; powerMW: number; littoralExplosion: boolean }
  /** Activity near the edge materialised generated ground: `bbox` [west, south, east, north] (m) of it. */
  | { kind: 'areaExpanded'; time: number; tiles: number; addedTiles: number; areaKm2: number; bbox: [number, number, number, number] }
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

/** Where a chamber's magma comes from and goes (volcano-time rates, cumulative volumes). */
export interface MagmaBudget {
  supplyM3PerS: number;
  eruptionM3PerS: number;
  /** Pressure an erupting chamber settles at (outflow = supply); absent while not erupting. */
  balanceOverpressureMPa?: number;
  overpressureRateMPaPerS?: number;
  intrudedM3: number;
  wallGrowthM3: number;
  eruptedM3: number;
  eruptionEndOverpressureMPa?: number;
}
