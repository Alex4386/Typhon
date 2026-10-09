# Arbitrary switches to replace with physics

The engine's processes start, stop and change form because a physical quantity says so, never because a
hand-set threshold does. This is the backlog of places that still gate behaviour with a switch, cap or
fudge constant (audit of 2026-10-09). Remove an entry when it is fixed.

Numerical guards (step bounds, solver tolerances, work budgets, divisor floors) are not on this list as
long as they do not change outcomes.

## Fixed

- `MagmaChamberConfig.eruptionEndOverpressureMPa` (2 MPa): eruptions end when the conduit flow can no
  longer keep the conduit molten, the conduit cannot lift magma, or every outlet is sealed or frozen.
- Dike buoyancy ignored exsolved gas, and dikes opened under the tip pressure, not the mean wall pressure.
- Dikes froze the moment they stalled: a stalled dike now stays molten for its solidification time and can
  be driven on.
- `ConduitModel.MAGMA_DENSITY = 2500` and the chamber's copy: melt density follows composition and dissolved
  water (`MeltDensity`) in the chamber, conduit, dikes, magma transfer and vent water mixing.
- `ConduitConfig.reopenOverpressureMPa` (3 MPa) and its linear interpolation: a molten conduit reopens
  when the pressure pushes out its solidified cap (`2·C·δ/r`, Griffith cohesion), and an eruption only
  starts or lasts while its flow keeps the conduit from freezing.
- A stop by hand drained the chamber to the end threshold; it now plugs the conduit and keeps the pressure.
- `conduitOpenness() < 0.5` picked the fast or slow conduit branch: a failure fragments the magma (fast branch)
  when its sudden decompression exceeds the foam's strength over its porosity (Spieler et al. 2004).
- One plume per volcano, put on the strongest vent: every vent now feeds its own column and plume.
- Localised vents kept their throat's conductance forever: a vent's throat now widens or freezes by the
  same heat balance as the fissure (`VentThroat`). `FissureFeeder.RHO = 2700` follows the melt's density.
- Molten lava held every step to 120 s whatever its cooling rate; the step now follows the fastest-cooling
  column. Bomb trajectories are sub-stepped by ground resolution (half a column) instead of a fixed 12.5 ms.
- `DikeConfig.maxSpeed` (5 m/s): the opening was twice too wide (the crack's full breadth used as its
  half-length) and took the static chamber pressure along the whole dike. A flowing dike now loses its
  pressure linearly to what just breaks rock at the tip, opens under the mean of the rest, and rises at the
  laminar or turbulent (Blasius) slot speed through its mean opening: 0.1–0.6 m wide at 4 MPa rising at
  0.06–0.3 m/s, ~1.8 m and ~12 m/s at 14 MPa, no cap. Below the subsurface grid the dike's wall rock is the
  geotherm plus the chamber's conductive halo (the subsurface model's own bottom boundary).
- Fissure segments opened with a random ±20 % spread about a uniform width, clamped at 0.3, widened up to
  4× and had a 4 cm minimum: they now open as an elastic crack (widest at the centre, closing to the tips)
  with a log-normal spread, and widen or freeze only by their heat balance.
- Localised vents freeze by cylindrical conduction (Carslaw &amp; Jaeger), not a planar wall: narrow throats
  lose heat far faster, so vents sharing a fissure's flow coalesce into the widest.
- `ConduitModel.INERTIAL_VISCOSITY = 1e4`: a fragmenting foam tears inertially when the Reynolds number of
  its expansion across the conduit, `ρ ε̇ r² / η`, exceeds 1 (Namiki &amp; Manga 2008).
- Quiet conduits only froze: gas-bearing magma now convects through them (core–annular exchange flow,
  `Q = Ps Δρ g r⁴ / μ_d`, Stevenson &amp; Blake 1998) and keeps them molten while that flux outruns
  conduction, degassing the chamber at the vent and cooling it (wall conduction along the conduit plus the
  radiation of its open magma surface, ~10 MW at Stromboli).
- Crust density was a constant 2600 kg/m³ (`MagmaChamber.ROCK_DENSITY`, `DikeConfig.rockDensity`): the chamber
  now takes the world's rock column above it (`CrustColumn`: each layer's bulk density, voids removed, pores
  saturated below the water table) for its lithostatic load, the head a magma column must balance and the
  pressure between chambers; dikes take their buoyancy from the same column, so dense magma stalls under a
  porous edifice at its level of neutral buoyancy (Ryan 1987).
- An open-vent volcano started at lithostatic pressure overflowed at once (its gas-rich column is lighter than
  the crust): `initialOverpressureMPa: NaN` starts it at rest, its convecting column standing at the vent, and
  a convecting conduit overflows only when the chamber lifts the column's mean weight (Stromboli).
