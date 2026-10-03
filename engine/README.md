# typhon-engine

Platform-independent volcano simulation core for Typhon v1. Hosts (Paper, Fabric, standalone)
feed it commands and terrain snapshots and apply the block changes and events it produces.

## Rules

- **No Minecraft/Bukkit/Fabric dependencies.** Blocks are `BlockId` / `BlockState` (namespaced
  ids, e.g. `minecraft:potent_sulfur`); hosts resolve them against their registries.
- **Deterministic.** All randomness comes from the `SimRandom` in `StepContext` (never
  `Math.random()`, `new Random()` or wall-clock time). Same seed + same commands ⇒ same frames.
- **Persistent.** Subsystem state must round-trip through `saveState` / `loadState` so a restored
  engine continues bit-for-bit (see `EngineTest.savedStateResumesBitForBit`). Terrain is not
  persisted; hosts re-send snapshots.
- **Semantic output.** Subsystems emit `EngineEvent`s describing what happened (a tremor of
  magnitude M at P), never how to render it. Block edits go through `Outbox.setBlock` as
  compare-and-set `BlockChange`s.
- **Multi-rate.** Pick each subsystem's `interval()` from the physics it models (a magma
  chamber every few seconds, lava every tick) and use `StepContext.dtSeconds()`.

## Units and scaling

Physics runs in real units: metres, seconds, MPa, °C, wt%, m³/s. Outputs reach the Minecraft world
through `VolcanoScaling` (Froude similarity: with L metres per block, lengths ×1/L, volumes ×1/L³,
velocities ×1/√L; eruption columns use their own length scale; dormancy is time-compressed).
Tests assert physical relationships (basalt runs farther than dacite), not tuned ratios.

## Layout

| Package | Contents |
|---|---|
| `sim` | `Engine` loop, `Subsystem`, `StepContext`, `EngineRunner` (off-thread runner) |
| `command` | `EngineCommand`, `CommandBus` |
| `output` | `BlockChange`, `EngineEvent`, `EngineFrame`, `Outbox` |
| `random` | `SimRandom` (SplitMix64, forkable, saveable) |
| `math`, `world` | `BlockPos`, `BlockId`, `BlockState` |
| `terrain` | `TerrainModel` (sparse column grid), `TerrainSnapshot` command |
| `volcano` | Shared volcano model: `VentSite`, `MagmaState`, `VolcanoScaling` |
| `magma` | `MagmaChamber` (lumped chamber: recharge, overpressure, crystallisation, Poiseuille eruption), `MeltViscosity` |
| `seismic` | `SeismicityModel` (VT/LP/tremor/explosion, Gutenberg–Richter, RSAM), `SeismicIntensity` |
| `alert` | `AlertLevelEstimator` (status with hysteresis), `EruptionStyleClassifier` |
| `lava` | `LavaFlow` (MAGFLOW-style Bingham cellular automaton, cooling, solidification) |
| `tephra` | `TephraSubsystem` (drag ballistics, Mastin plume, ash advection–diffusion and fall) |
| `geothermal` | `Geothermal` (heat/groundwater grid, fumaroles, sulfur, geysers, springs, alteration) |
| `assembly` | `VolcanoSystem` (wires one volcano together), `VolcanoCoupler` (eruption → lava/tephra) |

Build and test: `./gradlew :engine:test` (performance smoke tests: `./gradlew :engine:perfTest`).
