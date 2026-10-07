# Literature basis of the Typhon engine

Every physical model in `engine/` cites the work it is built on. This page collects them by
subsystem. Entries marked **✓** were checked against the publisher or an index (author, year,
title, journal, volume, pages). Entries marked **(unverified)** are cited from memory: author,
year and topic are believed correct, but volume and page numbers were **not** checked and are left
out rather than guessed.

Where a model departs from the literature (simplifications, grid resolution), the class javadoc
says so; see also `engine/README.md` ("Units").

## Magma chamber and conduit — `magma/`

| Used for | Reference |
|---|---|
| Open-chamber overpressure, failure at the wall's tensile strength | ✓ Blake, S. (1981). Volcanism and the dynamics of open magma chambers. *Nature* 289, 783–785. doi:10.1038/289783a0 |
| Exsolved volatiles raise chamber compressibility | Huppert, H. E. & Woods, A. W. (2002). The role of volatiles in magma chamber dynamics. *Nature* 420 (unverified pages) |
| Critical overpressure range (10–40 MPa) | Jellinek, A. M. & DePaolo, D. J. (2003). *Bull. Volcanol.* (unverified) |
| Poiseuille conduit flow; H₂O solubility ∝ √P | ✓ Wilson, L. & Head, J. W. (1981). Ascent and eruption of basaltic magma on the Earth and Moon. *J. Geophys. Res.* 86(B4), 2971–3001. doi:10.1029/JB086iB04p02971 |
| Exponential decline of effusion rate | Wadge, G. (1981). *J. Volcanol. Geotherm. Res.* (unverified) |
| Melt viscosity (VFT, composition dependent) | ✓ Giordano, D., Russell, J. K. & Dingwell, D. B. (2008). Viscosity of magmatic liquids: A model. *Earth Planet. Sci. Lett.* 271, 123–134 |
| Hydrous rhyolite viscosity | Hess, K.-U. & Dingwell, D. B. (1996). *Am. Mineral.* (unverified) |
| Crystal correction (Einstein–Roscoe) | Roscoe, R. (1952). *Br. J. Appl. Phys.* (unverified); maximum packing after Marsh, B. D. (1981) (unverified) |
| Strain-rate (brittle) fragmentation | ✓ Papale, P. (1999). Strain-induced magma fragmentation in explosive eruptions. *Nature* 397, 425–428. doi:10.1038/17109 |
| Gas volume fraction at fragmentation | Sparks, R. S. J. (1978). *J. Volcanol. Geotherm. Res.* (unverified) |

## Dikes — `dike/`

| Used for | Reference |
|---|---|
| Buoyancy/pressure-driven dike ascent, opening and stalling | ✓ Rubin, A. M. (1995). Propagation of magma-filled cracks. *Annu. Rev. Earth Planet. Sci.* 23, 287–336. doi:10.1146/annurev.ea.23.050195.001443 |
| Slot (lubrication) flow in a dike | Lister, J. R. & Kerr, R. C. (1991). *J. Geophys. Res.* (unverified) |

## Seismicity and alert levels — `seismic/`, `alert/`

| Used for | Reference |
|---|---|
| Event classes (VT, LP, tremor, explosion), swarm b-values | ✓ McNutt, S. R. (2005). Volcanic seismology. *Annu. Rev. Earth Planet. Sci.* 33, 461–491. doi:10.1146/annurev.earth.33.092203.122459 |
| Long-period events | Chouet, B. (1996). *Nature* (unverified) |
| Magnitude–frequency law | Gutenberg, B. & Richter, C. F. (1944). *Bull. Seismol. Soc. Am.* (unverified) |
| Energy–magnitude relation (log E = 1.5 M + 4.8) | Gutenberg, B. & Richter, C. F. (1956) (unverified) |
| b-value maximum-likelihood estimate | Aki, K. (1965). *Bull. Earthq. Res. Inst.* (unverified) |
| Accelerating precursory seismicity (failure forecast) | Voight, B. (1988). *Nature* (unverified); Kilburn, C. R. J. (2003). *J. Volcanol. Geotherm. Res.* (unverified) |
| RSAM | ✓ Endo, E. T. & Murray, T. (1991). Real-time Seismic Amplitude Measurement (RSAM): a volcano monitoring and prediction tool. *Bull. Volcanol.* 53, 533–545. doi:10.1007/BF00298154 |
| Amplitude attenuation with distance | Battaglia, J. & Aki, K. (2003). *J. Geophys. Res.* (unverified) |
| Alert-level system | Gardner, C. A. & Guffanti, M. C. (2006). U.S. Geological Survey Fact Sheet 2006-3139 (unverified) |

## Ground deformation — `deformation/`

| Used for | Reference |
|---|---|
| Point pressure source (Mogi) | ✓ Mogi, K. (1958). Relations between the eruptions of various volcanoes and the deformations of the ground surfaces around them. *Bull. Earthq. Res. Inst.* 36, 99–134 |
| Dike opening (2D elastic dislocation, parametric) | Okada, Y. (1985). *Bull. Seismol. Soc. Am.* (unverified) — the model uses a simplified plane-strain form, not Okada's full solution |

## Lava flows — `lava/`

| Used for | Reference |
|---|---|
| Bingham cellular automaton (MAGFLOW) | ✓ Del Negro, C., Fortuna, L., Hérault, A. & Vicari, A. (2008). Simulations of the 2004 lava flow at Etna volcano using the MAGFLOW cellular automata model. *Bull. Volcanol.* 70, 805–812. doi:10.1007/s00445-007-0168-8 |
| Crust growth ∝ √t, inflation, tubes | ✓ Hon, K., Kauahikaua, J., Denlinger, R. & Mackay, K. (1994). Emplacement and inflation of pahoehoe sheet flows: Observations and measurements of active lava flows on Kilauea Volcano, Hawaii. *Geol. Soc. Am. Bull.* 106(3), 351–370 |

