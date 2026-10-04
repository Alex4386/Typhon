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
  listSessions                  ─────▶
                                ◀─────   sessions[…]
  attach{sessionId}             ─────▶   (or createSession{preset|world, seed})
                                ◀─────   attached{world}, units{replace:true}, state, events, replayInfo
  subscribe{fields}             ─────▶
                                ◀═════   tile frames (binary), throttled by flow control
  flow{tilesProcessed}          ─────▶   (repeatedly)
                                ◀─────   clock (≥ 2 Hz), state (≥ 2 Hz), events (as produced),
                                         units (when new deposits appear), replayInfo (~0.5 Hz)
```

If `hello.protocol` differs from the server's, it sends `error{code:"protocol"}` and closes. One
session is one engine (a world with N volcanoes) driven by an `EngineRunner`. Several clients may
attach to the same session; transport and commands from any client affect everyone.

## 3. Client → server messages

### 3.1 Connection and session

| type | fields | meaning |
|---|---|---|
| `hello` | `protocol: 1`, `client: string` | First message. |
| `listSessions` | — | Reply: `sessions`. |
| `createSession` | `preset?: string`, `world?: string`, `seed?: number` | Create and attach to a new session from a simulator preset or a `worlds/<name>` directory. Reply: `attached` (+ the attach burst). |
| `attach` | `sessionId: string` | Attach to an existing session. Reply: `attached` …, or `error{noSession}`. |
| `subscribe` | `fields: FieldId[]`, `bounds?: TileBounds` | Replace the set of streamed tile fields (§5.2). Optional inclusive tile bounds restrict streaming; omitted means all tiles. Resets flow-control counters (§5.4). |
| `flow` | `tilesProcessed: number` | Cumulative tile frames processed since the last `subscribe` (§5.4). |

### 3.2 Transport

Names follow `EngineRunner.Mode`.

| type | fields | server action |
|---|---|---|
| `transport` | `mode: "REALTIME" \| "UNBOUNDED" \| "PAUSED"`, `speed?: number` | `REALTIME` → `runner.realtime(speed)`, with `speed` clamped to 0.1–1000 and the current speed kept if omitted. `UNBOUNDED` → `runner.unbounded()`, which also remembers `speed`. `PAUSED` → `runner.pause()`. |
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
| `injectMagma` | `volcanoId`, `volumeM3` | Recharge pulse (`MagmaCommands.InjectRecharge`; the server picks the recharge composition). |
| `rain` | `mmPerHour` | World rainfall rate; 0 stops it. |
| `addWater` | `at: XY`, `volumeM3`, `seconds?` | Pour water at a point, released over `seconds` (default 600). This is surface-water input that can infiltrate to the water table (plan §3-1). |
| `dig` | `at: XY`, `radius` (m), `depth` (m) | Excavate a pit from the surface (`WorldEdit.carve`/`erode`). |
| `setWind` | `speed` (m/s), `bearingDeg` | Wind blowing **towards** `bearingDeg`, clockwise from north. |

### 3.4 Sections, saves, replay

| type | fields | reply |
|---|---|---|
| `section` | `requestId`, `polyline: XY[]` (≥ 2 points), `zMin`, `zMax` (m), `nu` (8–1024), `nz` (8–512) | A binary section frame (§6) with the same `requestId`, or `error{badRequest, requestId}`. |
| `save` | `name` | `ack` or `error`. Saves the session to `worlds/<world>/state` (plan §3-3). |
| `load` | `name` | Re-attaches all clients to the loaded state (full attach burst). |
| `replay` | `action: "enter" \| "exit"` | Enter or leave replay mode (§7). |
| `seek` | `time` (s) | In replay mode only: jump to the state at `time` (§7). |

## 4. Server → client JSON messages

### 4.1 `welcome`, `sessions`, `attached`

```json
{"type":"welcome","protocol":1,"server":"typhon-sim-server/1.0","fields":[1,2,3,4,5,6,7,8,9,10,11,12]}
{"type":"sessions","sessions":[{"id":"s1","name":"Kīlauea","preset":"kilauea","time":3600.0}]}
{"type":"attached","sessionId":"s1","world":{ …WorldInfo… }}
```

`fields` lists the field ids the server can stream. `WorldInfo`:

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

- `eruptionRate` is DRE m³/s.
- `regime` is one of `NONE`, `FOUNTAINING`, `OPEN_VENT`, `DOME`, `EXPLOSIVE`, `SURTSEYAN`.
- `alert.level` is one of the six alert levels; `alert.style` is the suggested eruption style.
- Station displacements are in metres and tilt in µrad.
- `plume` is present only while an eruption column exists.

### 4.5 `events`

`{type:"events", events: SimEvent[], dropped: number}`. These are the engine's events, translated.
`dropped` counts events lost in the runner's event ring since the previous `events` message.

On attach the server sends a backlog. It includes all non-seismic events that are still relevant, plus
at least the most recent ~500 seismic events. Every event has `kind` and `time` (s):

| kind | fields | engine source |
|---|---|---|
| `seismic` | `volcanoId`, `type` (`VT`/`LP`/`TREMOR`/`EXPLOSION`), `magnitude`, `hypocenter` [x,y,z], `durationSeconds`, `swarm` | `SeismicEvent` |
| `eruptionStarted` | `volcanoId`, `cause` (`AUTOMATIC`/`FORCED`/`DIKE`), `ventIds` | `MagmaEvents.EruptionStarted` + coupler vents |
| `eruptionEnded` | `volcanoId`, `eruptedVolumeM3` | `MagmaEvents.EruptionEnded` |
| `alertChanged` | `volcanoId`, `previous` (or null), `current` | `AlertEvents.AlertLevelChanged` |
| `regimeChanged` | `volcanoId`, `regime` | eruptive-regime change |
| `dikeAdvanced` | `volcanoId`, `dikeId`, `path` [[x,y,z]…] (full path so far) | `DikeEvents.DikeAdvanced` |
| `fissureOpened` | `volcanoId`, `vent: VentInfo` | `DikeEvents.FissureOpened` |
| `bombLaunched` | `volcanoId`, `id`, `start`, `velocity` (m/s), `dragK`, `flightSeconds`, `landing` | `TephraEvents.BombLaunched` |
| `plume` | `volcanoId`, `base`, `topZ`, `radius`, `massRateKgS` | `TephraEvents.PlumeColumn` |
| `lightning` | `volcanoId`, `at` | `TephraEvents.VolcanicLightning` |
| `massFlowFront` | `volcanoId`, `flow` (`PDC`/`LAHAR`), `cells` [XY…] (≤ 256), `speed`, `temperatureC` | `PdcFront` / `LaharFront` |
| `geothermalFeature` | `volcanoId`, `feature` (e.g. `FUMAROLE`, `GEYSER`, `HOT_SPRING`, `SULFUR_SPRING`, `MUD_POT`, `SULFUR_DEPOSIT`, `SUBMARINE_VENT`), `at` | `GeyserFormed` / `HydrothermalFeatureFormed` |
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
