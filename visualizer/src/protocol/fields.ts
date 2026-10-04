// Field and codec identifiers for binary tile frames (protocol v1).
// Keep in sync with docs/protocol.md §5.

/** Spatial fields streamed per tile. Values are the u16 `fieldId` in the frame header. */
export const Field = {
  /** Ground surface elevation incl. deformation (m above datum). */
  SurfaceElevation: 1,
  /** Molten lava thickness (m). */
  LavaDepth: 2,
  /** Lava temperature (°C), meaningful where LavaDepth > 0. */
  LavaTemperature: 3,
  /** Surface water depth (m) — lakes, rivers, poured water, sea. */
  WaterDepth: 4,
  /** Pyroclastic density current flow depth (m). */
  PdcDepth: 5,
  /** Lahar flow depth (m). */
  LaharDepth: 6,
  /** Accumulated tephra fall deposit thickness (m). */
  AshDepth: 7,
  /** Temperature of the top subsurface cell (°C). */
  SurfaceTemperature: 8,
  /** Depth of the water table below the surface (m; negative = above ground / spring). */
  WaterTableDepth: 9,
  /** Unit id of the top stratigraphic layer (see UnitInfo). */
  TopUnit: 10,
  /** Cumulative vertical deformation (m) since session start. */
  Uplift: 11,
  /** Steam fraction of the top subsurface cell (0..1). */
  SteamFraction: 12,
} as const;
export type FieldId = (typeof Field)[keyof typeof Field];

export const FIELD_NAMES: Record<FieldId, string> = {
  1: 'surfaceElevation',
  2: 'lavaDepth',
  3: 'lavaTemperature',
  4: 'waterDepth',
  5: 'pdcDepth',
  6: 'laharDepth',
  7: 'ashDepth',
  8: 'surfaceTemperature',
  9: 'waterTableDepth',
  10: 'topUnit',
  11: 'uplift',
  12: 'steamFraction',
};

/** Payload encodings. Values are the u8 `codec` in the frame header. */
export const Codec = {
  /** u16 centimetres above `param0` (tile minimum, m), row-major, delta-coded (wrapping u16). */
  ElevationU16CmDelta: 1,
  /** Sparse: u32 count, then count × (u16 index, u16 millimetres). Absent cells are 0. */
  DepthU16MmSparse: 2,
  /** Dense: u16 millimetres per cell, row-major. */
  DepthU16MmDense: 3,
  /** u8 log-scaled temperature: T = exp(v/255 · ln(1 + TMAX)) − 1, TMAX = param0 (°C). */
  TemperatureU8Log: 4,
  /** u16 raw values (e.g. unit ids). */
  U16Raw: 5,
  /** f32 raw values. */
  F32Raw: 6,
  /** u8 linear: value = param0 + v/255 · (param1 − param0). */
  U8Linear: 7,
} as const;
export type CodecId = (typeof Codec)[keyof typeof Codec];

/** Default temperature ceiling for TemperatureU8Log (°C). */
export const DEFAULT_TMAX = 1300;