## Tephra, eruption columns and ash — `tephra/`, `massflow/ColumnCollapse`

| Used for | Reference |
|---|---|
| Plume height vs. eruption rate (H = 2.00 V̇^0.241); eruption source parameters | ✓ Mastin, L. G. et al. (2009). A multidisciplinary effort to assign realistic source parameters to models of volcanic ash-cloud transport and dispersion during eruptions. *J. Volcanol. Geotherm. Res.* 186(1–2), 10–21 |
| Eruption-column model and column collapse | ✓ Woods, A. W. (1988). The fluid dynamics and thermodynamics of eruption columns. *Bull. Volcanol.* 50, 169–193. doi:10.1007/BF01079681 |

## Pyroclastic density currents and lahars — `massflow/`

| Used for | Reference |
|---|---|
| Voellmy friction law | Voellmy, A. (1955). *Schweizerische Bauzeitung* (unverified) |
| Voellmy–Salm rheology for mass flows | Salm, B. (1993). *Ann. Glaciol.* (unverified) |

## Subsurface heat, groundwater and surface water — `subsurface/`, `geothermal/`

| Used for | Reference |
|---|---|
| Boiling point for depth (hydrostatic) | ✓ Haas, J. L. (1971). The effect of salinity on the maximum thermal gradient of a hydrothermal system at hydrostatic pressure. *Econ. Geol.* 66(6), 940–946. doi:10.2113/gsecongeo.66.6.940 |
| Unconfined groundwater (Dupuit assumption) | Dupuit, J. (1863). *Études théoriques et pratiques sur le mouvement des eaux* (unverified) |
| Local-inertial shallow water for surface water | ✓ Bates, P. D., Horritt, M. S. & Fewtrell, T. J. (2010). A simple inertial formulation of the shallow water equations for efficient two-dimensional flood inundation modelling. *J. Hydrol.* 387, 33–45 |
| Hydrothermal manifestations, Yellowstone heat discharge | ✓ Fournier, R. O. (1989). Geochemistry and dynamics of the Yellowstone National Park hydrothermal system. *Annu. Rev. Earth Planet. Sci.* 17, 13–53. doi:10.1146/annurev.ea.17.050189.000305 |
| Geyser-basin shallow geology (permeable glacial sediment and sinter over rhyolite) | ✓ White, D. E., Fournier, R. O., Muffler, L. J. P. & Truesdell, A. H. (1975). Physical results of research drilling in thermal areas of Yellowstone National Park, Wyoming. USGS Prof. Paper 892, 70 p. doi:10.3133/pp892 |
| Hydraulic conductivity of materials (gravel, basalt, sediment, …) | Freeze, R. A. & Cherry, J. A. (1979). *Groundwater*, Table 2.2 (unverified) |
| Water table as a subdued replica of topography | Haitjema, H. M. & Mitchell-Bruker, S. (2005). Are water tables a subdued replica of the topography? *Ground Water* 43(6), 781–786 (unverified) |
| Kīlauea summit water table and the 2019–2020 water lake (lava boiled it off in ~1.5 h) | ✓ Nadeau, P. A. et al. (2024). Chemistry, growth, and fate of the unique, short-lived (2019–2020) water lake at the summit of Kīlauea Volcano, Hawaii. *Geochem. Geophys. Geosyst.* doi:10.1029/2023GC011154 |

## Validation references (simulator presets)

Used by `simulator validate` (see `simulator/README.md`) to judge the presets.

| Preset | Reference |
|---|---|
| Kīlauea | ✓ Neal, C. A. et al. (2019). The 2018 rift eruption and summit collapse of Kīlauea Volcano. *Science* 363, 367–374. doi:10.1126/science.aav7046; Poland, M. P., Miklius, A. & Montgomery-Brown, E. K. (2014), USGS Prof. Paper 1801 (unverified) |
| Stromboli | Ripepe, M. et al. (2008) (unverified) |
| Mount St. Helens | ✓ Carey, S. & Sigurdsson, H. (1985). The May 18, 1980 eruption of Mount St. Helens: 2. Modeling of dynamics of the Plinian phase. *J. Geophys. Res.* 90(B4), 2948–. doi:10.1029/JB090iB04p02948 (end page unverified); Schilling, S. P. et al. (2008), USGS Prof. Paper 1750 (unverified); Swanson, D. A. & Holcomb, R. T. (1990) (unverified) |
| Pinatubo | ✓ Holasek, R. E., Self, S. & Woods, A. W. (1996). Satellite observations and interpretation of the 1991 Mount Pinatubo eruption plumes. *J. Geophys. Res.* 101(B12), 27635–27655. doi:10.1029/96JB01179; ✓ Mastin et al. (2009), above (Table 1: Pinatubo 1991, 35–40 km, 0.8–1.6 km³ DRE in ~3 h); Paladio-Melosantos, M. L. et al. (1996), in *Fire and Mud* (unverified); Newhall, C. G. & Punongbayan, R. S. (eds.) (1996), *Fire and Mud* (unverified) |
| Surtsey | Thorarinsson, S. (1967). *Surtsey: The New Island in the North Atlantic* (unverified); Jakobsson, S. P. et al. (2000), *Surtsey Research* (unverified) |
| Yellowstone | ✓ Fournier (1989), above; Christiansen, R. L. (2001), USGS Prof. Paper 729-G (unverified) |
