# Eruption dynamics: emulate the eruption, estimate its style

Until now the engine **picked** an eruptive regime (`FOUNTAINING`, `OPEN_VENT`, `DOME`, `EXPLOSIVE`)
from a few thresholds when an eruption started, branched its behaviour on that choice, split the
erupted mass with fixed shares (column collapse 50 % or 0 %, tuff-ring fallout 40 %, jet ballistics
10 %), and then *named* the style by mapping the regime. That is the "choose a style, then play its
effects" pattern of the Minecraft plugin.

The model below removes the choice. Each chamber step solves a steady **conduit flow** from the
chamber's state to the vent. Everything at the surface follows continuously from the conduit's exit
conditions: the effusion of coherent magma, fountains, the size of pyroclasts, which pyroclasts
fall back near the vent or are carried by the jet, column collapse, slug bursts, plug failure, and
magma–water interaction. The eruption **style** is no longer an input anywhere. An
`EruptionClassifier` *estimates* it afterwards from what the eruption did, the way a volcanologist
classifies a deposit or an observatory classifies activity, and reports probabilities and a VEI.

Citations marked ✓ were checked against the publisher or an author copy; others are marked
*(unverified)*.

## 1. Conduit flow (`magma/conduit/ConduitModel`)

A vertical cylindrical conduit of radius `r` and length `L` (the chamber's physical depth) connects
the chamber top (pressure `P_ch` = lithostatic + overpressure) to the vent (ambient pressure
`p_a` = atmospheric + any standing water above the vent). The flow is steady and one-dimensional,
treated as a homogeneous gas–melt–crystal mixture (Wilson & Head 1981 ✓; Mastin 2002 *(unverified)*),
with explicit permeable outgassing that lets gas leave the mixture (Degruyter et al. 2012 ✓).

State along height `z` (0 at the chamber, `L` at the vent): pressure `p`, microlite crystal
fraction `φ`, and the mass fraction of gas lost by outgassing `n_out`.

**Volatiles.** Dissolved water follows the square-root solubility law already used by the chamber,
`c_d = min(c₀, 0.411·√p[MPa])` wt% (Wilson & Head 1981 ✓). Exsolved gas mass fraction
`n_tot = (c₀ − c_d)/100`; the gas still carried by the mixture is `n = n_tot − n_out`.

**Mixture.** Specific volume `v = n R_w T / p + (1 − n)/ρ_m` (`R_w = 461.5 J/kg/K`), density
`ρ = 1/v`, gas volume fraction `α = n R_w T/(p v)`. With mass flux per area `G = Q_m/(πr²)` the
velocity is `u = G v`.

**Momentum.** For constant `G`, `ρ u du/dz = G² (∂v/∂p) dp/dz`, so

```
dp/dz = −(ρ g + F) / (1 + G² ∂v/∂p)          ∂v/∂p = −n R_w T/p² + (R_w T/p − 1/ρ_m) ∂n/∂p
```

The denominator vanishes when `u` reaches the mixture sound speed: the flow is **choked** there.

**Friction.** Below fragmentation, laminar wall friction `F = 8 η u / r²` (Poiseuille). Above
fragmentation, turbulent gas–pyroclast friction `F = f_D ρ u² / (4 r)` with `f_D = 0.02`
(Wilson & Head 1981 ✓).

**Viscosity.** Melt viscosity from `MeltViscosity` (VFT, Giordano et al. 2008 form) at the local
dissolved water and total crystal fraction (chamber crystals + microlites, Einstein–Roscoe). Bubbles
modify it with the capillary-number dependent relation of Llewellin & Manga (2005) ✓:
`η_r = η_∞ + (η_0 − η_∞)/(1 + (6/5·Ca)²)`, `η_0 = (1 − α)⁻¹`, `η_∞ = (1 − α)^{5/3}`,
`Ca = η_m r_b γ̇ / Γ` with `γ̇ ≈ u / r`, bubble radius `r_b = 100 µm`, surface tension
`Γ = 0.1 N/m`.

**Decompression crystallisation.** Water loss raises the liquidus and drives microlite growth
(Cashman & Blundy 2000 *(unverified)*). The equilibrium microlite fraction grows by
`k_x = 0.12` per wt% of exsolved water (capped so that total crystals ≤ 0.6) and is approached
with a kinetic time `τ_x` (hours): `dφ/dz = (φ_eq − φ)/(τ_x u)`. Slowly rising magma crystallises
and stiffens; fast-rising magma does not have time to.

**Permeable outgassing.** Above a percolation threshold `α_c = 0.3` the bubble network is
permeable, `k(α) = k_ref ((α − α_c)/(1 − α_c))³` with `k_ref = 10⁻¹¹ m²`
(Klug & Cashman 1996 *(unverified)* range 10⁻¹⁴–10⁻¹¹ m²). Gas then leaves the mixture:

* *vertically*, Darcy flow relative to the melt `w = (k/μ_g)·|dp/dz|`, escaping over the
  remaining path to the vent: rate `λ_v = w / max(L − z, r)`;
* *laterally* into the wall rock, limited by the less permeable of magma and wall
  (`k_w = 10⁻¹³ m²`): `λ_l = 2 (min(k, k_w)/μ_g)·(p − p_h) / (r² α)`, with `p_h` the hydrostatic
  pore pressure at that depth (Jaupart & Allègre 1991 *(unverified)*).

`dn_out/dz = (λ_v + λ_l)·n / u` (`μ_g = 3×10⁻⁵ Pa·s`). Slow ascent loses most of its gas; fast
ascent keeps it. The gas lost this way is reported as passive degassing at the surface.

**Fragmentation.** The coherent mixture fragments when either

* the melt fails brittlely: `η_m·|du/dz| ≥ k G_∞ = 0.01 × 10¹⁰ Pa` (Papale 1999 ✓), giving fine
  ash; or
* the gas volume fraction reaches `α_f = 0.75` **while expansion outpaces outgassing**
  (`(λ_v + λ_l) < |d ln v/dt|`). In fluid basalt this is inertial break-up of a rapidly expanding
  foam into coarse clots (Namiki & Manga 2008 ✓); when gas escapes faster than the foam expands, the
  magma outgasses instead of fragmenting.

Above the fragmentation level the gas carries the pyroclasts; outgassing and viscous friction stop.

**Vent condition and solution.** For a trial mass flux the profile is integrated upward
(RK2 on a grid refined towards the vent). The flux is valid if the flow reaches the vent with
`p_vent = p_a` (subsonic) or chokes exactly at the vent with `p_vent ≥ p_a`. Fluxes that choke
below the vent, or drop below `p_a` before reaching it, are too high. The admissible fluxes are
found by scanning `G` on a log grid and refining every bracket.

**Several steady states.** The same chamber pressure can admit a slow, outgassed, effusive
solution and a fast, gas-rich, fragmented one (Melnik & Sparks 1999 ✓ for dome extrusion;
Degruyter et al. 2012 ✓). Which one the volcano follows depends on history, not on a chosen style:
a sealed conduit that fails suddenly is decompressed rapidly and starts on the fastest branch; a
conduit that is already open, or a dike-fed flank vent, starts on the slowest; during an eruption
the flow stays on the branch closest to its current flux. If no steady flux exists (the magma
column is too heavy for the chamber pressure) nothing erupts until pressure builds again.

**Outputs** (`ConduitSolution`): mass and DRE flux, exit velocity, pressure, choked flag, carried
gas mass and volume fractions, fragmentation depth and mechanism (brittle / inertial), exit melt
viscosity, crystallinity, temperature, dissolved water, outgassed fraction, gas segregation
(bubble rise speed vs ascent speed) and a descriptive flow-regime label (bubbly, slug, churn,
fragmented; Gonnermann & Manga 2007 ✓).

The chamber uses the solved flux in its exact exponential pressure update
(`P → P_eq + (P − P_eq) e^{−t/τ}` with conductance `Q/P`), capped by `maxEruptionRate` as a vent
erosion limit.

## 2. Surface partition (`assembly/VentPartition`)

Every quantity below is a continuous function of the conduit exit state. Nothing branches on a
style or regime name.

### 2.1 Fragmented flow
* **Jet velocity.** Gas expands from the exit pressure to ambient: `u_j² = u_v² + 2 n R_w T ln(p_v/p_a)`
  (isothermal decompression work; Wilson 1980 *(unverified)*).
* **Pyroclast sizes.** Log-normal in diameter (σ = 2 φ units). The median depends on how the
  melt broke: brittle fragmentation of viscous melt gives ash, `d₅₀ ≈ 0.5 mm/(1 + 20 n)`; inertial
  break-up of fluid melt gives clots and spatter, `d₅₀ ≈ 20 mm`; in between it is interpolated in
  `log η` (Rust & Cashman 2011 ✓; Namiki & Manga 2008 ✓).
* **Ballistic / fountain fall-back.** A clast whose terminal velocity in the jet gas,
  `v_t = √(4 ρ_p g d / (3 C_d ρ_g))`, exceeds the jet velocity cannot be carried: everything coarser
  than `d* = 3 C_d ρ_g u_j² / (4 ρ_p g)` (and everything above 64 mm) falls back near the vent.
  The fountain height is `u_j²/2g` (Wilson & Head 1981 ✓; Wilson, Parfitt & Head 1995 ✓).
* **Hot or cold landing.** A clast of size `d` cools in flight over `t_f = 2 u_j / g` with time
  constant `τ_c ≈ ρ c_p d / (6 h)` (radiation plus convection, `h ≈ 700 W/m²/K`). Dense fountains
  shield their interior: the effective flight time is reduced by an opacity
  `1 − e^{−Q_m/Q_ref}` (Head & Wilson 1989 ✓). Clasts landing above the solidus coalesce into
  clastogenic lava (added to the effusion); colder ones build a scoria/spatter cone as ballistics.
* **Eruption column.** The remainder rises as a column with the exit gas fraction and jet
  velocity. Whether it collapses comes from the Woods (1988) ✓ steady jet model already in
  `ColumnCollapse`; the critical gas fraction `n_c` is found by bisection and the collapsing share
  varies smoothly across the transition, `f_c = 1/(1 + e^{ln(n/n_c)/0.15})`, representing the
  partial-collapse regime (Degruyter & Bonadonna 2013 ✓). The collapsing share feeds pyroclastic
  density currents; the buoyant share forms the plume, whose height follows Mastin et al. (2009).

### 2.2 Coherent (unfragmented) flow
* **Effusion.** The erupted magma becomes lava at the vent with the conduit's exit temperature,
  dissolved water and crystallinity. Whether it spreads as flows or piles up as a **dome** is
  decided by the lava field's rheology (viscosity and Bingham yield strength), not by a label.
* **Gas slugs (Strombolian bursts).** Bubbles rise through the melt at the Stokes speed
  `v_b = ρ g d_b² / (18 η)`; the share of the exsolving gas that segregates is
  `v_b/(v_b + u)`, and coalescence into conduit-filling slugs is efficient only in fluid melt,
  `E_c = 1/(1 + η/10³ Pa·s)` (Jaupart & Vergniolle 1988 *(unverified)*; Gonnermann & Manga 2007 ✓).
  Slugs have gamma-distributed lengths of a few conduit diameters (James et al. 2008
  *(unverified)*); each burst releases the gas in the slug at its overpressure `ρ_m g L_s` and
  fragments the magma cap above it. Burst sizes and intervals follow from the segregated gas flux.
* **Plugs (Vulcanian explosions).** When the exit viscosity is high enough for the uppermost
  conduit to stiffen into a plug (degassing-induced crystallisation; Diller et al. 2006 ✓), the
  gas still carried by the magma accumulates beneath it. The plug's strength grows with its
  viscosity; when the trapped gas pressure exceeds it the plug fails and the gas-charged cap is
  fragmented (Clarke et al. 2002 *(unverified)*). Explosion size and repose emerge from gas
  supply, plug strength and outgassing.

### 2.3 Magma–water interaction
External water reaching the vent — standing sea or lake water over a submerged vent with an open
rim, or groundwater from the subsurface model where the water table lies above the shallow
conduit — gives a water/magma mass ratio `R`. The conversion of thermal to mechanical energy
peaks near `R ≈ 0.3` (Sheridan & Wohletz 1983 ✓; Wohletz 1983 ✓): efficiency
`E(R) = (R/0.3)·e^{1 − R/0.3}`. Hydrostatic pressure suppresses steam expansion with depth,
`S(d) = e^{−d/60 m}` (Kokelaar 1986 *(unverified)*). The phreatomagmatically fragmented share
`E·S` erupts as a wet, fine-grained column with cock's-tail jets; its wet fallout builds a tuff
ring (wetter ⇒ more near-vent fallout, `R/(R + 0.3)`); the rest of the magma follows the magmatic
path (lava or pillow lava under water).

## 3. Style estimation (`alert/EruptionClassifier`)

The classifier observes the eruption and never feeds back into it. Over a rolling physical-time
window it tracks: lava versus tephra mass rates, mass eruption rate, buoyant column height,
fountain height, explosion rate and mean explosion mass, collapse (PDC) share, water involvement,
vent viscosity, and the cumulative erupted volume. Each style gets a score from soft membership
functions of these observables:

| Style | Evidence |
|---|---|
| Hawaiian | effusion and/or fountains of fluid magma, few discrete explosions |
| Strombolian | frequent (≳1 /h) small discrete explosions, low column, fluid magma |
| Vulcanian | infrequent larger discrete explosions from viscous magma, columns of km |
| Sub-Plinian | sustained tephra-dominated column 10–20 km (Cioni et al. 2000 *(unverified)*) |
| Plinian | sustained tephra-dominated column above 20 km |
| Surtseyan | water-driven fragmentation dominating a shallow-submerged vent |
| Phreatic | steam explosions without juvenile magma (not produced by the model yet) |
| Lava dome | effusion of very viscous lava (≳10⁹ Pa·s) |
| Pelean | a dome with collapsing, PDC-producing activity |

Scores are normalised to probabilities; when no style exceeds 0.45 the estimate is reported as
**MIXED**. The **VEI** follows Newhall & Self (1982) ✓ from the cumulative bulk tephra volume of the
eruption, raised to the column-height bound when the column is higher. An
`EruptionStyleEstimated` event carries the probabilities and VEI whenever the most likely style or
the VEI changes, or a probability moves by more than 0.2. Before an eruption the observatory's
forecast is the same classifier applied to the conduit solution the chamber would follow if it
failed now.

## 4. Magma inputs

Everything about the magma is a runtime input, never a style: chamber supply rate, temperature,
SiO₂, H₂O, crystal fraction and supply variability (`MagmaCommands.SetSupplyMagma`, persisted,
hot-reloadable), and recharge pulses (`MagmaCommands.InjectRecharge` with volume, temperature,
SiO₂, H₂O, crystal fraction). Mixing conserves mass and energy: bulk SiO₂ and H₂O are
mass-weighted, and the mixed temperature solves the enthalpy balance
`h = c_p T + L (1 − φ_eq(T))`, so crystal-rich (cooler, latent-heat-poor) recharge cools the
chamber more than its temperature alone suggests.

## References

* Cashman K.V., Blundy J.D. (2000) Degassing and crystallization of ascending andesite and dacite. Phil. Trans. R. Soc. A 358:1487–1513. *(unverified)*
* Cioni R. et al. (2000) Plinian and subplinian eruptions. Encyclopedia of Volcanoes, 477–494. *(unverified)*
* Clarke A.B. et al. (2002) Computational modelling of the transient dynamics of the August 1997 Vulcanian explosions at Soufrière Hills Volcano. Geol. Soc. London Mem. 21:319–348. *(unverified)*
* Degruyter W., Bachmann O., Burgisser A., Manga M. (2012) The effects of outgassing on the transition between effusive and explosive silicic eruptions. EPSL 349–350:161–170. ✓
* Degruyter W., Bonadonna C. (2013) Impact of wind on the condition for column collapse of volcanic plumes. EPSL 377–378:218–226. ✓
* Diller K., Clarke A.B., Voight B., Neri A. (2006) Mechanisms of conduit plug formation: implications for vulcanian explosions. GRL 33:L20302, doi:10.1029/2006GL027391. ✓
* Gonnermann H.M., Manga M. (2007) The fluid mechanics inside a volcano. Annu. Rev. Fluid Mech. 39:321–356. ✓
* Head J.W., Wilson L. (1989) Basaltic pyroclastic eruptions: influence of gas-release patterns and volume fluxes on fountain structure, and the formation of cinder cones, spatter cones, rootless flows, lava ponds and lava flows. JVGR 37:261–271. ✓
* James M.R. et al. (2008) Pressure changes associated with the ascent and bursting of gas slugs in liquid-filled vertical and inclined conduits. JVGR 175:229–237. *(unverified)*
* Jaupart C., Allègre C.J. (1991) Gas content, eruption rate and instabilities of eruption regime in silicic volcanoes. EPSL 102:413–429. *(unverified)*
* Jaupart C., Vergniolle S. (1988) Laboratory models of Hawaiian and Strombolian eruptions. Nature 331:58–60. *(unverified)*
* Klug C., Cashman K.V. (1996) Permeability development in vesiculating magmas. Bull. Volcanol. 58:87–100. *(unverified)*
* Kokelaar P. (1986) Magma–water interactions in subaqueous and emergent basaltic volcanism. Bull. Volcanol. 48:275–289. *(unverified)*
* Llewellin E.W., Manga M. (2005) Bubble suspension rheology and implications for conduit flow. JVGR 143:205–217. ✓
* Mastin L.G. (2002) Insights into volcanic conduit flow from an open-source numerical model. G³ 3(7). *(unverified)*
* Mastin L.G. et al. (2009) A multidisciplinary effort to assign realistic source parameters to models of volcanic ash-cloud transport. JVGR 186:10–21. (see `docs/references.md`)
* Melnik O., Sparks R.S.J. (1999) Nonlinear dynamics of lava dome extrusion. Nature 402:37–41. ✓
* Namiki A., Manga M. (2008) Transition between fragmentation and permeable outgassing of low viscosity magmas. JVGR 169:48–60. ✓
* Newhall C.G., Self S. (1982) The volcanic explosivity index (VEI). JGR 87(C2):1231–1238. ✓
* Papale P. (1999) Strain-induced magma fragmentation in explosive eruptions. Nature 397:425–428. ✓
* Rust A.C., Cashman K.V. (2011) Permeability controls on expansion and size distributions of pyroclasts. JGR 116:B11202, doi:10.1029/2011JB008494. ✓
* Sheridan M.F., Wohletz K.H. (1983) Hydrovolcanism: basic considerations and review. JVGR 17:1–29. ✓
* Wilson L. (1980) Relationships between pressure, volatile content and ejecta velocity in three types of volcanic explosion. JVGR 8:297–313. *(unverified)*
* Wilson L., Head J.W. (1981) Ascent and eruption of basaltic magma on the Earth and Moon. JGR 86:2971–3001. ✓
* Wilson L., Parfitt E.A., Head J.W. (1995) Explosive volcanic eruptions VIII. The role of magma recycling in controlling the behaviour of Hawaiian-style lava fountains. GJI 121:215–225. ✓
* Wohletz K.H. (1983) Mechanisms of hydrovolcanic pyroclast formation: grain-size, scanning electron microscopy, and experimental studies. JVGR 17:31–63. ✓
* Woods A.W. (1988) The fluid dynamics and thermodynamics of eruption columns. Bull. Volcanol. 50:169–193. ✓

## As implemented (differences from the plan above)

- **Kinetic exsolution and the frozen sound speed.** Equilibrium exsolution gives a mixture sound
  speed of a few m/s right at the saturation level and chokes basaltic flows there. Exsolution (H₂O,
  and CO₂ by Henry's law, 5×10⁻⁴ wt%/MPa, Dixon 1997) relaxes towards solubility with time constant
  `exsolutionTimescale` (1 s); the singular point is the *frozen* sound speed
  `1 + G² ∂v/∂p|ₙ = 0`, and exsolution enters the momentum balance as a source
  `G² (∂v/∂n) dn/dz`. Exsolution, outgassing and microlite growth are integrated with exponential
  relaxation (stable at any ascent rate); RK2 sub-steps keep pressure changes below 5 %.
- **CO₂.** The chamber tracks bulk CO₂; what exceeds solubility at the inlet enters the conduit as free
  gas (a mixture gas constant is carried to the vent), and exsolved chamber CO₂ adds compressibility.
- **Wall slip.** Coherent wall stress saturates at `wallSlipStressPa + wallFrictionCoefficient · p`
  (1 MPa + 0.1 p): stiff crystal-rich magma slides as a plug on a marginal shear zone
  (Iverson et al. 2006) instead of locking up at Poiseuille stresses of 10⁷ Pa/m.
- **Fragmentation.** Brittle (Papale) on the *crystal-free* melt viscosity; viscous foams fragment when
  past the porosity threshold *and* the bubbles' viscous overpressure `4/3 η ε̇ α` exceeds
  `foamStrengthPa` (1 MPa, Spieler et al. 2004) *and* outgassing cannot keep up; fluid foams
  (η < 10⁴ Pa s) tear inertially. Within one conduit radius of the vent no criterion is applied (the
  foam vents freely); a choked flow whose gas would pass the porosity threshold on decompressing to
  ambient fragments at the vent (Hawaiian fountains).
- **Outgassing** drives vertical Darcy flow with the steeper of the flow's gradient and
  `(p − p_ambient)/depth`; lateral loss uses a pore pressure that is ambient above the water table
  (reported by the groundwater model) and hydrostatic below.
- **Chamber coupling.** Outflow is linearised about the last two conduit solutions (secant on the
  current branch, else about the magmastatic balance) and integrated exactly; re-solved when the
  drivers move by 2 %. The input and solution are saved, so restores are bit-exact. A forecast
  (failure now, branch by openness) is memoised on rounded inputs.
- **Chamber gas through an open conduit.** Exsolved gas the chamber vents (degassing timescale) rises
  through the conduit in proportion to its openness; in fluid magma it coalesces
  (`1/(1 + η/η_c)`) into slugs that burst at the surface *between* eruptions — persistent
  Strombolian activity emerges from an open conduit, fluid magma and a gas supply. During an eruption
  it joins the flow's own slugs (none in a fragmenting flow: churn) or, under a stiff plug, the
  trapped gas. A plug reseals once magma has risen through the cap depth.
- **Surface partition.** Clasts the jet carries are further limited by what the buoyant plume can hold
  up (plume height from Mastin 2009, buoyancy flux from `H = 8.2 F^¼ N^−¾`, rise speed
  `1.66 (F/z)^⅓` at a third of the height); everything coarser falls back in the fountain. A
  collapsing column sorts itself: ash < 1 mm feeds PDCs, coarser clasts fall back. In a crater
  flooded by open water, falling clasts are quenched (no clastogenic lava) and coherent lava is
  quench-granulated with share `1 − d/150 m` (hyaloclastite, added to the tuff ring); explosive
  magma–water efficiency is suppressed by `(1 − d/150 m)²` (Kokelaar 1986).
- **Style estimate** (`alert.EruptionClassifier`): bursts observed between eruptions count as
  activity (not a forecast); `PHREATIC` is defined but never produced (no magma-free steam blasts are
  modelled); groundwater-driven magma–water eruptions count towards `SURTSEYAN`.
