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
    [--skip-events Type,Type|none] [--dem FILE [--dem-lat D --dem-lon D] [--dem-cell M]
    [--dem-meters-per-block L] [--dem-max-meters M]] [--quiet]
dem-info --preset NAME-real
```

`--base-step-ms` sets the engine's base step (default 50 ms; a resolution setting, results are
largely invariant to it). `--save DIR` writes the engine state plus the simulated world at the end of
the run; `--load DIR` resumes such a save and runs `--hours` more. A load must use the same preset,
seed, base step and terrain options (mismatches are rejected by the engine's configuration check).

`--dem` swaps the preset's synthetic terrain for real elevation data and re-anchors the vents to
the new ground.

- **Real-scale presets** (`*-real`): the DEM is cropped around the preset's coordinates (or
  `--dem-lat/--dem-lon`), voids are filled, and it is resampled onto the preset's columns (box
  average when columns are coarser than the DEM). Elevations map 1:1 onto L-metre blocks: sea level
  (0 m) is the top of block −1, exactly as in the world model, and columns below it are flooded.
- **Compact presets**: elevation 0 m maps to y = 62 and heights are clamped to the build range.

| Format | Notes |
|---|---|
| GeoTIFF (`.tif`) | Single band; int8–64/uint/float32/64; strips or tiles; none, LZW, Deflate, PackBits; predictors 2/3; BigTIFF; GeoKeys (geographic or projected, EPSG), pixel-is-point, `GDAL_NODATA`. No JPEG/LERC/ZSTD, no rotated transforms. Reader: `terrain/GeoTiff.java`, no dependencies. |
| SRTM `.hgt` | 1" (3601²) or 3" (1201²) tiles; the corner comes from the file name (`N19W156.hgt`); voids (−32768) are filled. |
| ESRI ASCII grid | `ncols/nrows/cellsize/NODATA_value` header or a bare matrix (cell size from `--dem-cell`). No georeferencing: the DEM's middle is the centre. |
| PNG heightmap | Grayscale 8/16-bit; black = 0 m, white = `--dem-max-meters`. |

Geographic DEMs get metric cell sizes at their centre latitude (local equirectangular, < 1% error over
these domains). Projected DEMs (UTM etc.) are used in their own metres; `--dem-lat/--dem-lon` then
cannot be used (no reprojection), so crop them around the volcano beforehand.

### Getting real DEMs

Real DEMs are not committed. `dem-info --preset NAME-real` prints the tile for a preset. Copernicus
GLO-30 (30 m, 1°×1° Cloud-Optimised GeoTIFFs) needs no account:

```sh
curl -O https://copernicus-dem-30m.s3.amazonaws.com/Copernicus_DSM_COG_10_N19_00_W156_00_DEM/Copernicus_DSM_COG_10_N19_00_W156_00_DEM.tif
simulator run --preset kilauea-real --dem Copernicus_DSM_COG_10_N19_00_W156_00_DEM.tif
```

| Preset | Centre (lat, lon) | Copernicus GLO-30 tile | SRTM tile | Caveat |
|---|---|---|---|---|
| `kilauea-real` | 19.4069, −155.2834 | `N19_00_W156_00` | `N19W156` | DEMs show the summit after the 2018 collapse |
| `stromboli-real` | 38.7939, 15.2133 | `N38_00_E015_00` | `N38E015` | Sea is 0 m: add EMODnet/GEBCO bathymetry |
| `st-helens-real` | 46.1914, −122.1956 | `N46_00_W123_00` | `N46W123` | Post-1980 crater (synthetic terrain is pre-1980) |
| `pinatubo-real` | 15.1429, 120.3496 | `N15_00_E120_00` | `N15E120` | Post-1991 caldera and lake |
| `surtsey-real` | 63.3033, −20.6046 | `N63_00_W021_00` | `N63W021` | Today's island only; shelf needs EMODnet |
| `yellowstone-real` | 44.4605, −110.8281 | `N44_00_W111_00` | `N44W111` | Centred on Old Faithful |

SRTM 1" tiles come from NASA Earthdata or OpenTopography (accounts needed). Bathymetry: EMODnet
(Europe, GeoTIFF export) or GEBCO (global, 15"); merge it with the land DEM before importing, or
the sea floor is imported as 0 m.

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
`--reset-changed` restarts only the volcanoes that changed. Hot changes (climate,
magma supply, feature rates, `active`) apply without a flag.

The `terrain` section of `world.yaml` says where the initial terrain comes from:
`{source: preset, preset: kilauea, seed: 1}`,
`{source: dem, path: dem.tif, centerLat: 19.4069, centerLon: -155.2834, halfExtent: 256}` (real scale:
GeoTIFF/`.hgt` or `mapping: real`, at `grid.metersPerColumn`, sea level from `world.yaml`),
`{source: dem, path: dem.asc, cell: 30}` (compact mapping; paths relative to the world directory) or
`{source: twin-cones, separation: 160, height: 60, radius: 140}`. `init-world --preset NAME-real --dem FILE`
writes a real-DEM world for a real-scale preset.

## Outputs (in `--out`, default `sim-out/<preset>-<seed>`)

| File | Contents |
|---|---|
| `timeseries.csv` | Primary volcano and world state every `--sample-seconds` |
| `events.ndjson` | One JSON object per engine event (`type` = record name) |
| `map-*.png` | Elevation, change, lava, ash and geothermal maps (north up, +X right) |
| `section-ew.png` | West–east cross-section through the main vent at true elevation (deposits by type, darker = later eruptions) |
| `section-ew-shallow.png` | The same line flattened to the present surface: the top 2–200 m (auto-sized to the thickest deposit) at true thickness, cropped to the deposits, units labelled; deposits under 2 px are drawn 2 px and marked ▸ |
| `section-columns.png` | Stratigraphic column logs at the vent and a distal point (log-scaled heights, true thickness in the labels) |
| `report.html` | Self-contained summary, timeline, SVG charts and embedded maps |

Columns in `timeseries.csv` include overpressure, eruption rate, RSAM, VT/LP rates, alert level and style, plus lava, plume, tephra and geothermal state.

Some high-frequency lava telemetry is left out of `events.ndjson` by default: `LavaSolidified` and `LavaEnteredWater`. These events are still counted in the report. Pass `--skip-events none` to write all of them. (Gas hazards, fumarole activity and ash fall are aggregated by the engine, so they are written.)

## Presets

Every preset exists twice. The compact ones are Minecraft-sized (a few hundred blocks, 4–20 m per
block with a squashed vertical range). The `*-real` ones run at real scale: km-wide domains of 10–30 m
columns whose blocks are L-metre cubes (no vertical exaggeration; eruption columns may rise far above
build height), with terrain fitted to published edifice dimensions, real geology (basement cake,
volcano edifice zone, initial geotherm and water table) and a documented real DEM.

| Name | Inspired by | Shows |
|---|---|---|
| `kilauea` | Kīlauea | Basaltic effusion, lava lake overflowing a summit pit |
| `stromboli` | Stromboli | Strombolian mix of lava and mild explosions |
| `st-helens` | Mount St. Helens 1980 | Dacite Plinian column, ballistics, ash fall, recharge |
| `pinatubo` | Pinatubo 1991 | Large Plinian eruption, lightning, broad ash blanket |
| `surtsey` | Surtsey 1963 | Submarine basalt, pillow lava, emergent island |
| `yellowstone` | Yellowstone | Hydrothermal caldera: fumaroles, springs, alteration |

| Real-scale preset | Domain | Column | Synthetic edifice (sources in `RealPresets.java`) |
|---|---|---|---|
| `kilauea-real` | 10.2 km | 20 m | Shield 1247 m, 4° flanks; caldera r 1.9 km, floor 1100 m; Halema'uma'u r 500 m, 85 m deep (pre-2008) |
| `stromboli-real` | 7.7 km | 15 m | Island cone 924 m, shoreline ~2 km out, −660 m at the edge; Sciara del Fuoco trough NW; crater terrace ~750 m |
| `st-helens-real` | 10.2 km | 20 m | Pre-1980 cone 2950 m on a 1200 m plateau, basal radius 6 km, concave flanks |
| `pinatubo-real` | 15.4 km | 30 m | Pre-1991 cone 1745 m from 400 m foothills, radius 7 km |
| `surtsey-real` | 3.8 km | 10 m | Shelf at −130 m, hyaloclastite cone to −15 m (November 1963) |
| `yellowstone-real` | 15.4 km | 30 m | Caldera floor ~2400 m, rim +250 m to the west, 60 m deep lake at 2357 m, four basins |

Each preset lists the literature values it is based on; see `Presets.java`, `RealPresets.java` or
the report. Real-scale presets also list numeric reference values, which the report and the CLI
compare with the run ("reference vs model"; within / below / above).

## Tests

`./gradlew :simulator:test` runs the fast suite (about 10 s). `./gradlew :simulator:slowTest` runs every preset at its default length.
