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

There is **one clock**: time is an exact count of microseconds and every subsystem works in
seconds of physical time. Nothing is compressed or sped up inside the model; how fast the clock
runs against the wall clock is playback (the runner's speed, §Runner), which never changes the
physics. Compact worlds scale *lengths* only (`VolcanoScaling`: metres per block, with velocities
and the times derived from them following Froude similarity).

**Adaptive steps.** Time advances in quanta of the **base step** (`baseStepMicros`, default 50 ms).
With `Engine.builder(seed).adaptive(maxSeconds)` (hosts use `Engine.DEFAULT_MAX_STEP_SECONDS`, a
day) each step is a power-of-two number of quanta: the largest that is at most every subsystem's
`Subsystem.maxStepSeconds()` (and the engine maximum) and divides the current time, so steps stay
aligned. A subsystem is stepped when its schedule point `phase + n·period` falls in the step, with
`dt` covering the time since its last step. The choice depends on the state only, so runs are
deterministic, thread-invariant and resume bit for bit across a change of step length
(`AdaptiveClockTest`). Without `adaptive` every step is one base step. Results are invariant to
the base step where physics allows (`BaseStepInvarianceTest`).

Step limits (`maxStepSeconds`) while something happens, otherwise unlimited:

| subsystem | limit |
|---|---|
| magma chamber | 20 s while erupting or about to; a quarter of the time to failure while pressurising; ≤ 1 day |
| volcano coupler | 1 s while erupting (fountains, bombs and flow fronts) |
| tephra | `ashStepSeconds` while a column or airborne ash exists; 1 s while bombs fly |
| lava | while any cell is molten or a source flows: ≤ 120 s and ≤ `maxSubsteps` stable sub-steps |
| mass flows | their step period while a flow is active |
| dikes | 20 s while a dike rises |
| subsurface | 30 s while surface water moves (macro steps themselves are split into ≤ 7-day spans) |
| geothermal | 1 day |

A quiet volcano therefore takes steps of most of a day (a year ≈ 600 steps, ≈ 20 s on one core for
the test cone); an eruption runs at about a second per step (an hour ≈ 4500 steps, ≈ 50 s). The
slow subsystems keep physical periods (chamber, coupler, alert, style, detail, geomorphology,
deformation and ash 20 s; seismicity 10 s; geothermal 40 s), so a 1-s eruption step stays cheap.
Surface water sub-steps at its CFL step but routes at most `surfaceWaterRoutingSeconds` per step
(and stops once quasi-steady); a spring-fed crater pond still makes quiet years cost minutes. Events carry `time()` in seconds (random events of a long step, such as
quakes, are spread over it); frames carry the step index and `timeMicros`.

Lava sources take eruption rates (m³/s DRE). Flow is sub-stepped (`Δt ≤ relaxation·L²/D`,
`D = ρgh³/3η` for the most fluid moving lava at `substepFlowThicknessM`, at most `maxSubsteps`),
and cooling integrates each column in sub-iterations of at most `coolingStepK`.

## Runner

`EngineRunner` runs an engine on its own thread:

| Mode | Behaviour |
|---|---|
| `REALTIME` | the clock runs at speed × wall time (seconds per wall second, adjustable while running, e.g. 1–10⁷×); a step runs once the wall clock reaches its end, whatever its length (`playbackMicros()` interpolates the clock inside a long step); catches up at most `maxCatchUpSteps` steps, then lets time slip |
| `UNBOUNDED` | as fast as the CPU allows |
| `PAUSED` | no steps; `step(n)` runs n steps, `stepFor(seconds)` until the clock has advanced that far; `pauseAtStep` / `pauseAtTime` pause automatically |

Output: an optional **lossless** frame queue (back-pressure; hosts that must apply every block
change), a bounded **lossy** event ring with a dropped-event count (UIs), and a throttled
`EngineSnapshot`. Saves and inspection run between steps via `onEngineThread`. The mode never
changes results (`EngineRunnerTest.resultsDoNotDependOnRunnerSpeed`). A frame observer
(`setFrameObserver`) sees every frame on the engine thread and may switch the speed there: the
sim-server's playback policy slows down to an eruption speed at the very step an eruption starts.

## Threading

Results are **bit-identical for every thread count**; threads only change speed. The thread count
is `Engine.Builder.threads(n)` (default: system property `typhon.threads`, else all cores); the
simulator and sim-server take `--threads N`.

- **Data parallelism inside subsystems** — `StepContext.parallel()` (`sim/Parallel`). A low-latency
  pool: workers spin briefly between regions (an engine step issues many short ones) and park when
  idle; the calling thread works too; regions smaller than twice their grain, and nested regions,
  run inline. Used by lava (flux, gather, heat balance, rendering per chunk), mass flows (faces and
  gather per chunk), tephra (ash transport, settling and injection per row), geothermal sampling,
  subsurface heat/groundwater/surface water (per solver chunk / tile), and sim-server tile
  sampling/encoding.
- **Lanes** — `Subsystem.concurrencyLane()`: consecutive due subsystems that declare a lane form a
  stage whose lanes run concurrently (each lane in registration order); outputs are buffered per
  subsystem and merged in registration order, so results equal sequential execution. The magma
  chamber, seismicity and alert estimator declare `volcano:<id>`.

Rules for subsystem authors (all checked by thread-count invariance tests,
`*ThreadInvarianceTest`, `ConcurrencyLaneTest`):

1. A parallel body for item *i* writes only item *i*'s state (its chunk, its row, its slot) and
   reads only what no item writes in the same region — e.g. neighbours' *previous* buffers. Swap
   double buffers in a *later* region than the one that reads neighbours.
2. Never accumulate into shared doubles from a parallel body: write per-item partials and combine
   them sequentially in item order (`Parallel.sum`, per-chunk scratch folded afterwards).
3. Anything order-dependent — `Outbox` block changes and events, `SimRandom` draws, world-model and
   terrain edits, creating chunks or other map entries — happens sequentially, in a fixed (sorted)
   order, before or after the parallel region. Defer per-item actions into the item's own buffer
   (as lava defers solidification) and apply them afterwards in order.
4. Resolve lookups that may create or cache things (neighbour chunks, terrain refresh) in a
   sequential "prepare" pass; parallel code only reads the prepared slots.
5. Keep per-thread scratch local (allocate in the body or use a `ThreadLocal`), never in fields.

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
`WorldSpec.metersPerColumn()`; ground block `y` has its top at `(y + 1)·L` metres. A re-sent column
whose ground block did not move keeps the stacks' exact (sub-block) surface.

### Stratigraphy (who deposits what)

Volcanic subsystems write their physical deposits straight into the world model, at their exact
thickness, as layers of a unit attributed to the producing volcano's current eruption (a
`UnitSource`; `assembly.VolcanoUnits` uses `MagmaChamber.eruptionCount()`). Blocks only render
those layers: subsystems update the block cache with `TerrainModel.updateBlockCache`, never through
the bridge (which would deposit unattributed fill a second time).

