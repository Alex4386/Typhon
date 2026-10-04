# typhon-engine

Platform-independent volcano simulation core for Typhon v1. Hosts (Paper, Fabric, standalone)
feed it commands and terrain snapshots and apply the block changes and events it produces.

## Rules

- **No Minecraft/Bukkit/Fabric dependencies.** Blocks are `BlockId` / `BlockState` (namespaced
  ids, e.g. `minecraft:potent_sulfur`); hosts resolve them against their registries.
- **Deterministic.** All randomness comes from the `SimRandom` in `StepContext` (never
  `Math.random()`, `new Random()` or wall-clock time). Same seed + same commands ⇒ same frames.
- **Persistent.** Subsystem state must round-trip through `saveState(StateWriter)` /
  `loadState(StateReader)` so a restored engine continues bit-for-bit (see
  `EngineTest.savedStateResumesBitForBit`, `SaveFormatTest`). Scalars go to `json()`, spatial arrays
  to `field(name, schema)`. Expose the build configuration through `config()` (hash-checked on
  restore) and a cheap immutable `snapshot()` for dashboards.
- **Semantic output.** Subsystems emit `EngineEvent`s describing what happened (a tremor of
  magnitude M at P), never how to render it. Block edits go through `Outbox.setBlock` as
  compare-and-set `BlockChange`s.
- **Multi-rate, in seconds.** Pick each subsystem's `periodSeconds()` from the physics it models
  (a magma chamber every second, lava every base step) and use `StepContext.dtSeconds()`. Never
  assume a particular base step; sub-step internally when stability needs a finer step.

## Time model

Simulation time is an exact `long` count of microseconds. The engine advances in fixed **base
steps** (`Engine.builder(seed).baseStepMicros(...)`, default 50 ms). The base step is a simulator
accuracy/performance setting, not a game tick: hosts convert simulation time to their own clock.
Subsystem periods and phases are rounded to whole base steps (minimum one step; `∞` = command-driven,
never stepped). Events carry `time()` in simulated seconds; frames carry the step index and
`timeMicros`. Results are invariant to the base step where physics allows
(`BaseStepInvarianceTest`) and bit-identical for a fixed base step.

Physical time compression (dormancy ×5000, eruptions ×20 by default) lives in `VolcanoScaling` and
is part of the model; how fast simulation time runs against the wall clock is the runner's job.

## Runner

`EngineRunner` runs an engine on its own thread:

| Mode | Behaviour |
|---|---|
| `REALTIME` | simulation time = speed × wall time (speed adjustable while running, e.g. 0.1–1000×); catches up at most `maxCatchUpSteps` steps, then lets time slip |
| `UNBOUNDED` | as fast as the CPU allows |
| `PAUSED` | no steps; `step(n)` runs n steps; `pauseAtStep` / `pauseAtTime` pause automatically |

Output: an optional **lossless** frame queue (back-pressure; hosts that must apply every block
change), a bounded **lossy** event ring with a dropped-event count (UIs), and a throttled
`EngineSnapshot`. Saves and inspection run between steps via `onEngineThread`. The mode never
changes results (`EngineRunnerTest.resultsDoNotDependOnRunnerSpeed`).

## Save format

`Engine.save(SaveStore)` / `Engine.builder(seed)...restore(SaveStore)`; `DirectorySaveStore` on
disk, `InMemorySaveStore` for tests and `stateHash()`. Paths are relative, so a store can be rooted
anywhere (e.g. `worlds/<world>/state/`).

```
meta.json                              format, engine version, seed, step, time, base step,
                                       subsystems in order with config + config hash, queued commands
subsystems/<id>.json                   random state + scalar/transient state + field schema versions
fields/<id>/<field>/r.<rx>.<rz>.bin    deflated typed arrays, 32×32 chunks per region file
log/events.ndjson                      append-only HistoricalEvents (eruptions, quakes, alert changes,
                                       dikes, fissures, flow onsets, ...)
```

Ids are percent-encoded (`magma:v` → `magma%3Av`). Unchanged files are not rewritten, so periodic
saves are incremental. Restoring checks format, seed, base step and every subsystem's config hash
(`allowConfigChanges()` to override). Terrain is part of the save.

