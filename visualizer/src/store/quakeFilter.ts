/**
 * Which earthquakes are shown: the most recent N as of the current clock time (count-based,
 * the default), optionally also limited to a time window and a minimum magnitude, optionally fading
 * with age. Quakes after the current time (a replay cursor moved back) are never shown.
 */
export interface QuakeFilter {
  /** Show the last N earthquakes. */
  count: number;
  /** Only those within this many seconds of now (null = no time limit). */
  windowS: number | null;
  /** Only those of at least this magnitude (null = all). */
  minMagnitude: number | null;
  /** Fade older quakes (by rank within the shown set, or by age when a window is set). */
  fade: boolean;
}

export const DEFAULT_QUAKE_FILTER: QuakeFilter = { count: 50, windowS: null, minMagnitude: null, fade: false };

export const QUAKE_COUNT_MAX = 2000;

export interface QuakeLike {
  time: number;
  magnitude: number;
}

/**
 * The quakes to show, oldest first. `quakes` must be in time order (as events arrive); the scan runs
 * backwards from the end and stops after `count` matches, so it costs O(count) for the usual case.
 */
export function filterQuakes<T extends QuakeLike>(quakes: readonly T[], now: number, f: QuakeFilter): T[] {
  const out: T[] = [];
  const count = Math.max(0, Math.min(QUAKE_COUNT_MAX, Math.floor(f.count)));
  if (count === 0) return out;
  const from = f.windowS != null && f.windowS > 0 ? now - f.windowS : Number.NEGATIVE_INFINITY;
  for (let k = quakes.length - 1; k >= 0 && out.length < count; k--) {
    const q = quakes[k];
    if (q.time > now) continue;
    if (q.time < from) break;
    if (f.minMagnitude != null && q.magnitude < f.minMagnitude) continue;
    out.push(q);
  }
  return out.reverse();
}

/**
 * Brightness (0–1) of a shown quake: 1 without fading; with fading, by age within the window when
 * one is set, else by rank (newest 1, oldest 0.25).
 */
export function quakeFade(f: QuakeFilter, age: number, rank: number, shown: number): number {
  if (!f.fade) return 1;
  if (f.windowS != null && f.windowS > 0) return Math.max(0.15, 1 - Math.max(0, age) / f.windowS);
  return shown <= 1 ? 1 : 0.25 + 0.75 * (rank / (shown - 1));
}

const KEY = 'typhon.quakeFilter';

export function loadQuakeFilter(): QuakeFilter {
  try {
    const raw = window.localStorage.getItem(KEY);
    if (raw) return sanitize(JSON.parse(raw));
  } catch {
    // no storage or bad JSON: defaults
  }
  return { ...DEFAULT_QUAKE_FILTER };
}

export function saveQuakeFilter(f: QuakeFilter): void {
  try {
    window.localStorage.setItem(KEY, JSON.stringify(f));
  } catch {
    // ignore
  }
}

export function sanitize(v: unknown): QuakeFilter {
  const o = (v ?? {}) as Partial<QuakeFilter>;
  const num = (x: unknown) => (typeof x === 'number' && Number.isFinite(x) ? x : null);
  return {
    count: Math.max(0, Math.min(QUAKE_COUNT_MAX, Math.round(num(o.count) ?? DEFAULT_QUAKE_FILTER.count))),
    windowS: num(o.windowS) != null && o.windowS! > 0 ? o.windowS! : null,
    minMagnitude: num(o.minMagnitude),
    fade: o.fade === true,
  };
}