| Producer | Deposit type, material | Flags |
|---|---|---|
| `LavaFlow` (flows on `surfaceZ + uplift`) | `LAVA` basalt/andesite/dacite/rhyolite/obsidian; `HYALOCLASTITE` under water; tube = `CAVITY` (void) under `TUBE_ROOF` | columnar flows `FRACTURED` |
| `PyroclasticFlows` | `PDC` tuff, welded at/above the welding temperature | non-welded `LOOSE` |
| `Lahars` | `LAHAR` lahar deposit | `LOOSE` |
| `TephraSubsystem` | `FALL` ash (every mm; none on molten lava, where it joins the flow); bombs: their real volume as `FALL` rock | ash `LOOSE` |
| `DikePropagation` | `INTRUSION` gabbro/andesite/granite from chamber depth to the tip, along the path | |
| `VolcanoCoupler` | tuff rings (`FALL`) | |
| `Geomorphology` | `LANDSLIDE` talus (source material); `EJECTA` crater breccia (excavated material) | `LOOSE` |
| `DebrisAvalanches` | `DEBRIS_AVALANCHE` debris | `LOOSE` |

Lahars entrain loose `FALL`/`PDC`/`LAHAR` layers out of the stacks (rain failure, bulking), so fresh
tephra and ignimbrite are lahar source material without any wiring. Without a volcano assembly the
subsystems use `UnitSource.typed(world)` (typed but unattributed units). Lava melt carries a unit
too; the newest unit contributing ≥ 10 % of a cell's melt takes it over.

