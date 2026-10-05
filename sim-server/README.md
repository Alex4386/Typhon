# typhon-sim-server

Runs typhon-engine sessions and streams them to the web visualizer over WebSocket using protocol
v1 ([`docs/protocol.md`](../docs/protocol.md)).

```sh
./gradlew :sim-server:run --args="--preset kilauea"             # ws://localhost:8787/ws
./gradlew :sim-server:run --args="--world worlds/twin --port 8787"
# serve the production visualizer from the same port
(cd visualizer && npm install && npm run build)
./gradlew :sim-server:run --args="--preset kilauea --ui visualizer/dist"   # http://localhost:8787/
# or develop the visualizer against it
(cd visualizer && npm run dev)   # http://localhost:5180/?server=ws://localhost:8787/ws
```

Options: `--preset NAME [--seed N] | --world DIR`, `--port`, `--host`, `--worlds-dir` (where
`save`/`load` and `createSession{world}` look, default `worlds`), `--ui DIR`, `--speed` (initial
REALTIME multiplier, default 20), `--base-step-ms` (engine base step, default 50).

## Structure

| Class | Role |
|---|---|
| `SimServer` | Javalin WebSocket endpoint (`/ws`, subprotocol `typhon.v1`), message dispatch, pump thread (clock/state 4 Hz, events 4 Hz, replayInfo 0.5 Hz, tiles) |
| `Session` | One scenario (preset or world directory) on an `EngineRunner`; transport, commands, saves, keyframe replay, event log, unit table |
| `ClientConnection` | Per-client subscription and credit-window flow control |
| `FieldSampler` / `TileStore` | Sample fields per tile on the engine thread; encode, hash and version tiles |
| `SectionBuilder` | `WorldModel.section` raster + overlays (chamber, conduits, dikes) → section frame |
| `EventTranslator` / `Probe` | Engine events and state → protocol JSON (block coordinates → metres) |
| `protocol.Codecs` | Value codecs and frame layouts, byte-compatible with `visualizer/src/protocol/frames.ts` |

All reads of simulation state run on the engine thread between steps (`EngineRunner.onEngineThread`);
a replay keyframe is viewed on a separate executor.

## Protocol coverage

| Feature | Status |
|---|---|
| hello / welcome / listSessions / createSession (preset, world) / attach | implemented |
| transport (REALTIME×speed, UNBOUNDED, PAUSED), step, pauseAt | implemented |
| clock, state (chamber, seismic, alert, deformation stations, plume), events, units | implemented |
| Tile fields 1–12 | implemented. Water depth combines standing water and the subsurface model's surface water; surface T is the subsurface top cell (1 m) raised by geothermal heat and lava; water-table depth and steam (max over 2–80 m) come from the subsurface model (NaN / 0 outside it) |
| Credit-window flow control, change-detected versions, version floor across loads | implemented |
| section (materials, units, flags, overlays) | implemented; temperature, saturation, steam and water table from the subsurface model (conductive geotherm fallback outside it); chamber, conduits, dikes and hypocentres drawn at physical depth (see `GridMapping.stretchZ`) |
| commands: startEruption, stopEruption, forceDike, injectMagma, setWind, rain, dig, addWater | implemented. `rain` sets lahar rainfall and the subsurface rainfall (`Subsurface.SetRainfall`, persisted); `addWater` pours into the surface-water model, which spreads, infiltrates and recharges the water table; `dig` lowers whole blocks |
| save / load | implemented (`worlds-dir/<name>/state`; world sessions save into their own directory) |
| replay enter / seek / exit | keyframe-only (every 5 simulated min, in memory) for preset sessions; world sessions refuse `seek` |

## Tests

`./gradlew :sim-server:test`: `ProtocolTest` runs a real server on an ephemeral port and talks to
it with the JDK WebSocket client (handshake, attach burst, tile window, sections, commands,
transport, save/load). `GoldenFramesTest` writes `visualizer/src/protocol/golden-java.json`, which
the visualizer's `golden.test.ts` decodes and re-encodes byte-for-byte.

`visualizer/scripts/screenshot-server.mjs` takes screenshots of the visualizer against a running
server.
