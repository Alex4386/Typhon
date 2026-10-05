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
| Tile fields 1–8, 10, 11 (elevation, lava depth/T, water, PDC, lahar, ash, surface T, top unit, uplift) | implemented; surface T comes from the geothermal grid and lava |
| Tile fields 9 (water-table depth), 12 (steam fraction) | **unavailable** until the subsurface model (M4); not advertised in `welcome.fields` |
| Credit-window flow control, change-detected versions, version floor across loads | implemented |
| section (materials, units, flags, overlays) | implemented; temperature is a placeholder geotherm, saturation/steam/water table unmodelled |
| commands: startEruption, stopEruption, forceDike, injectMagma, setWind, rain, dig | implemented (`rain` needs lahars; `dig` lowers whole blocks) |
| command addWater | accepted and stored in the world model; it only flows/infiltrates once the surface-water model (M4) exists |
| save / load | implemented (`worlds-dir/<name>/state`; world sessions save into their own directory) |
| replay enter / seek / exit | keyframe-only (every 5 simulated min, in memory) for preset sessions; world sessions refuse `seek` |

## Tests

`./gradlew :sim-server:test`: `ProtocolTest` runs a real server on an ephemeral port and talks to
it with the JDK WebSocket client (handshake, attach burst, tile window, sections, commands,
transport, save/load). `GoldenFramesTest` writes `visualizer/src/protocol/golden-java.json`, which
the visualizer's `golden.test.ts` decodes and re-encodes byte-for-byte.

`visualizer/scripts/screenshot-server.mjs` takes screenshots of the visualizer against a running
server.