## Subsurface: heat, groundwater, surface water

`subsurface.Subsurface` is one world-level subsystem shared by every volcano (`World` registers it
after the terrain; a stand-alone `VolcanoSystem` with geothermal activity creates its own). It works
on the world model:

- **Solver grid** (`SubsurfaceGrid`): columns `solverSpacing` wide (`r` surface columns per side),
  24 terrain-following levels (1 m at the top, ×1.32 per level, ≈ 2.4 km deep). Cell properties
  come from the stratigraphic layer at the cell centre, recomputed when a world-model tile changes;
  cavities insulate and carry no water. Level of detail per 16×16 chunk: HOT (anomaly > 2 °C, a
  vent, or pending heat, or next to such a chunk), WARM (within two chunks: every 10th macro step),
  DORMANT (not stepped). Heat crosses faces only between chunks stepped together.
- **Heat** (`SubsurfaceHeat`, every `macroStepSeconds`): explicit lateral conduction and
  Darcy advection (sub-stepped), then implicit vertical conduction (Thomas) with porous-convection
  Nusselt enhancement (`Ra > 40`), latent heat of melting, a Robin surface boundary (ground or
  water) and a bottom boundary from the background geotherm plus each chamber's steady conductive
  halo (sphere below an isothermal surface, method of images). Saturated cells above the boiling
  point at their depth below the water table (`100 + 3·d^0.7`, Haas 1971; not beyond the critical
  point, not in molten rock) flash pore water to steam, taken from the aquifer.
- **Groundwater** (`Groundwater`): Dupuit water table per solver column,
  `S_y ∂h/∂t = ∇·(T∇h) + R − Q`, transmissivity integrated from layer K; red-black SOR, then a
  conservative update from face fluxes. Vadose bucket with lag τ; springs where the table reaches
  the ground; sea columns are fixed heads.
- **Surface water** (`SurfaceWater`, every `surfaceWaterStepSeconds`): local-inertial shallow
  water with Manning friction on the surface columns; imported water bodies touching the edge of
  the known world (and anything below sea level) are open water at a fixed level, enclosed ones are
  lakes. Infiltration where the table is below the ground, evaporation.
- **Water budget** (`WaterBudget`): rain, poured water, imported lakes, initial groundwater, sea
  exchange, evaporation and boiling are all accounted; the imbalance stays at rounding level.
- **Hooks**: `HeatSources` (chambers, vent heat pipes — `Geothermal` implements it per volcano),
  `addSurfaceHeat`/`addSurfaceHeatFlux` (lava, PDCs resting on the ground), `addSheetHeat` (dikes,
  sills), `addWater`/`removeWater` and `WorldModel.addWater` (buckets, hosts), queries through
  `HydrothermalField` (temperature at depth, water-table depth, steam fraction and flux, surface
  water depth). `equilibrate` spins the system up (heat, groundwater, rain; no randomness).

`geothermal.Geothermal` no longer models heat or water itself: it supplies its volcano's heat and
samples the reservoir temperature and water-table depth on a coarse feature grid to decide where
fumaroles, geysers, springs, mud pots, sinter, alteration and cinnabar form.

