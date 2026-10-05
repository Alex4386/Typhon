# Typhon visualizer

Web front-end for the Typhon volcano simulator. It connects to a sim-server over WebSocket using
**protocol v1** ([`docs/protocol.md`](../docs/protocol.md)). It renders the volcano in 3D, draws
cross-sections through the subsurface, shows observatory instruments, and drives the simulation
(transport, commands, replay).

It is independent of Gradle: plain Node ≥ 20 and npm.

```bash
cd visualizer
npm install
npm run dev:mock     # mock sim-server (ws://localhost:8787/ws) + Vite dev server (http://localhost:5180)
```

| script | what it does |
|---|---|
| `npm run dev` | Vite dev server only (connect to a real sim-server) |
| `npm run mock` | Mock sim-server only |
| `npm run dev:mock` | Both |
| `npm run build` | Typecheck + production build into `dist/` |
| `npm run typecheck` | `tsc -b --noEmit` |
| `npm test` | Codec round-trip tests (vitest) |

**Server URL.** The default is `ws://<page host>:8787/ws`. Override with
`?server=ws://host:port/ws`.

**Renderer.** The default is three.js `WebGPURenderer`, which falls back to its WebGL2 backend where
WebGPU is missing. `?renderer=webgl` forces the classic `WebGLRenderer`. The header shows which one
is active.

## The mock server

`mock/` is a **synthetic stand-in**, clearly marked as MOCK in the UI. It is not the simulator and
nothing in it is physically validated. It exists so the visualizer can be built before the Java
sim-server (M6). It speaks the full protocol:

- **World.** A 10 × 10 km island (512² columns of 20 m in 8×8 tiles of 64) with a stratovolcano and
  an explosive satellite cone.
- **Volcano dynamics.** Chamber pressure builds up and erupts. Dikes advance with VT swarms.
- **Surface flows.** Lava spreads on an 8-neighbour automaton and cools into new ground. Ash falls
  downwind. Column collapse sends PDC pulses.
- **Fields and events.** It also produces ground heat, a water table with springs and rain, Mogi-like
  uplift with GNSS stations, geothermal features, bombs, plume and lightning.
- **Protocol coverage.** Commands (erupt, stop, dike, inject, rain, pour water, dig, wind),
  cross-sections with stratigraphy units and overlays, change-detected tile streaming with credit
  flow control, and keyframe-only replay.

Debug helpers: `npx tsx mock/bench.ts` (cost of the synthetic world), `npx tsx mock/probe-client.ts`
(message stream), `npx tsx mock/section-probe.ts` (section round-trip).

## Layout

```
src/protocol/   protocol v1: field/codec ids, JSON message types, binary frame encode/decode (+ tests)
src/net/        WebSocket client: handshake, subscription, tile batching + flow control, commands
src/store/      zustand store (world, clock, 0D history, events, UI state) + tile store
src/camera/     camera rig (orbit / fly / walk / follow / tour, transitions, clearance, clip planes,
                URL + bookmarks), HUD (camera bar, compass readout, minimap, help), pure math (+ tests)
src/scene/      3D view (react-three-fiber)
  Terrain       tiled terrain: hillshade/hypsometric or data colour modes, lava (emissive by
                temperature), water, PDC/lahar overlays, vertical + deformation exaggeration
  Markers       vents, chambers/conduits, dike paths, GNSS stations with displacement arrows,
                geothermal features, section line
  Hypocentres   x-ray earthquake hypocentres sized by magnitude, coloured by type, fading with age
  Atmosphere    eruption column + umbrella, downwind ash cloud, ballistic bombs, lightning, flow fronts
src/panels/     SectionPanel (Erupt3-style cross-section: stratigraphy / material / temperature /
                water+steam, isotherms, water table, chamber/conduit/dike overlays, hover readout),
                Observatory (helicorder, RSAM & rates, Gutenberg–Richter, chamber gauges & history,
                GNSS/tilt, alert timeline), Controls (transport, speed 0.1–1000×, UNBOUNDED,
                step/+10s/+10m, tools, commands, layers), ReplayBar, EventLog
mock/           mock sim-server (MOCK)
scripts/        headless screenshot and debug probes (Playwright, installed ad hoc)
```