## World model

`world.WorldModel` is the source of truth for what lies under every column: stratigraphic layers
from a datum up to the ground surface (`ColumnStacks`: 32×32-column tiles, CSR layer arrays, at most
48 layers per column). Each layer has a material (`MaterialTable`: density, conductivity, heat
capacity, hydraulic conductivity, porosity, solidus/liquidus, erodibility, with sources), a
stratigraphic unit (`UnitTable`: volcano, eruption, deposit type, time, emplacement T, SiO₂),
porosity, welding, a sub-cell void fraction and flags (`LOOSE`, `FRACTURED`, `ALTERED`).

- Queries (`WorldQuery`): `surfaceZ`, `layer`, `materialAt`, `isSolid` (binary search on layer
  tops), `column`, `section` (raster along a polyline with material/unit/void per pixel).
- Edits (`WorldEdit`): `deposit`, `erode` (optionally loose material only, returns what was
  removed per material), `carve` (cavities: lava tubes, tunnels; digging from the surface opens a
  pit), `fill`, `addWater` (held for the surface-water model), `newUnit`.

Imported columns are built bottom-up from the world spec: the basement layer cake
(`geology.basement`), country rock (`geology.edificeMaterial`) and a surface cover. Where a volcano
defines an edifice (`edifice: {material, radius, baseZ}` in its YAML, a `world.Edifice` centred on its
primary vent), the rock above `baseZ` (the pre-volcano surface; default: the top of the basement cake)
is the volcano's material instead, recorded as that volcano's `EDIFICE` unit. Overlapping edifices go
to the volcano the column lies deepest in (distance / radius).

`world.yaml` also holds the initial conditions the subsurface solvers start from (consumed from M4
on; nothing integrates them yet):

- `geotherm: {surfaceTemperatureC, gradientCPerKm, lapseRateCPerKm}` →
  `WorldDefinition.Geotherm.initialTemperature(surfaceZ, depth)` = surface T − lapse·max(0, zs)/1000 +
  gradient·depth/1000.
- `aquifer: {waterTableDepth, specificYield, topographyFactor, baseLevel, rechargeFraction}` →
  `WorldDefinition.Aquifer.initialWaterTable(surfaceZ, base)` = base + f·(zs − depth − base), capped at
  the surface; `base` is `baseLevel`, else sea level, else the domain's lowest surface. `f = 1` keeps
  the table a fixed depth below ground, `f = 0` makes it flat (real tables: ~0.3–0.8).

`terrain.TerrainModel` is the block-level bridge over it: host `TerrainSnapshot`s import columns
(built as above) or reconcile known ones,
and every ground change made through it deposits or erodes in the stacks. Blocks are cubes of
`WorldSpec.metersPerColumn()`; ground block `y` has its top at `(y + 1)·L` metres.

## World definitions and worlds

A world is a directory:

```
worlds/<world>/
  world.yaml                     grid, scaling, sea level, climate, geology, geotherm, aquifer,
                                 terrain source (host-interpreted), lava parameters
  volcanoes/<id>.yaml            vents, magma chamber + conduit, dikes, geothermal, mass flows,
                                 deformation, tephra, time-compression overrides, active flag
  state/                         engine save (above) + world.json (definitions it ran with,
                                 runtime additions/removals/activity)
  history/world.ndjson
  history/volcanoes/<id>/{eruptions,seismic,alerts,events}.ndjson
```

`config.WorldDefinition` / `config.VolcanoDefinition` parse the YAML (SnakeYAML Engine) strictly:
unknown keys, wrong types and values that only the world scaling may set are reported with file,
key path and the valid keys. Sections bind onto the engine's config objects by name
(`ConfigBinder`), so every tunable of `MagmaChamberConfig`, `ConduitConfig`, `DikeConfig`,
`GeothermalConfig`, `MassFlowConfig`, `TephraConfig` and `LavaConfig` is configurable. See the
javadoc of both definition classes for annotated examples.

