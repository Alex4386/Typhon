# Typhon sim-server ↔ visualizer protocol, version 1

This document is the contract between the simulation server (M6, Java, `sim-server`) and the web
visualizer (M7, `visualizer/`). The TypeScript definitions in `visualizer/src/protocol/` are the
reference implementation; this document is normative where they differ.

The development mock in `visualizer/mock/` implements the server side and can be used to check a
Java implementation against the visualizer.

## 1. Conventions

- **Transport.** One WebSocket per client at `ws://<host>:<port>/ws`, subprotocol `typhon.v1`. The
  server rejects other subprotocols during the handshake.
- **Frames.** Client → server: JSON text frames only. Server → client: JSON text frames (§3–4) and
  binary frames (§5–6). Each binary frame starts with a frame-kind byte.
- **Byte order.** All binary numbers are little-endian. `f32`/`f64` are IEEE 754.
- **Compression.** Payloads marked compressed use zlib (RFC 1950): a 2-byte header, deflate data and
  an Adler-32 trailer. This is what `java.util.zip.Deflater` produces by default; decompress with
  `Inflater` (not with `nowrap`).
- **Coordinates.** World coordinates are metres: `x` east, `y` north, `z` up above the datum. Points
  are `[x, y]` (`XY`) or `[x, y, z]`. This is the simulator's real-scale frame, not Minecraft blocks.
- **Time.** All simulation times are **seconds** (JSON numbers, binary `f64`). The engine keeps exact
  integer microseconds internally; send `timeMicros / 1e6`. Event times use the same clock.
- **Ids.** Volcano and vent ids are the engine's ids (`"fuji"`, `"fuji/summit"`). Unit ids,
  material ids and deposit-type ids are small integers defined by the server per world (§3.3).
- **Unknown input.** Clients must ignore unknown message `type`s, unknown event `kind`s and unknown
  object properties. Servers answer unknown client messages with `error` code `badRequest`.

## 2. Session flow

```
client                                   server
  hello{protocol:1}             ─────▶
                                ◀─────   welcome{protocol:1, fields, mock?}
  listSessions, listCatalog     ─────▶
                                ◀─────   sessions[…], catalog{presets, worlds}
  attach{sessionId}             ─────▶   (or createSession{preset|world, …})
                                ◀─────   attached{world}, units{replace:true}, state, events,
                                         entities{replace:true}, replayInfo, clock, schema
  subscribe{fields}             ─────▶
                                ◀═════   tile frames (binary), throttled by flow control
  flow{tilesProcessed}          ─────▶   (repeatedly)
                                ◀─────   clock (≥ 2 Hz), state (≥ 2 Hz), events (as produced),
                                         units (when new deposits appear), replayInfo (~0.5 Hz),
                                         entities (deltas, ≤ 1 Hz, only when something changed)
  inspect{requestId, x, y}      ─────▶
                                ◀─────   inspection{requestId, …}
```

If `hello.protocol` differs from the server's, it sends `error{code:"protocol"}` and closes. One
session is one engine (a world with N volcanoes) driven by an `EngineRunner`. Several clients may
attach to the same session; transport and commands from any client affect everyone.

**Many worlds, one server.** A server runs several sessions at once (at most `--max-sessions`,
default 8). `--preset`/`--world` (comma-separated) only choose the sessions started with the server;
clients start, open, pause and close the others (§3.1). Each session has its own engine thread; the
engines share one worker pool per thread count (they take turns on it), so paused sessions cost
memory but no processor time. A client is attached to one session at a time; `attach` to another
detaches it from the first. The `sessions` list is pushed to every client whenever it changes and
every 2 s.

Sessions are of two kinds. *World* sessions live in a directory under `--worlds-dir`
(`world.yaml`, `volcanoes/*.yaml`, `state/`, `replay/`, `tuning/`); they are saved on close, can be
reopened later and can be tuned (§3.5). *In-memory* sessions run a preset without files (the
`--preset` sessions, or `createSession{inMemory:true}`) and are lost when closed.

## 3. Client → server messages

### 3.1 Connection and session

| type | fields | meaning |
|---|---|---|
| `hello` | `protocol: 1`, `client: string` | First message. |
| `listSessions` | — | Reply: `sessions`. |
| `listCatalog` | — | Reply: `catalog` (§4.1): presets and the world directories under `--worlds-dir`. |
| `createSession` | `requestId?`, `preset?: string`, `world?: string`, `seed?: number`, `name?: string`, `timeCompression?: {dormant?, eruptive?}`, `paused?: boolean`, `attach?: boolean` (default true), `inMemory?: boolean` | `world`: open `<worlds-dir>/<world>` (if it is already loaded, its session is reused). `preset`: write a new world directory `<worlds-dir>/<name>` (default: a free name derived from the preset) from the preset and open it; with `inMemory:true` run the preset without files instead. `timeCompression` overrides the world's dormant/eruptive time compression (not allowed for an already loaded world or in memory). Replies `ack{message:"Session <id>"}`, then the attach burst if `attach`; `error{badRequest}` when the server is full or the name is taken. |
| `attach` | `sessionId: string` | Attach to an existing session (detaching from the current one). Reply: `attached` …, or `error{noSession}`. |
| `sessionControl` | `requestId?`, `sessionId`, `action: "pause" \| "resume" \| "close" \| "closeWithoutSaving"` | Pause/resume any session (also one this client does not watch). `close` saves a world session and unloads it; clients attached to it receive `detached`. |
| `deleteWorld` | `requestId?`, `name` | Delete `<worlds-dir>/<name>` (refused while it is loaded). Reply `ack`, then a fresh `catalog`. |
| `subscribe` | `fields: FieldId[]`, `bounds?: TileBounds`, `levels?: int[]` | Replace the set of streamed tile fields (§5.2). Optional inclusive tile bounds restrict streaming of level 0; omitted means all tiles. `levels` adds pyramid levels from `WorldInfo.lod` (§5.5) — coarse context (> 0) and crater detail (< 0); level 0 always streams. Resets flow-control counters (§5.4). The server remembers which tiles the client holds for fields and levels that stay subscribed, so re-subscribing (e.g. to add or drop a detail level) only streams tiles the client never had; a dropped field or level is forgotten and sent in full when subscribed again. |
| `flow` | `tilesProcessed: number` | Cumulative tile frames processed since the last `subscribe` (§5.4). |

