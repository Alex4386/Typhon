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

## High: decide whether or when eruptions, dikes or flows happen

- `DikeConfig.maxSpeed` (5 m/s) caps the dike's slot-flow speed, and so its freeze test. It hides openings
  that come out several times too wide (7–19 m against observed ~1–3 m): the crust's shear modulus is 3 GPa
  and the breadth reaches the chamber's diameter. Turbulent wall friction alone still gives ~80 m/s; the
  opening and the elastic modulus need calibrating against dike widths before the cap can go.
- `DikeConfig.maxConcurrentDikes = 1`: only one dike rises at a time.
- `DikeConfig.minCharacteristicHeight = 200` sets the toughness test and speed of a new dike.
- `MagmaChamber.MAX_CRYSTAL_FRACTION = 0.58`: a chamber can never lock up as a mush.
- `ConduitModel.INERTIAL_VISCOSITY = 1e4` skips the foam-strength fragmentation test.
- `ConduitModel` choke fudge `denominator < 0.05`, and the ban on fragmentation within one radius of the
  vent.
- Plug explosions: `plugStrengthMPa`, `plugViscosityLog10` and the logistic `PLUG_TRANSITION_LOG10`.
- `FissureFeeder` `FREEZE_WIDTH_M`, `MAX_WIDENING`, minimum width, and the random `WIDTH_SPREAD` seed.
- A quiet conduit only loses heat by conduction: magma convecting in it (Kazahaya et al. 1994) and gas
  streaming through it keep open-vent volcanoes (Stromboli, lava lakes) molten; without them every
  conduit caps over within weeks.
- `GeomorphConfig.debrisFlowSaturation` / `hotCollapseTemperatureC` classify failed masses.
- `VentPartition` aquifer constants (`AQUIFER_PERMEABILITY`, `AQUIFER_INTERACTION_DEPTH_M`, unit
  gradient) ignore the groundwater model.

## Empirical constraints kept for now

- `VentPartition.SUPPRESSION_DEPTH_M` (150 m): explosive water–magma interaction is observed only in the top
  ~100–200 m of water (Kokelaar 1986). A first-principles steam-expansion bound (work ∝ ln(p_c/p)) leaves
  half the explosivity at 140 m, more than observed, so the observed depth stays until the MFCI physics is
  modelled.

## Medium: change magnitudes

- Three liquidus/solidus laws and three maximum crystallinities for the same magma.
- `MagmaChamberConfig.wallTemperatureC = 400` instead of the geotherm and the heating history.
- Crust density is a constant 2600 kg/m³ (`MagmaChamber.ROCK_DENSITY`, `DikeConfig.rockDensity`) for
  lithostatic pressure and buoyancy; it should come from the world's rock column (porous near the surface,
  ~2900 at depth), which decides where basalt is buoyant.
- Duplicated constants that disagree: magma, rock and clast densities, crustal shear modulus.
- Wall viscosity clamp, slug ejecta and backlog caps, the coalescence heuristic.
- Configured `exsolutionTimescale` and `crystallisationTimescale`.
- `VentPartition` grain-size medians, column-collapse sigmoid, aggregation constants.
- `VolcanoCoupler.PHASE_UPDATE_THRESHOLD = 0.25`, fixed slug and plug clast sizes.
- Seismic explosivity index, and VT and swarm rates standing in for physics.
- Lava: `crustDisruptionVelocity`, `hyaloclastiteFraction`, fixed density, the tuned yield-strength shift.
- Mass flows: stop depth and speed, cooling timescale, welding temperature, erosion and rain thresholds.
- Tephra: exit-speed clamps, flight-time cut-off, fixed bomb size.
- Geomorphology caps: slab depth, crater radius, minimum slope.
- Geothermal activity ramps and formation probabilities per hour.
- Subsurface: Nusselt cap, steam collapse timescale.
- `conduitFreezingRateM3PerS` ignores the temperature contrast and latent heat.