`worlds.World` assembles one engine from a world and N volcanoes (shared terrain and lava field,
per-volcano subsystems), saves state and per-volcano history, and adds/removes/sleeps volcanoes at
runtime by rebuilding the engine from an in-memory save (definition files stay untouched; runtime
changes go to `state/world.json`). When a saved world is reopened its definitions are diffed against
the saved ones (`ConfigChanges`): *hot* changes (climate, time compression, magma supply, feature
caps/rates, names, `active`, new volcanoes) apply; *re-init* changes (grid, geology, chamber
geometry, vents, removed volcanoes) need `ChangePolicy.ACCEPT` (keep state) or `RESET_CHANGED`
(restart the changed volcanoes).

## Units and scaling

Physics runs in real units: metres, seconds, MPa, °C, wt%, m³/s. Outputs reach the Minecraft world
through `VolcanoScaling` (Froude similarity: with L metres per block, lengths ×1/L, volumes ×1/L³,
velocities ×1/√L; eruption columns use their own length scale; dormancy is time-compressed).
Tests assert physical relationships (basalt runs farther than dacite), not tuned ratios.

## Layout

| Package | Contents |
|---|---|
| `sim` | `Engine` loop, `Subsystem`, `StepContext`, `SimTime`, `EngineRunner` (modes, frames, events, snapshots), `EngineSnapshot` |
| `save` | `SaveStore` (directory / in-memory), `StateWriter` / `StateReader`, `FieldChunk`, `SaveFormat` (region files) |
| `command` | `EngineCommand`, `CommandBus` |
| `output` | `BlockChange`, `EngineEvent`, `HistoricalEvent`, `EngineFrame`, `Outbox` |
| `random` | `SimRandom` (SplitMix64, forkable, saveable) |
| `math` | `BlockPos` |
| `world` | `BlockId`, `BlockState`; world model: `WorldModel`, `ColumnStacks`, `MaterialTable`, `UnitTable`, `WorldQuery`, `WorldEdit`, `BlockMaterialPalette` |
| `config` | `WorldDefinition`, `VolcanoDefinition` (YAML), `ConfigBinder`, `ConfigNode`, `Yaml` |
| `worlds` | `World` (multi-volcano assembly, saves, runtime changes), `WorldDirectory`, `ConfigChanges`, `HistoryRouter` |
| `terrain` | `TerrainModel` (block-level bridge over the world model), `TerrainSnapshot` command |
| `volcano` | Shared volcano model: `VentSite`, `MagmaState`, `VolcanoScaling` |
| `magma` | `MagmaChamber` (lumped chamber: recharge, overpressure, crystallisation, Poiseuille eruption, open/closed conduit, Strombolian slugs, Vulcanian plugs), `ConduitFlow` (outgassing, brittle fragmentation, gas segregation → `EruptiveRegime`), `MeltViscosity` |
| `seismic` | `SeismicityModel` (VT/LP/tremor/explosion, Gutenberg–Richter, RSAM), `SeismicIntensity` |
| `alert` | `AlertLevelEstimator` (status with hysteresis), `EruptionStyleClassifier` |
| `lava` | `LavaFlow` (MAGFLOW-style Bingham cellular automaton on an 8-neighbour L-metre grid in real units, cooling, crust and lava tubes, ocean-entry deltas) |
| `dike` | `DikePropagation` (buoyancy/stress-driven dike ascent, flank fissures, induced VT hypocentres) |
| `deformation` | `DeformationModel` (Mogi chamber source + dike dislocation, virtual GNSS/tilt stations) |
| `massflow` | `PyroclasticFlows`, `Lahars` (Voellmy–Salm depth-averaged flows), `ColumnCollapse` (Woods 1988) |
| `tephra` | `TephraSubsystem` (drag ballistics, Mastin plume, ash advection–diffusion and fall) |
| `geothermal` | `Geothermal` (heat/groundwater grid, fumaroles, sulfur, geysers, springs, alteration) |
| `assembly` | `VolcanoSystem` (wires one volcano together), `VolcanoCoupler` (vent selection, eruption → lava/tephra/PDC) |

Build and test: `./gradlew :engine:test` (performance smoke tests: `./gradlew :engine:perfTest`).