- `FissureFeeder.WANING_CONDUCTANCE` (0.5): a fissure is waning when its flow is falling and even its widest
  segment narrows.
- `MagmaChamberConfig.wallTemperatureC = 400` played three parts: the chamber now cools into the region's
  geotherm at its depth, its conduit freezes against the geotherm half-way up, and its walls creep at the
  mean temperature of a one-radius shell in its steady conductive halo (Jellinek &amp; DePaolo 2003).
- `conduitFreezingRateM3PerS` (πκL) ignored the latent heat and the contrast to the wall rock: it is now
  `πκL / (1 + L_h/(c ΔT))`, the flux that crosses the conduit within its own solidification time.
- `GeomorphConfig.debrisFlowSaturation` (0.8): a failed mass liquefies into a debris flow when its pore
  water fills the pores of the debris it contracts to (Iverson et al. 1997): saturated loose tephra does,
  saturated dense lava (dilating) does not.
- `VentPartition` aquifer constants (`AQUIFER_PERMEABILITY` 1e-11 m², `AQUIFER_INTERACTION_DEPTH_M` 300 m, unit
  gradient): groundwater enters the conduit between the water table and the fragmentation level, through the
  conductivity of the ground's layers under the vent, as a well screen (Hvorslev shape factor).

## High: decide whether or when eruptions, dikes or flows happen

- `DikeConfig.maxConcurrentDikes = 1`: only one dike rises at a time.
- `MagmaChamber.MAX_CRYSTAL_FRACTION = 0.58`: a chamber can never lock up as a mush. Removing it alone locks
  Pinatubo (780 °C dacite) at 69 % crystals, where it erupted with ~40 %: the crystallinity needs a hydrous
  liquidus (water lowers it by ~100–150 °C; Médard &amp; Grove 2008) and a non-linear melt-fraction curve
  (silicic melts survive to near the solidus) first.
- `minCharacteristicHeight = 200` is the size of the crack a dike starts as; the quasi-static slot model is
  singular at zero height. It should come from the roof's failure (the region in hoop tension); taking the
  chamber's radius instead gave cracks broader than the roof is thick and 26 m/s dikes from a large chamber.
- Plug explosions: `plugStrengthMPa`, `plugViscosityLog10` and the logistic `PLUG_TRANSITION_LOG10`.
- `GeomorphConfig.hotCollapseTemperatureC` (400 °C) sends hot failed masses (dome collapses) down as
  block-and-ash flows: what makes a hot collapse fluidise into a PDC (pressurised pore gas, heated entrained
  air) is not modelled.

## Empirical constraints kept for now

- `VentPartition.SUPPRESSION_DEPTH_M` (150 m): explosive water–magma interaction is observed only in the top
  ~100–200 m of water (Kokelaar 1986). A first-principles steam-expansion bound (work ∝ ln(p_c/p)) leaves
  half the explosivity at 140 m, more than observed, so the observed depth stays until the MFCI physics is
  modelled.

## Numerical, documented

- `ConduitModel`: a flow within a few per cent of its sound speed at the vent (`1 − M² < 0.05`) counts as
  choked, where the sonic singularity steepens the profile faster than the grid resolves; within one
  conduit radius of the vent the foam is open to the atmosphere, and whether the jet breaks up is decided at
  the vent from its porosity at ambient pressure.
- `FissureFeeder.FREEZE_WIDTH_M` (1 mm) and `VentThroat.FREEZE_RADIUS_M`: the last millimetre closes in
  minutes.

## Medium: change magnitudes

- Three liquidus/solidus laws and three maximum crystallinities for the same magma.
- Below the world's stack the crust keeps its deepest layer's density (no compaction with depth).
- Duplicated constants that disagree: magma, rock and clast densities, crustal shear modulus.
- Wall viscosity clamp, slug ejecta and backlog caps, the coalescence heuristic.
- Configured `exsolutionTimescale` and `crystallisationTimescale`.
- `VentPartition` grain-size medians, column-collapse sigmoid, aggregation constants.
- Fixed slug and plug clast sizes. `VolcanoCoupler.PHASE_UPDATE_THRESHOLD` now only decides when a column change is
  reported (the lofted rate is retuned every step), but the collapse (PDC) source still follows it in 25 % steps.
- Seismic explosivity index, and VT and swarm rates standing in for physics.
- Lava: `crustDisruptionVelocity`, `hyaloclastiteFraction`, fixed density, the tuned yield-strength shift.
- Mass flows: stop depth and speed, cooling timescale, welding temperature, erosion and rain thresholds.
- Tephra: exit-speed clamps, flight-time cut-off, fixed bomb size.
- Geomorphology caps: slab depth, crater radius, minimum slope.
- Geothermal activity ramps and formation probabilities per hour.
- Subsurface: Nusselt cap, steam collapse timescale.