## World definitions and worlds

A world is a directory:

```
worlds/<world>/
  world.yaml                     grid, scaling, sea level, climate, geology, geotherm, aquifer,
                                 terrain source (host-interpreted), lava and subsurface parameters
  volcanoes/<id>.yaml            vents, magma chamber + conduit, dikes, geothermal, mass flows,
                                 deformation, tephra, active flag
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
the saved ones (`ConfigChanges`). Changes that keep the saved state valid apply; the others need
`ChangePolicy.ACCEPT` (keep state anyway) or `RESET_CHANGED` (reset what they affect).

### Changing definitions while running (`ConfigImpact`)

One rule set, `worlds.ConfigImpact`, decides what any change of a definition needs; reopen
compatibility (`ConfigChanges`), live retuning and hosts' plans, schemas and messages all derive
from it. The rule: **every physical parameter is live**; only these are not:

| kind | what | why |
|---|---|---|
| **reinit** of the whole volcano | `magma.chamber.initial*`, chamber `volume` and `center`, `magma.conduit.initialOpenness`, `vents`, adding/removing `dikes`, `geothermal`, `massFlows`, `deformation`, adding/removing a volcano | initial conditions, geometry or the presence of subsystems define the state |
| **reinit** of one part | `tephra.cellSize`/`gridCells`/`worldTopY` (ash grid), `geothermal.center`/`radius`/`cellSize` (hot-spring grid), `detail.*` (crater surface) | the part's saved state is laid out on them; only that part starts over |
| **reload** (state kept) | world `geology`, `geotherm`, `aquifer`, `terrain`; a volcano's `edifice` | read only when ground is generated or the world assembled |
| refused for a running world | `seed`, `baseStepMs`, `grid.*`, `seaLevel`, `subsurface.levels`/`firstLevelM`/`levelGrowth`, `expansion.tileColumns` | the whole world is laid out on them |

Live changes are applied in place: `World.reconfigureLive(world, volcanoes)` derives every
subsystem's configuration from the new definitions (`VolcanoSystem.Builder.subsystemConfigs`, the
same pure step assembly uses) and hands the changed ones to `Engine.reconfigure(id, config)` between
steps. Each subsystem's `Subsystem.reconfigure` takes the new configuration keeping its state (mutable
configuration classes are copied into the instance its helpers share, records swapped) and refuses
layout changes. The engine updates the subsystem's schedule and recorded configuration hash, and the
world records the new definitions, so a save after a live change restores bit for bit with them and
runs are unchanged by thread count (`LiveRetuneTest`).

### Generation vs simulation (on-demand growth)

Like a Minecraft world, a Typhon world is unbounded but only partly *loaded*. Unlike Minecraft, what
loads ground is physics, never a viewer.

- **Generation.** The host attaches a `terrain.TerrainGenerator` (`World.setTerrainGenerator`, on every
  open): the column at any (x, z) as a pure function of the world definition (preset generator and
  seed, or DEM). It is not saved; it is regenerated from `world.yaml`. The initial core
  (`terrain.coreExtentM` for the simulator's hosts) is just a window onto it.
- **Simulation.** Only columns in the `TerrainModel` are simulated. `expansion.WorldExpansion` (last
  subsystem, every `expansion.periodSeconds`) asks its `ExpansionActivity` sources where something is
  happening: molten lava and flows blocked by unknown ground (`LavaFlow`), moving pyroclastic flows,
  lahars and debris avalanches (`MassFlowField`), rising dike tips, explosion craters and caldera
  collapse (`Geomorphology`; slope failures report through the avalanches they become), tephra fall at
  least `ashThresholdM` thick, and running water at least `waterThresholdM` deep that reached the edge.
  Every expansion tile (`tileColumns`, aligned to multiples of it) within `marginTiles` of an active
  tile that is not fully simulated is generated and imported through `TerrainModel.apply`, exactly
  like the initial terrain (geology, edifices, continuous relief). Background processes (ravelling,
  settling lakes, thin ash) do not grow the world.
- **Hand-over.** New columns are initialised by the subsurface on its next step (their aquifer enters
  the water budget as `initialGroundwater`; the groundwater fixed-head edge moves with the area). The
  tephra ash grid keeps fall on unsimulated ground and lays it down when the ground materialises
  (`ExpansionActivity.Listener`). Lava and mass flows simply see new known chunks.
- **Determinism.** Activity is read from saved state inside the step, tiles are materialised in key
  order and the generator is pure: runs are identical for any thread count and a restore mid-growth
  continues bit for bit (`WorldExpansionTest`). Materialised tiles are saved with the terrain.
- **Bounds.** `maxExtentM` (a square around x = z = 0) and `maxTiles` cap growth; one 64×64 tile of
  20 m columns costs about 0.8 MB of heap (≈0.2 KB per column), so the default 400 tiles add ≤ ~0.35 GB.
  All `expansion:` parameters are hot.

## Units and scaling

Physics runs in real units: metres, seconds, MPa, °C, wt%, m³/s. Outputs reach the Minecraft world
through `VolcanoScaling` (Froude similarity: with L metres per block, lengths ×1/L, volumes ×1/L³,
velocities ×1/√L; eruption columns use their own length scale; dormancy is time-compressed).
Tests assert physical relationships (basalt runs farther than dacite), not tuned ratios.

## Eruptions are emulated, not chosen

No preset or definition selects an eruption style. The chamber feeds a resolved steady conduit flow
(`magma.conduit.ConduitModel`), whose outputs (mass flux, exit velocity and pressure, gas fractions,
fragmentation depth and mode, vent viscosity and crystallinity) are partitioned continuously at the
vent (`assembly.VentPartition`) into lava, fountain fall-back, ballistics, an eruption column and its
collapsing share, and magma–water explosions. Gas slugs and stiff plugs burst as discrete explosions
when the flow makes them. What an observer would call the eruption (Hawaiian, Strombolian,
Vulcanian, Pelean, sub-Plinian, Plinian, lava dome, Surtseyan, mixed) and its VEI are *estimated*
from the resulting activity by `alert.EruptionClassifier` and reported in
`AlertEvents.EruptionStyleEstimated`; nothing reads them back. `EruptiveRegime` is a one-word
descriptor of the conduit flow for telemetry. See `docs/eruption-dynamics.md`.

### Magma supply and injection commands

All magma inputs are tunable at run time. Commands are records (Gson-serialisable; boxed fields are
optional and `null` means "keep" / "use the supply's"), addressed by volcano id, applied at the
chamber's next step, and their effect is saved with the chamber's state.

| Command | Fields | Effect |
|---|---|---|
| `MagmaCommands.SetSupplyRate` | `volcanoId`, `double supplyRate` (m³/s DRE, physical) | Deep supply rate. |
| `MagmaCommands.SetSupplyMagma` | `volcanoId`, `Double supplyRate`, `Double temperatureC`, `Double silicaWt`, `Double waterWt`, `Double co2Wt`, `Double crystalFraction` (0–0.9), `Double variability` (log-normal σ) | Rate and magma of the continuous deep supply; each field optional. |
| `MagmaCommands.InjectRecharge` | `volcanoId`, `double volume` (m³ DRE), `double temperatureC`, `double silicaWt`, `double waterWt`, `Double co2Wt`, `Double crystalFraction` | One recharge pulse: raises overpressure by `volume / (V β)` and mixes in. `co2Wt` / `crystalFraction` default to the supply's. The 5-argument constructor keeps the old form. |
| `MagmaCommands.StartEruption` / `StopEruption` | `volcanoId` | Manual overrides. |

Water and CO₂ are melt contents (wt%); `silicaWt` is the bulk SiO₂. Supply and pulses mix by mass
(SiO₂, H₂O and CO₂ of the melt fraction) and by enthalpy `h = c_p T + L (1 − φ/φ_max)`: crystals have
already released their latent heat, so a crystal-rich batch heats the chamber less than a melt of the
same temperature. The chamber's own crystallinity then follows its temperature. A world definition's
`magma.chamber.supply*` / `recharge*` keys are the initial supply; changing them in a definition
re-applies them over the saved state on reload (`MagmaChamber.resetSupplyFromConfig`). Validation:
temperature 0–2000 °C, SiO₂ 35–80 wt%, H₂O 0–15 wt%, CO₂ 0–5 wt%, crystals 0–0.9, rate ≥ 0.

`MagmaChamber.supply()` reports the current supply (`SupplyMagma(rate, temperatureC, silicaWt,
waterWt, co2Wt, crystalFraction, variability)`).

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
| `volcano` | Shared volcano model: `VentSite`, `VentStatus`, `VentEvents`, `VentCommands` (seal/unseal/remove vents), `MagmaState`, `VolcanoScaling` |
| `magma` | `MagmaChamber` (lumped chamber: recharge by mass and enthalpy, overpressure, crystallisation, H₂O/CO₂ exsolution, open/closed conduit, slug bursts, plug failures), `MagmaCommands`, `MeltViscosity`; `magma.conduit`: `ConduitModel` (steady 1-D two-phase conduit flow: exsolution, outgassing, microlites, fragmentation, choking, multiple steady states), `ConduitSolution` |
| `seismic` | `SeismicityModel` (VT/LP/tremor/explosion, Gutenberg–Richter, RSAM), `SeismicIntensity` |
| `alert` | `AlertLevelEstimator` (status with hysteresis), `EruptionClassifier` (style probabilities and VEI estimated from the eruption's observables; output only) |
| `lava` | `LavaFlow` (MAGFLOW-style Bingham cellular automaton on an 8-neighbour L-metre grid in real units, cooling, crust and lava tubes, ocean-entry deltas) |
| `dike` | `DikePropagation` (buoyancy/stress-driven dike ascent, flank fissures, induced VT hypocentres; user arrest/remove/block via `DikeCommands`) |
| `deformation` | `DeformationModel` (Mogi chamber source + dike dislocation, virtual GNSS/tilt stations) |
| `massflow` | `PyroclasticFlows`, `Lahars`, `DebrisAvalanches` (Voellmy–Salm depth-averaged flows), `ColumnCollapse` (Woods 1988) |
| `geomorph` | `Geomorphology` (infinite-slope + Culmann slope stability on the stratigraphy, strength from material/alteration/heat (`RockStrength`), pore pressure, pseudo-static shaking (`GroundMotion`); talus relaxation or avalanche/debris-flow/block-and-ash release; hydrothermal alteration; explosion craters (`CraterScaling`), open-vent clearing, piston collapse) |
| `tephra` | `TephraSubsystem` (drag ballistics, Mastin plume, ash advection–diffusion and fall) |
| `subsurface` | `Subsurface` (world-level heat conduction, Dupuit groundwater, boiling, surface water, water budget), `HydrothermalField`, `HeatSources` |
| `geothermal` | `Geothermal` (supplies volcano heat to the subsurface; fumaroles, sulfur, geysers, springs, alteration from its fields) |
| `assembly` | `VolcanoSystem` (wires one volcano together), `VolcanoCoupler` (vent selection, eruption → lava/tephra/PDC, magma–water), `FissureFeeder` (thermal life of a fissure's feeder: conductance-weighted flow, localisation, freezing; Bruce & Huppert 1989, Wylie et al. 1999), `VentPartition` (continuous partition of the conduit flow at the vent) |

Build and test: `./gradlew :engine:test` (performance smoke tests: `./gradlew :engine:perfTest`).
