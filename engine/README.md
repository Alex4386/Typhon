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

## Units

1 block = 1 m. Time in simulated seconds (20 ticks = 1 s). Pressure in MPa, temperature in °C,
composition in wt%, rates per simulated second.

## Layout

| Package | Contents |
|---|---|
| `sim` | `Engine` loop, `Subsystem`, `StepContext`, `EngineRunner` (off-thread runner) |
| `command` | `EngineCommand`, `CommandBus` |
| `output` | `BlockChange`, `EngineEvent`, `EngineFrame`, `Outbox` |
| `random` | `SimRandom` (SplitMix64, forkable, saveable) |
| `math`, `world` | `BlockPos`, `BlockId`, `BlockState` |
| `terrain` | `TerrainModel` (sparse column grid), `TerrainSnapshot` command |
| `volcano` | Shared volcano model: `VentSite`, `MagmaState` |

Build and test: `./gradlew :engine:test`.