## Camera

The camera bar (top right) switches modes, frames targets and holds bookmarks; the compass (bottom
left) shows heading, world position (E/N metres), altitude and height above ground (AGL); the minimap
(bottom right) shows vents, the section line and the camera frustum. Press **?** for the controls
overlay.

| Mode (key) | What it does |
|---|---|
| Orbit (1) | Rotate around a pivot: drag rotates, right-drag / two fingers pan, wheel / pinch zooms towards the cursor. Never flips below the horizon or into the ground. |
| Fly (2) | Free flight. WASD moves along the view, Q/E (or Space) down/up, mouse look after clicking the view (pointer lock; Esc releases; dragging also looks), arrow keys look without a mouse. Speed scales with height above ground; Shift ×4, Ctrl/Alt ×0.2, wheel or `[` `]` change the base speed. Stays `clearance` metres above the terrain unless **underground** is on (G), which lets you fly below the surface to the chamber. |
| Walk (3) | First person at eye height (1.7 m) on the terrain with gravity: WASD, Shift runs, Space jumps; slopes steeper than 42° block you. Good for standing on a crater rim. |
| Follow (4) | Tracks the vent, the lava flow front, the plume top or the volcano with damped motion while you orbit around it as usual (target selector appears in the bar). |
| Tour (5) | Slow automatic fly-around of the selected volcano; any input stops it. |

| Key / action | |
|---|---|
| F (Shift+F while flying) | Frame the selected volcano |
| P | Fit the eruption column — a 20 km Plinian column fits the view |
| O | Overview of the whole world |
| Double-click terrain | Focus the orbit pivot there (fly mode: fly towards it) |
| Minimap click | Fly to that place, keeping height and heading |
| ★ views | Built-in bookmarks (Summit, Overview, Plume, Section line) and saved views per world (stored in the browser) |
| ⚙ | Fly speed, ground clearance, underground |
| Gamepad | Left stick move, right stick look, triggers down/up, A boost (walk: B jumps) |

The current view is kept in the URL as `?cam=mode,x,y,z,heading,pitch[,tx,ty,tz]` (scene metres,
radians), so copying the address shares exactly that view; the last view of each world is also
restored on the next visit. `prefers-reduced-motion` makes transitions instant and the tour slower.
Clip planes follow the height above ground (near plane down to 0.2 m at a crater rim, far plane
beyond the world from altitude).

## Using it

- **Navigate.** See [Camera](#camera); the 🖐 tool must be active for mouse look in fly/walk mode.
- **Cross-section.** Use ✎ in the toolbox or "Draw line" in the section panel. Click two or more
  points on the map, then **Cut**. The section refreshes every 3 s while shown.
- **Pour water / dig.** Use 💧 / ⛏, then click the map. Volume, radius and depth are in the toolbox.
  These demonstrate two-way coupling: a pit below the water table fills up, and poured water infiltrates.
- **Surface colouring.** Natural, ground temperature, water-table depth, surface unit, deformation,
  ash thickness or steam.
- **Transport.** ▶ REALTIME at the slider speed, ⏸ PAUSED, `+1` engine step, `+10s` / `+10m`, and
  ⏩ max (UNBOUNDED).
- **Replay.** ⟲ enters replay mode; drag the timeline to seek (keyframes are tick marks, eruptions
  are orange); ⏏ returns to live.

## Screenshots

With the mock and dev server running:

```bash
npm install --no-save playwright
node scripts/screenshot.mjs screenshots "http://localhost:5180/?renderer=webgl"
```

Software-rendered headless Chromium draws only ~1–2 frames per second, so the script dispatches
clicks directly and waits generously. A real GPU browser is far faster.