### 3.2 Transport

Names follow `EngineRunner.Mode`.

| type | fields | server action |
|---|---|---|
| `transport` | `mode: "REALTIME" \| "UNBOUNDED" \| "PAUSED"`, `speed?: number`, `sessionId?` (another loaded session; default the attached one) | `REALTIME` → `runner.realtime(speed)`, with `speed` clamped to 0.1–1000 and the current speed kept if omitted. `UNBOUNDED` → `runner.unbounded()`, which also remembers `speed`. `PAUSED` → `runner.pause()`. |
| `step` | `steps?: number` or `seconds?: number` | Pause, then `runner.step(n)`. `n = steps`, or `ceil(seconds / baseStep)`; at least 1. |
| `pauseAt` | `time: number \| null` | `runner.pauseAtTime(time)` (seconds); `null` clears it. |

After any transport change the server sends a `clock` message promptly.

### 3.3 Commands

`{type:"command", requestId?: number, command: SimCommand}`. Commands are queued with
`engine.submit(...)` and applied at the start of the next step. If `requestId` is present, the server
replies `ack{requestId, ok, message?}`. Use `ok:false` with a message for rejected commands (unknown
volcano, out of world, …).

| kind | fields | engine meaning |
|---|---|---|
| `startEruption` | `volcanoId` | Forced eruption start (`MagmaCommands.StartEruption`). |
| `stopEruption` | `volcanoId` | Forced stop. |
| `forceDike` | `volcanoId` | `DikeCommands.ForceDike`. |
| `arrestDike` | `volcanoId`, `dikeId` (integer) | `DikeCommands.ArrestDike`: a propagating dike stops where it is and freezes into an intrusion (`DikeStalled` with reason `ARRESTED`). Rejected (`unknownDike`, `badRequest`) for unknown or no longer propagating dikes. |
| `removeDike` | `volcanoId`, `dikeId` (integer) | `DikeCommands.RemoveDike`: deletes the dike (arresting it if still rising). Its fissure stops being a vent (`ventState` → `removed`); the intrusion stays in the rock and deformation. The `dike:` entity is removed. |
| `blockDikes` | `volcanoId`, `blocked` (boolean) | `DikeCommands.BlockDikes`: stops (or allows again) spontaneous dike nucleation; `forceDike` still works. Shown as the chamber's `dikesBlocked`. |
| `sealVent` | `volcanoId`, `ventId` | `VentCommands.SealVent`: plug a summit vent or a fissure. Magma leaves through the remaining open vents; with none left the eruption ends (`eruptionEnded` cause `SEALED`) and the chamber keeps its pressure. A sealed summit cannot fail, so pressure builds until a dike opens a flank path. Error `unknownVent` for an unknown id. |
| `unsealVent` | `volcanoId`, `ventId` | `VentCommands.UnsealVent`. A frozen fissure stays frozen (the ack carries a note). |
| `removeVent` | `volcanoId`, `ventId` | `VentCommands.RemoveVent`: delete a dike-fed fissure (same as `removeDike` on its dike). Summit vents are rejected (`unsupported`): seal them instead. |
| `injectMagma` | `volcanoId`, `volumeM3` (0–1e12), `temperatureC?` (650–1350), `silicaWt?` (42–78), `waterWt?` (0–8), `co2Wt?` (0–3), `crystalFraction?` (0–0.6) | Recharge pulse (`MagmaCommands.InjectRecharge`). Omitted properties take the magma the deep supply delivers now; out-of-range values are rejected. The accepted fields, their ranges and defaults are listed in `schema.commands.injectMagma` (§4.7), so clients build their form from it and pick up new fields without changes. |
| `rain` | `mmPerHour` | World rainfall rate; 0 stops it. |
| `addWater` | `at: XY`, `volumeM3`, `seconds?` | Pour water at a point, released over `seconds` (default 600). This is surface-water input that can infiltrate to the water table (plan §3-1). |
| `dig` | `at: XY`, `radius` (m), `depth` (m) | Excavate a pit from the surface (`WorldEdit.carve`/`erode`). |
| `setWind` | `speed` (m/s), `bearingDeg` | Wind blowing **towards** `bearingDeg`, clockwise from north. |

### 3.4 Sections, saves, replay

| type | fields | reply |
|---|---|---|
| `section` | `requestId`, `polyline: XY[]` (≥ 2 points), `zMin`, `zMax` (m), `nu` (8–1024), `nz` (8–512), optional `datum: "absolute" \| "surface"` (default absolute) | A binary section frame (§6) with the same `requestId`, or `error{badRequest, requestId}`. |
| `save` | `name` | `ack` or `error`. Saves the session to `worlds/<world>/state` (plan §3-3). |
| `load` | `name` | Re-attaches all clients to the loaded state (full attach burst). |
| `replay` | `action: "enter" \| "exit"` | Enter or leave replay mode (§7). |
| `seek` | `time` (s) | In replay mode only: jump to the state at `time` (§7). |

### 3.5 Parameters

| type | fields | reply |
|---|---|---|
| `getSchema` | — | `schema` (§4.7) of the attached session. It is also sent at the end of every attach burst. |
| `setParams` | `requestId?`, `values: {[paramId]: number \| boolean \| null}`, `restart?: boolean` | Changes definition values of the attached world session; `null` restores a parameter's default (for `auto` parameters: computed again). |

Parameter ids are dotted paths into the world's definition files: `world.<path>` for `world.yaml`
(e.g. `world.climate.rainfallMmPerHour`) and `volcano.<id>.<path>` for `volcanoes/<id>.yaml` (e.g.
`volcano.kilauea.magma.chamber.supplyRate`). Every numeric or boolean value of the definitions is a
parameter, except volcano identity and geometry (`id`, chamber centre, vents, edifice) and world
values that would need a new world (grid, geology, seed, terrain generator).

Whether a change can be applied to the running simulation is the rule of the engine's
`ConfigChanges` (the same one that applies when YAML is edited by hand between runs):

- `apply: "hot"` (climate, time compression, magma supply rate and the properties of supplied
  magma, feature rates, solver tuning, …): the server saves the session, writes the YAML and reopens
  the world keeping all state.
