/**
 * Frame statistics of the 3D view, written once per rendered frame by {@link PerfProbe} and read by
 * the perf HUD and the probe scripts (no React state: reading them never triggers a render).
 */
export const perfStats = {
  /** Rendered frames per second over the last second (0 while idle with on-demand rendering). */
  fps: 0,
  /** Mean wall time between rendered frames over the last second (ms). */
  frameMs: 0,
  /** Mean JS time of a frame's update callbacks plus render submission (ms). */
  cpuMs: 0,
  /** Draw calls, triangles, geometries, textures and shader programs of the last frame. */
  calls: 0,
  triangles: 0,
  geometries: 0,
  textures: 0,
  programs: 0,
  /** Entity markers drawn (instances, all kinds). */
  entities: 0,
  /** Frames rendered since the page loaded. */
  frames: 0,
  /** Current device-pixel ratio (adaptive quality lowers it under load). */
  dpr: 1,
};

export type PerfStats = typeof perfStats;
