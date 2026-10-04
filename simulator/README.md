# typhon-simulator

Runs typhon-engine scenarios headless, with no Minecraft involved. The engine's block changes go
to an in-memory world using the same compare-and-set rule a host would apply. The simulator writes
a time series, an event log, maps and an HTML report.

```sh
./gradlew :simulator:run --args="list-presets"
./gradlew :simulator:run --args="run --preset kilauea --hours 3 --out sim-out/kilauea"
# or build once and call the launcher directly
./gradlew :simulator:installDist
simulator/build/install/simulator/bin/simulator run --preset pinatubo --seed 7
```

## Options

```
run --preset NAME [--seed N] [--hours H] [--out DIR] [--sample-seconds S]
    [--base-step-ms MS] [--save DIR] [--load DIR]
    [--skip-events Type,Type|none] [--dem FILE [--dem-cell M] [--dem-meters-per-block L]
    [--dem-max-meters M]] [--quiet]
```

`--base-step-ms` sets the engine's base step (default 50 ms; a resolution setting, results are
largely invariant to it). `--save DIR` writes the engine state plus the simulated world at the end of
the run; `--load DIR` resumes such a save and runs `--hours` more. A load must use the same preset,
seed, base step and terrain options (mismatches are rejected by the engine's configuration check).

`--dem` swaps the preset's synthetic terrain for real elevation data and re-anchors the vents to the new ground. It accepts two formats:

- an ESRI ASCII grid, or a bare matrix of metres;
- a grayscale PNG, where white = `--dem-max-meters`.

Elevation 0 m maps to y = 62, and columns below it are flooded.

## Worlds

A world directory (`worlds/<name>/world.yaml` + `volcanoes/<id>.yaml`, see `engine/README.md`) can
hold any number of volcanoes and keeps its own state and history:

```
init-world --preset NAME [--seed N] --out DIR     # template that reproduces a preset exactly
init-world --example twin --out DIR               # two volcanoes sharing terrain and lava
run --world DIR [--hours H (default 1)] [--out DIR (default DIR/runs/latest)] [--sample-seconds S]
    [--accept-config-change | --reset-changed] [--no-save] [--skip-events ...] [--quiet]
```

`run --world` starts the world from its definitions, or resumes `DIR/state` when it exists, and
saves back into `DIR` at the end (unless `--no-save`). If the definitions changed since the last
save in ways that do not fit the saved state (grid, geology, chamber geometry, vents, ...), the run
is refused with the list of changes; `--accept-config-change` keeps the state and applies them,
`--reset-changed` restarts only the volcanoes that changed. Hot changes (climate, time compression,
magma supply, feature rates, `active`) apply without a flag.

The `terrain` section of `world.yaml` says where the initial terrain comes from:
`{source: preset, preset: kilauea, seed: 1}`, `{source: dem, path: dem.asc, cell: 30}` (path relative
to the world directory) or `{source: twin-cones, separation: 160, height: 60, radius: 140}`.

## Outputs (in `--out`, default `sim-out/<preset>-<seed>`)

| File | Contents |
|---|---|
| `timeseries.csv` | Primary volcano and world state every `--sample-seconds` |
| `events.ndjson` | One JSON object per engine event (`type` = record name) |
| `map-*.png` | Elevation, change, lava, ash and geothermal maps (north up, +X right) |
| `report.html` | Self-contained summary, timeline, SVG charts and embedded maps |

Columns in `timeseries.csv` include overpressure, eruption rate, RSAM, VT/LP rates, alert level and style, plus lava, plume, tephra and geothermal state.

Some high-frequency lava telemetry is left out of `events.ndjson` by default: `LavaSolidified` and `LavaEnteredWater`. These events are still counted in the report. Pass `--skip-events none` to write all of them. (Gas hazards, fumarole activity and ash fall are aggregated by the engine, so they are written.)

## Presets

| Name | Inspired by | Shows |
|---|---|---|
| `kilauea` | Kīlauea | Basaltic effusion, lava lake overflowing a summit pit |
| `stromboli` | Stromboli | Strombolian mix of lava and mild explosions |
| `st-helens` | Mount St. Helens 1980 | Dacite Plinian column, ballistics, ash fall, recharge |
| `pinatubo` | Pinatubo 1991 | Large Plinian eruption, lightning, broad ash blanket |
| `surtsey` | Surtsey 1963 | Submarine basalt, pillow lava, emergent island |
| `yellowstone` | Yellowstone | Hydrothermal caldera: fumaroles, springs, alteration |

Each preset lists the literature values it is based on; see `Presets.java` or the report.

## Tests

`./gradlew :simulator:test` runs the fast suite (about 10 s). `./gradlew :simulator:slowTest` runs every preset at its default length.