- `apply: "restart"` (chamber size and depth, roof strength, initial magma, conduit and dike
  parameters, …): refused with `error{badRequest}` unless `restart:true`; then the affected volcanoes
  restart from their definitions (`World.ChangePolicy.RESET_CHANGED`). Terrain, deposits and other
  volcanoes are kept.

Values are validated by parsing the new definitions before anything is written; if the world cannot
be reopened, the files are restored. On success: `ack{ok:true, message:"Applied n changes" |
"Restarted with n changes"}`, then every client attached to the session gets a full attach burst
(the engine was rebuilt) ending with the new `schema`. In-memory sessions reply
`error{unsupported}`.

Defaults are the definitions as they were before the first change (`<world>/tuning/baseline.json`);
each change is appended to `<world>/tuning/audit.json` (last 200).

### 3.6 Inspection

`{"type":"inspect","requestId":31,"x":1200,"y":-450}` asks what the simulation knows about the
column containing the point (x, y) (metres, §1). It is answered on the engine thread with:

```json
{"type":"inspection","requestId":31,"at":[1205,-455],"column":"120,45","inside":true,
 "surfaceZ":1020.0,"surfaceMaterial":"BASALT","layerCount":5,
 "layers":[{"top":1020,"bottom":1019.2,"material":"BASALT","unit":7,"depositType":"lava",
            "label":"kilauea #1 lava","porosity":0.12,"loose":false}, …],
 "groundTemperatureC":164,
 "water":{"tableZ":600.1,"tableDepthM":419.9,"surfaceWaterDepthM":0,"vadoseM":419.9,"steamFluxKgPerSm2":2e-7},
 "temperatureProfile":[{"depthM":5,"temperatureC":120,"steam":0.4}, …],
 "lava":{"thicknessM":4.2,"temperatureC":1136,"crustM":0.21},
 "pdc":{"depthM":…,"speedMPerS":…,"temperatureC":…},"lahar":{…}}
```

| field | meaning |
|---|---|
| `at`, `column` | Centre of the column (m) and its cell indices. |
| `inside` | False outside the simulated area; nothing else follows then. |
| `layers` | Stratigraphy top down (at most 24; `layerCount` is the total): `top`/`bottom` (m), `material`, `unit` (§4.2), `depositType?`, `label?`, `porosity`, `voidFraction?`, `loose?`. |
| `water` | Groundwater: table elevation and depth, standing water, unsaturated thickness, steam flux. Absent where the subsurface model has no data. |
| `temperatureProfile` | One entry per subsurface level: centre depth below ground (m), temperature, steam fraction. |
| `groundTemperatureC` | Surface temperature from the geothermal model. |
| `lava`, `pdc`, `lahar` | Present only where that flow currently covers the column. |

Missing `x`/`y` gives `error{badRequest}`. The visualizer sends `inspect` when the user selects a
point or an entity, and again every 2 s while the simulation runs.

## 4. Server → client JSON messages

### 4.1 `welcome`, `sessions`, `attached`

```json
{"type":"welcome","protocol":1,"server":"typhon-sim-server/1.0","fields":[1,2,3,4,5,6,7,8,9,10,11,12]}
{"type":"sessions","sessions":[{"id":"s1","name":"Kīlauea","preset":"kilauea","world":"my-kilauea","time":3600.0,
  "mode":"REALTIME","speed":20,"rate":19.6,"replay":false,"clients":1,
  "volcanoes":[{"id":"kilauea","alert":"ERUPTING","erupting":true,
                "timeCompression":{"dormant":5000,"eruptive":20,"current":20}}]}],
 "server":{"maxSessions":8,"cpus":16,"heapUsedMB":812,"heapMaxMB":6144,"worldsDir":"/srv/worlds"}}
{"type":"catalog","presets":[{"name":"kilauea","title":"Kīlauea-like shield","description":"…","realScale":false}],
 "worlds":[{"name":"my-kilauea","title":"Kīlauea","volcanoes":1,"hasState":true,
            "timeCompression":{"dormant":5000,"eruptive":20},"sessionId":"s1"}],
 "server":{ … }}
{"type":"attached","sessionId":"s1","world":{ …WorldInfo… }}
{"type":"detached","sessionId":"s1","reason":"closed"}
```

In `sessions`, `world` is the world directory name (absent for in-memory sessions); the per-volcano
summary is refreshed about once a second. In `catalog`, `sessionId` marks worlds that are loaded and
`error` worlds whose definitions cannot be read. `detached` tells the clients attached to a session
that it was closed; they should attach elsewhere.

`fields` lists the field ids the server can stream. This is the capability mechanism: a field
missing from `fields` is not modelled by this server. Servers ignore such ids in `subscribe`;
clients should treat them as unavailable rather than as zero. Within an advertised field, columns a
session does not model (e.g. outside its subsurface model) carry `NaN` for `WaterTableDepth` and 0
for `SteamFraction`; section pixels for unmodelled quantities are 0 (saturation, steam) or `NaN`
(water table). `WorldInfo`:

| field | type | meaning |
|---|---|---|
| `name` | string | Display name. |
| `origin` | XY | World coordinates of the south-west corner of tile (0, 0). |
| `cellSize` | number | Surface column size `dxS` (m). |
| `tileSize` | int | Columns per tile edge (`T`); tiles are `T × T`. The engine's `ColumnStacks` tiles are 32×32; the server may stream 32 or 64. |
| `tiles` | `{minTx,minTy,maxTx,maxTy}` | Inclusive tile range of the world. |
| `seaLevel` | number | m. |
| `elevationRange` | `[min,max]` | Initial elevation range (m), for colour ramps. |
| `volcanoes` | `VolcanoInfo[]` | `{id, name, vents: VentInfo[], chamber: {center: [x,y,z], radius}}`. |
| `materials` | `MaterialInfo[]` | `{id, name, color: "#rrggbb", kind}`. `kind` is one of `rock`, `tephra`, `soil`, `ice`, `void`, `water`, `magma`. Ids are those used in sections. |
| `depositTypes` | `DepositTypeInfo[]` | `{id, name, color}`, for the engine's deposit types (LAVA, FALL, PDC, LAHAR, TUBE_ROOF, INTRUSION, BASEMENT, FILL, …). |
| `lod` | `LodInfo` | The tile pyramid (§5.5): `{levels: [{level, cellSize, tiles: TileBounds, fields: FieldId[], kind: "context" \| "detail"}], extent: [x0, y0, x1, y1]}`. `extent` is the whole landscape the server describes (m), the simulated core included. |

