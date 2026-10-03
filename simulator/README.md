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
    [--skip-events Type,Type|none] [--dem FILE [--dem-cell M] [--dem-meters-per-block L]
    [--dem-max-meters M]] [--quiet]
```

`--dem` swaps the preset's synthetic terrain for real elevation data and re-anchors the vents to the new ground. It accepts two formats:

- an ESRI ASCII grid, or a bare matrix of metres;
- a grayscale PNG, where white = `--dem-max-meters`.

Elevation 0 m maps to y = 62, and columns below it are flooded.

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