`VentInfo` is `{id, kind: "crater" | "fissure", at, z, radius, line?: [XY, XY]}`.

### 4.2 `units`

`{type:"units", units: UnitInfo[], replace: boolean}`. This is the stratigraphic unit table (the
engine's `UnitTable`). `replace:true` sends the full table (on attach and load); afterwards the server
sends newly created units with `replace:false`. `UnitInfo` is
`{id, volcanoId | null, depositType, eruption | null, time | null, label}`, where `time` is the
emplacement time (s) and `null` means pre-existing geology. The `TopUnit` field and section pixels
refer to these ids.

### 4.3 `clock`

```json
{"type":"clock","time":5025.35,"step":100507,"baseStep":0.05,"mode":"REALTIME","speed":20,"rate":19.8,"replay":false}
```

- `time` is simulation time (s); `step` is `runner.completedStep()`; `baseStep` is the engine base
  step (s).
- `speed` is the requested REALTIME multiplier. `rate` is the measured simulated seconds per wall
  second.
- Send at least twice per second, and immediately after transport changes.
- Clients extrapolate `time + rate · Δwall` between clocks while not `PAUSED`.
- `compression` (optional) is the first volcano's current time compression: physical (volcano)
  seconds per simulated second, its dormant or eruptive value. `physicalTime` (optional) is the
  approximate physical time elapsed since the session was loaded (simulated time × compression,
  integrated). Three different rates are in play: simulated time (`time`), volcano time
  (`time × compression`) and playback (`speed`/`rate`, simulated seconds per wall second).

### 4.4 `state`

This is a snapshot of 0D state per volcano (from `runner` snapshots), sent ≥ 2 Hz:

```json
{"type":"state","time":5025.35,
 "world":{"rainMmPerHour":0,"wind":{"speed":8,"bearingDeg":250}},
 "volcanoes":{"fuji":{
   "chamber":{"overpressureMPa":12.1,"tensileStrengthMPa":15,"temperatureC":1150,"silicaWt":50,
              "waterWt":0.6,"crystalFraction":0.08,"eruptionRate":0,"regime":"NONE"},
   "seismic":{"rsam":42.0,"vtPerMinute":3.1,"lpPerMinute":0.2,"tremor":false,"swarm":false},
   "alert":{"level":"MAJOR_ACTIVITY","style":"HAWAIIAN"},
   "deformation":{"maxUpliftM":0.012,"stations":[{"id":"SUMM","at":[300,200],"east":0.001,
                  "north":0.0004,"up":0.009,"tiltX":1.2,"tiltY":-0.4}]},
   "plume":{"topZ":9200,"massRateKgS":2.1e6}}}}
```

- `eruptionRate` is DRE m³ per **simulated** second (it includes the eruptive time compression);
  `physicalEruptionRate` is DRE m³/s of volcano time and is what a status display should show.
- `ruptureOverpressureMPa` is the wall-rupture limit the chamber's overpressure never exceeds;
  `failureOverpressureMPa` is where the conduit/roof fails and eruptions or dikes start. Show
  "pressure % of limit" against `failureOverpressureMPa`.
- `regime` is one of `NONE`, `FOUNTAINING`, `OPEN_VENT`, `EFFUSIVE`, `DOME`, `EXPLOSIVE`, `SURTSEYAN`: a descriptor
  derived from the conduit flow (never an input).
- `alert.level` is one of the six alert levels; `alert.style` is the eruption style *estimated* from what the
  eruption does (`HAWAIIAN`, `STROMBOLIAN`, `VULCANIAN`, `PELEAN`, `PLINIAN`, `LAVA_DOME`, `SUBPLINIAN`, `SURTSEYAN`,
  `PHREATIC`, `MIXED`), or `null` before the first estimate. `alert.vei` is the estimated VEI and
  `alert.styleForecast` is true while no eruption is running (the style is then a forecast for the next one).
- Station displacements are in metres and tilt in µrad.
- `plume` is present only while an eruption column exists.
- `geomorph` (optional) is `{failures, failedM3, avalanches, craters, maxCraterRadiusM, calderaSubsidenceM}`:
  landscape change since the volcano started — slope failures of every size (including the ravelling
  not reported one by one), how many became avalanches or debris flows, explosion craters and caldera
  subsidence (3 significant digits).
- `chamber.volumeM3` (optional) is the chamber's magma volume (m³), e.g. for previewing how an
  injection mixes in.
- `timeCompression` (optional) is `{dormant, eruptive, current}` for the volcano, and
  `physicalTime` (optional) its approximate elapsed volcano time (s), as in `clock`.

### 4.5 `events`

`{type:"events", events: SimEvent[], dropped: number}`. These are the engine's events, translated.
`dropped` counts events lost in the runner's event ring since the previous `events` message.

On attach the server sends a backlog, in time order up to the session time: every **milestone**
(`eruptionStarted`, `eruptionEnded`, `alertChanged`, `regimeChanged`, `styleEstimated`, `dikeStarted`,
`dikeStalled`, `fissureOpened`, `message`, the first `oceanEntry` and the first `geothermalFeature` of
each feature type per volcano; the last 4000), the most recent ~1500 other non-seismic events, and the
most recent ~600 seismic events and bombs. Milestones are kept apart from the rolling event log so a
late client always learns that an eruption started, however many quakes and plume updates followed. Every event has `kind` and `time` (s):

| kind | fields | engine source |
|---|---|---|
| `seismic` | `volcanoId`, `type` (`VT`/`LP`/`TREMOR`/`EXPLOSION`), `magnitude`, `hypocenter` [x,y,z], `durationSeconds`, `swarm` | `SeismicEvent` |
| `eruptionStarted` | `volcanoId`, `cause` (`AUTOMATIC`/`FORCED`/`DIKE`), `ventIds` | `MagmaEvents.EruptionStarted` + coupler vents |
| `eruptionEnded` | `volcanoId`, `eruptedVolumeM3`, `cause` (`AUTOMATIC`/`FORCED`/`SEALED`) | `MagmaEvents.EruptionEnded` |
| `ventState` | `volcanoId`, `ventId`, `previous`, `state` (`idle`/`active`/`waning`/`frozen`/`sealed`/`removed`), `feederWidthM?` (fissures) | `VentEvents.VentStateChanged`: a fissure's feeder froze or is narrowing, a vent started or stopped erupting, or the user sealed/unsealed/removed it |
| `alertChanged` | `volcanoId`, `previous` (or null), `current` | `AlertEvents.AlertLevelChanged` |
| `regimeChanged` | `volcanoId`, `regime` | eruptive-regime change |
| `styleEstimated` | `volcanoId`, `previous`, `current`, `vei`, `forecast`, `probabilities` | the estimated eruption style or VEI changed (`probabilities`: style → probability) |
| `dikeStarted` | `volcanoId`, `dikeId`, `origin` [x,y,z], `overpressureMPa` | `DikeEvents.DikeStarted` |
| `dikeAdvanced` | `volcanoId`, `dikeId`, `path` [[x,y,z]…] (full path so far) | `DikeEvents.DikeAdvanced` |
| `dikeStalled` | `volcanoId`, `dikeId`, `tip` [x,y,z], `depthM`, `volumeM3`, `reason` (`INSUFFICIENT_PRESSURE`/`FROZE`) | `DikeEvents.DikeStalled` (was a `message` before) |
| `fissureOpened` | `volcanoId`, `vent: VentInfo` | `DikeEvents.FissureOpened` |
| `bombLaunched` | `volcanoId`, `id`, `start`, `velocity` (m/s), `dragK`, `flightSeconds`, `landing` | `TephraEvents.BombLaunched` |
| `plume` | `volcanoId`, `base`, `topZ`, `radius`, `massRateKgS` | `TephraEvents.PlumeColumn` |
| `lightning` | `volcanoId`, `at` | `TephraEvents.VolcanicLightning` |
| `massFlowFront` | `volcanoId`, `flow` (`PDC`/`LAHAR`/`DEBRIS_AVALANCHE`), `cells` [XY…] (≤ 256), `speed`, `temperatureC` | `PdcFront` / `LaharFront` |
| `geothermalFeature` | `volcanoId`, `feature` (e.g. `FUMAROLE`, `GEYSER`, `HOT_SPRING`, `SULFUR_SPRING`, `MUD_POT`, `SULFUR_DEPOSIT`, `SUBMARINE_VENT`), `at` | `GeyserFormed` / `HydrothermalFeatureFormed` |
| `slopeFailure` | `volcanoId`, `at` [x,y,z], `volumeM3`, `style` (`TALUS`/`DEBRIS_AVALANCHE`/`DEBRIS_FLOW`), `trigger` (`OVERSTEEPENING`/`ALTERATION`/`THERMAL`/`PORE_PRESSURE`/`SEISMIC`), `factorOfSafety` | `GeomorphEvents.SlopeFailure`, for failures above the reporting volume; the small ravelling of steep fresh slopes is only summed in the volcano's `geomorph` state. Avalanches and debris flows are milestones of the backlog. |
| `craterExcavated` | `volcanoId`, `at`, `radiusM`, `depthM` | `GeomorphEvents.CraterExcavated`: an explosion dug or enlarged a crater (the first per volcano is a milestone). |
| `calderaCollapse` | `volcanoId`, `at`, `radiusM`, `subsidenceM` (total so far) | `GeomorphEvents.CalderaCollapse`: the chamber roof sinking as a piston (the first per volcano is a milestone). |
| `oceanEntry` | `at`, `powerMW`, `littoralExplosion` | `LavaOceanEntry` |
| `message` | `text` | free-form server notices |

Clients draw ballistic bombs from `start`, `velocity` and `dragK` (`a = g − k|v|v`). The landing point
is authoritative.

### 4.6 Other server messages

| type | fields |
|---|---|
| `ack` | `requestId`, `ok`, `message?` |
| `error` | `code` (`protocol`, `badRequest`, `noSession`, `unknownVolcano`, `unsupported`, `internal`), `message`, `requestId?` |
| `replayInfo` | `start`, `end` (s): the recorded range; `keyframes`: times (s) of stored keyframes |
| `replayReset` | `time`: the session jumped to `time` (seek, replay exit or load). Clients drop history newer than `time`; fresh tiles, state and events follow. |

### 4.7 `schema`

The tunable parameters and command fields of a session (§3.5):

```json
{"type":"schema","sessionId":"s2","tunable":true,
 "params":[
  {"id":"volcano.kilauea.magma.chamber.supplyRate","label":"Magma supply rate","unit":"m³/s",
   "group":"Kīlauea · Magma supply","type":"number","min":0,"max":100,"log":true,
   "value":0.2,"default":0.15,"apply":"hot","volcanoId":"kilauea",
   "help":"Magma rising into the chamber from below. Kīlauea ~0.1–0.2 m³/s."},
  {"id":"volcano.kilauea.magma.chamber.volume","label":"Chamber volume","unit":"m³", …,"apply":"restart"}],
 "commands":{"injectMagma":[
  {"id":"volumeM3","label":"Volume","unit":"m³","group":"Batch","type":"number","min":1000,"max":1e10,"log":true,"default":5e6,"apply":"hot"},
  {"id":"temperatureC","label":"Temperature","unit":"°C","group":"Magma","type":"number","min":650,"max":1350,"default":1150,"apply":"hot"}, …]},
 "audit":[{"at":1791000000000,"simTime":3600,"id":"volcano.kilauea.magma.chamber.supplyRate",
           "label":"Kīlauea: Magma supply rate","from":0.15,"to":0.2,"apply":"hot"}]}
```

| field | meaning |
|---|---|
| `tunable` | False for in-memory sessions (`reason` says why); `params` is then empty but `commands` is still filled. |
| `params[]` | `ParamSpec`: `id`, `label`, `unit?`, `help?`, `group` (heading), `type` (`number`/`boolean`/`choice`), `min?`/`max?` (validated by the server), `step?` (1 for integers), `log?` (slider hint), `choices?`, `value`, `default`, `apply` (`hot`/`restart`), `volcanoId?`. `recommended?: {min?, max?}` is the physically sensible part of the range with `warning` explaining what goes wrong outside it; `outOfRange: true` marks a current value outside it. `auto: true` marks a value the engine computes from physics unless overridden (wall rupture limit, wall yielding): `value` is null or absent while computed, `computed` is the value in use, a number overrides it and `null` in `setParams` returns it to computing (the audit records it as `"auto"`). Out-of-range values are accepted, and the `setParams` ack message then ends with `Warning: …`. Parameters without curated metadata get a label and unit derived from their name and no range. |
| `commands` | Fields of commands, as `ParamSpec`s with defaults (`injectMagma`: the first volcano's recharge magma; `injectMagma@<volcanoId>`: the same fields with that volcano's own recharge magma as defaults; `volumeM3.recommended.max` is 10 % of the chamber volume — larger batches rupture the chamber walls, and the command's ack carries a note saying so). |
| `audit` | Recent changes, oldest first: `at` (wall ms), `simTime`, `id`, `label`, `from`, `to` (null = back to default), `apply`. |

### 4.8 `entities`

Things in the world with a place and a lifetime, so clients can show where something appeared and
when it went away:

```json
{"type":"entities","time":5400,"replace":false,
 "upsert":[{"id":"feature:kilauea:HOT_SPRING:212:340","kind":"feature","volcanoId":"kilauea",
            "label":"Hot spring","at":[2120,-3400,980],
            "props":{"feature":"HOT_SPRING","groundTemperatureC":86},
            "createdAt":5100,"updatedAt":5400},
           {"id":"dike:kilauea:3","kind":"dike","volcanoId":"kilauea","label":"Dike 3",
            "at":[150,-90,-200],"path":[[0,0,-2500],[150,-90,-200]],
            "props":{"status":"PROPAGATING","tipDepthM":1210,"openingM":1.2,"speedMPerS":0.4, …},
            "createdAt":5390,"updatedAt":5400}],
 "remove":["quake:kilauea:3600:…"]}
```

- `replace:true` (on attach, after a replay jump or a restart) carries the full set: clients drop
  everything they had. Otherwise the message is a delta: entities that appeared or changed
  (`upsert`) and ids that are gone (`remove`). Deltas are sent at most once a second and only when
  something changed.
- `id` is stable for the entity's life; `createdAt` (simulated s) is when the server first saw it,
  `updatedAt` when its record last changed. `at` is a representative point [x, y, z] in metres
  (§1); `path?` is extra geometry (dikes: origin → tip). `hidden:true` marks statistics-only
  entities that are not drawn. `props` are kind-specific and may grow (unknown keys are ignored).

| kind | id | lifetime | props |
|---|---|---|---|
| `chamber` | `chamber:<volcano>` | always | `overpressureMPa`, `tensileStrengthMPa`, `temperatureC`, `silicaWt`, `waterWt`, `crystalFraction`, `volumeM3`, `depthM`, `eruptionRateM3PerS`, `regime`, `styleEstimate?`, `vei`, `radiusM`, `dikesBlocked?` (when dikes are simulated) |
| `vent`, `fissure` | `vent:<volcano>:<ventId>` | while the vent exists (fissures appear when a dike breaks the surface and disappear when removed) | `ventId`, `shape`, `craterRadiusM`, `lengthM?`, `strikeDeg?` (clockwise from east), `erupting`, `state` (`idle`/`active`/`waning`/`frozen`/`sealed`), `sealed`, `fluxM3PerS` (DRE through this vent, 2 significant digits), fissures only: `feederWidthM` (widest open feeder segment, cm precision; 0 once frozen), `segmentsOpen`, `segmentsTotal` |
| `dike` | `dike:<volcano>:<n>` | from nucleation until removed or the engine forgets it (it keeps the latest few, stalled or erupted) | `status` (`PROPAGATING`/`STALLED`/`ERUPTED`), `startedAt`, `tipDepthM`, `heightM`, `openingM`, `strikeLengthM`, `speedMPerS`, `volumeM3`, `fissure?` |
| `feature` | `feature:<volcano>:<KIND>:<x>:<z>` | while the geothermal model keeps the feature | `feature` (`HOT_SPRING`, `GEYSER`, `FUMAROLE`, `MUD_POT`, `SULFUR_SPRING`, `SUBMARINE_VENT`, `SULFUR_DEPOSIT`, `ACID_ALTERATION`, `SINTER`, `CINNABAR`), `groundTemperatureC` (whole degrees), `level?` |
| `plume` | `plume:<volcano>` | while an eruption column stands | `topZ`, `heightM`, `massRateKgS` |
| `station` | `station:<volcano>:<name>` | always | `station` |
| `quake` | `quake:<volcano>:<ms>:<pos>` | M ≥ 2, for 30 simulated minutes (newest 100) | `magnitude`, `type`, `time`, `durationSeconds`, `swarm` |
| `lavaFront` | `lava:front` | 2 simulated minutes after the last front report | `lengthM`, `activeCells`, `moltenVolumeM3` |
| `lavaField` | `lava:field` (hidden) | while lava is molten | `activeCells`, `moltenVolumeM3`, `emittedM3`, `solidifiedM3` |
| `pdc`, `lahar` | `pdc:<flow>`, `lahar:<flow>` | 2 simulated minutes after the last front report | `runoutM`, `volumeM3`, `maxSpeedMPerS`, `maxTemperatureC`/`sedimentFraction`, `activeCells` |

## 5. Tile frames (binary, kind 1)

### 5.1 Layout

A tile frame carries one field of one tile of `width × height` columns. Values are row-major. Row `r`
is the `r`-th row from the south (`y` increasing) and column `c` is the `c`-th column from the west
(`x` increasing). Column `(c, r)` of tile `(tx, ty)` covers world
`x ∈ origin.x + [(tx·T + c)·cellSize, (tx·T + c + 1)·cellSize)`, and likewise for `y`.

| offset | type | field |
|---|---|---|
| 0 | u8 | frame kind = 1 |
| 1 | u8 | frame version = 1 |
| 2 | u16 | reserved (0) |
| 4 | u16 | field id (§5.2) |
| 6 | u8 | codec id (§5.3) |
| 7 | u8 | flags: bit 0 = payload zlib-compressed |
| 8 | i32 | tile x |
| 12 | i32 | tile y |
| 16 | u32 | version: monotonic per (field, tile) |
| 20 | u16 | width (columns) |
| 22 | u16 | height (rows) |
| 24 | f32 | param0 (codec-specific) |
| 28 | f32 | param1 (codec-specific) |
| 32 | f64 | simulation time of the data (s) |
| 40 | u32 | payload length in bytes (after compression) |
| 44 | … | payload |

Clients keep the frame with the highest `version` per (field, tile) and discard older ones. Servers
bump the version only when the *encoded* values change, so float drift below codec precision does
not resend tiles.

### 5.2 Fields

| id | name | unit | recommended codec |
|---|---|---|---|
| 1 | SurfaceElevation | m above datum, incl. deformation | 1 ElevationU16CmDelta |
| 2 | LavaDepth | molten thickness, m | 2 DepthU16MmSparse |
| 3 | LavaTemperature | °C (where lava > 0) | 4 TemperatureU8Log |
| 4 | WaterDepth | surface water depth, m (sea, lakes, poured water) | 6 F32Raw (sea can exceed 65.535 m) |
| 5 | PdcDepth | m | 2 |
| 6 | LaharDepth | m | 2 |
| 7 | AshDepth | tephra-fall deposit thickness, m | 2 |
| 8 | SurfaceTemperature | top subsurface cell, °C | 4 |
| 9 | WaterTableDepth | m below surface; negative = above ground (spring) | 6 |
| 10 | TopUnit | unit id of the top layer (§4.2) | 5 U16Raw |
| 11 | Uplift | cumulative vertical deformation since session start, m | 6 |
| 12 | SteamFraction | top subsurface cell, 0..1 | 7 U8Linear (0, 1) |

The server may use any codec for any field. Clients decode by the codec id in the frame.

### 5.3 Codecs

Let `n = width × height` and `v[i]` be the values. All of these describe the payload *before*
compression.

| id | name | payload | decode |
|---|---|---|---|
| 1 | ElevationU16CmDelta | `param0 = min(v)`. `cm[i] = clamp(round((v[i] − param0)·100), 0, 65535)`. Payload: `n × u16` of `d[i] = (cm[i] − cm[i−1]) mod 65536` with `cm[−1] = 0`. | `cm = (cm + d[i]) mod 65536`; `v = param0 + cm/100`. Range is 655.35 m above the tile minimum. |
| 2 | DepthU16MmSparse | `u32 count`, then `count × (u16 index, u16 mm)` for cells with `v > 0.0005` m, with `mm = min(65535, round(v·1000))`. Indices ascending. | Absent cells are 0. Requires `n ≤ 65536`. |
| 3 | DepthU16MmDense | `n × u16` millimetres. | `v = mm/1000`. |
| 4 | TemperatureU8Log | `param0 = Tmax` (°C, default 1300). `u8 = round(255·ln(1+clamp(T,0,Tmax))/ln(1+Tmax))`. | `T = exp(u8/255·ln(1+Tmax)) − 1`. Error is ≤ ~3 % of T. |
| 5 | U16Raw | `n × u16`. | integer values |
| 6 | F32Raw | `n × f32`. | exact |
| 7 | U8Linear | `param0 = min`, `param1 = max`. `u8 = round(255·clamp((v−min)/(max−min), 0, 1))`. | `v = min + u8/255·(max−min)` |

### 5.4 Streaming and flow control

- After `subscribe`, the server streams every subscribed (field, tile) the client does not hold at its
  current version, nearest the volcanoes first. Afterwards it sends tiles whose version changed.
- Servers should re-check slowly varying fields less often. The mock uses SurfaceElevation 1 s,
  LavaDepth / PdcDepth 0.25 s, Ash / TopUnit 2 s, SurfaceTemperature 3 s, WaterTable / Uplift /
  Steam 4 s.
- **Credit window.** The server counts tile frames sent since `subscribe` (`sent`) and the latest
  `flow.tilesProcessed` (`acked`). It sends a tile only while `sent − acked < TILE_WINDOW` (48).
- Clients send `flow` after applying each batch of tiles (at most once per animation frame).
- This bounds how many tile bytes can queue ahead of JSON messages and section replies, so a slow
  client stays responsive. Servers should additionally skip tiles while the socket's pending bytes
  exceed ~128 KiB.
- Tiles are never dropped, only delayed. A tile skipped now is still pending and goes out later at its
  newest version.

### 5.5 Levels (multi-resolution)

The core's columns are level 0. Around them the server offers a pyramid of extra levels, listed in
`WorldInfo.lod` and streamed only to clients that ask for them (`subscribe.levels`):

- **Coarse context levels `ℓ = 1…K`** — cells of `2^ℓ` columns. Level ℓ covers `2^ℓ` core half-widths
  around the core's centre (a clipmap), clamped to `lod.extent` (by default 30 km at real scale, at
  least 6 km and four core widths for compact worlds), so each level has about as many cells as the
  core has columns. Inside the core a coarse cell is the **mean** of its live columns, so volumes per
  area (lava, ash, PDC, lahar depth) and elevations are conserved between levels; outside, it is the
  static context terrain (the generator's continuous surface, or the nearest core edge for DEMs) and,
  for `AshDepth`, the tephra deposit where the ash grid reaches. Fields: 1, 2, 5, 6, 7, 11.
- **Fine detail levels `ℓ < 0`** — cells of `2^ℓ` columns (1–5 m) over each volcano's crater-resolving
  region (volcano YAML `detail: {radiusM, metersPerCell}`; default eight crater radii, 150–1500 m).
  A detail cell's elevation averages to its column's, so the fine surface adds the shape of craters,
  rims and ponds without volume and without seams; outside the region a fine tile repeats its
  columns. Field: 1.

All levels share `origin` and `tileSize`: level-ℓ cell `(i, j)` covers
`x ∈ origin.x + [i·c, (i+1)·c)`, `y ∈ origin.y + [j·c, (j+1)·c)` with `c = cellSize·2^ℓ`, and tile
`(tx, ty)` holds cells `i = tx·T + col`, `j = ty·T + row`. Tile indices can be negative (context west or
south of the core). A tile frame carries its level as an **i16 at header offset 2** (0 for level 0, the
value every protocol-1 frame already had there).

Rendering: draw the coarsest level for the whole `extent`, then each finer level over its tiles, with
level 0 over the core and detail levels over the craters; where a finer tile is present it replaces
the coarser one. Elevation tiles spanning more than 655 m use codec 6 (F32Raw) instead of codec 1.
Flow control is shared with level 0 (one credit window): level 0 goes first, then the coarse levels
(coarsest first), then detail.

## 6. Section frames (binary, kind 2)

This is the reply to a `section` request. The section samples a vertical curtain along the
horizontal polyline. Column `i ∈ [0, nu)` is centred at distance `(i + 0.5)·length/nu` along the
polyline. Row `k ∈ [0, nz)` is centred at `z = zMin + (k + 0.5)·(zMax − zMin)/nz`, so row 0 is the
bottom. The pixel index is `p = k·nu + i`.

Header:

| offset | type | field |
|---|---|---|
| 0 | u8 | frame kind = 2 |
| 1 | u8 | frame version = 1 |
| 2 | u16 | reserved |
| 4 | u32 | requestId |
| 8 | u16 | nu |
| 10 | u16 | nz |
| 12 | u8 | flags: bit 0 = body zlib-compressed |
| 13 | 3 bytes | reserved |
| 16 | f64 | simulation time (s) |
| 24 | u32 | body length (after compression) |
| 28 | … | body |

Body (after decompression), in order:

| part | encoding |
|---|---|
| meta length | u32 byte length `L` |
| meta | `L` bytes UTF-8 JSON `SectionMeta` (below) |
| surfaceZ | `nu × f32`: ground surface (m) per column, including molten lava |
| waterTableZ | `nu × f32`: water-table elevation (m) per column, `NaN` if none |
| material | `nu·nz × u8`: material id (`WorldInfo.materials`) |
| unit | `nu·nz × u16`: unit id (§4.2), 0 where not applicable |
| temperature | `nu·nz × u8`: TemperatureU8Log with Tmax = 1300 |
| saturation | `nu·nz × u8`: water saturation 0..1 as U8Linear(0,1) |
| steam | `nu·nz × u8`: steam fraction 0..1 as U8Linear(0,1) |
| flags | `nu·nz × u8`: bit 0 void (tube, cave, dug pit), bit 1 water body, bit 2 magma or melt, bit 3 air |

`SectionMeta`:

```json
{"requestId":7,"length":9200.5,"zMin":-6000,"zMax":2300,
 "units":[{"id":12,"volcanoId":"fuji","depositType":1,"eruption":3,"time":5100.0,"label":"fuji #3 lava"}],
 "overlays":[
   {"kind":"chamber","u":4600,"z":-4000,"rx":1200,"rz":670,"temperatureC":1150},
   {"kind":"conduit","u":4600,"zTop":1850,"zBottom":-3330,"width":30,"active":true},
   {"kind":"dike","points":[[4400,-3500],[4300,-1200]],"active":true}]}
```

- `datum` echoes the request (`"absolute"` when omitted). With `"surface"`, `zMin`/`zMax`, row
  elevations, `surfaceZ` and `waterTableZ` are metres relative to each column's own ground (negative
  below ground; `surfaceZ` is then the molten-lava thickness, ≥ 0), so a shallow window such as
  −20…+3 m shows deposits of a few metres at true thickness whatever the relief. Overlays are empty
  in that mode.
- `units` lists the units that appear in the section.
- Overlays use `u`, the distance along the polyline (m), and `z`. They are projections of 3D
  objects within a reasonable distance of the line: chambers within ~2 radii and dikes within ~1.5 km.
- Section requests are answered in request order. Clients ignore replies whose `requestId` is
  older than the latest one they sent.

## 7. Replay

- The server records the session while it runs:
  - **Keyframes:** a full engine save every *N* simulated minutes. The engine's region-file save
    format is fine for this; incremental saves keep them cheap.
  - **Deltas:** the event stream and the tile frames between keyframes, as sent to clients.
- `replayInfo{start, end, keyframes}` advertises the range.
- `replay{action:"enter"}` freezes the live run. Transport and commands are refused with
  `error{unsupported}` until exit.
- `seek{time}` restores the latest keyframe `≤ time`. With deltas, the server then replays to exactly
  `time`; otherwise it lands on the keyframe. Then it sends:
  - `replayReset{time}`,
  - the state, an events backlog, a `clock` with `replay:true`,
  - every subscribed tile again with fresh versions.
- `replay{action:"exit"}` returns to the live state with `replayReset{time: liveTime}`.
- The mock implements keyframe-only replay with in-memory snapshots every 5 simulated minutes.

## 8. Versioning

- `PROTOCOL_VERSION` (handshake) changes on any incompatible change to messages or framing.
- The frame-version byte changes when a binary layout changes.
- New fields, codecs, event kinds or optional properties are compatible additions. Clients ignore
  what they do not know, so these do not bump the protocol version.

## 9. Implementation notes for M6 (Java)

The Java implementation is `sim-server/` (see its README). Notes from implementing it:

- Engine columns are integer `(x, z)` with `+z` south and blocks `L` metres tall; the server maps
  column `(cx, cz)` to `x ∈ [cx·L, (cx+1)·L)`, `y ∈ [−(cz+1)·L, −cz·L)` and elevations to
  `block·L`. Unit ids on the wire are engine unit ids + 1 (0 = none).
- Events are batched (~4 Hz). Every `events` message re-renders the client's event-driven views,
  so per-step messages swamp it.
- `geothermalFeature` is sent for point features only (fumaroles, geysers, springs, mud pots,
  sulfur deposits, submarine vents); diffuse alteration shows through `TopUnit`.
- Tiles are 64 columns when the world allows (fewer meshes for the client).

- **Runner.** Run the engine on `EngineRunner`. Use lossless frames for block changes (needed only
  by a Minecraft host) and the event ring for `events`. Build `state` from `runner.snapshot()` and
  `clock` from `completedStep()` and the base step.
- **Tiles.** Derive tiles from the world model: `ColumnStacks.surfaceZ` + uplift; lava, water, PDC
  and lahar surface fields; ash from FALL layers; subsurface top-cell temperature and steam; water
  table `hw`. Keep a per-(field, tile) version and the last encoded payload hash.
- **Compression.** `Deflater` with level 6 is sufficient; reuse a `Deflater` per thread.
- **Sections.** `WorldQuery.section(polyline, zMin, zMax, nu, nz, mask)` maps one-to-one onto §6.
- **Reference check.** Run the visualizer against your server with `VITE` pointed at it
  (`?server=ws://host:port/ws`). The codec tests in `visualizer/src/protocol/frames.test.ts` define
  exact round-trip expectations.
