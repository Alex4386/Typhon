/** Reads a JSON value from local storage (best effort). */
export function loadPref<T>(key: string, fallback: T, check: (v: unknown) => v is T): T {
  try {
    const raw = window.localStorage.getItem(key);
    if (raw != null) {
      const v: unknown = JSON.parse(raw);
      if (check(v)) return v;
    }
  } catch {
    // storage unavailable or bad JSON
  }
  return fallback;
}

export function savePref(key: string, v: unknown): void {
  try {
    window.localStorage.setItem(key, JSON.stringify(v));
  } catch {
    // ignore
  }
}

export const isBool = (v: unknown): v is boolean => typeof v === 'boolean';
export const isFlags = (v: unknown): v is Record<string, boolean> => !!v && typeof v === 'object' && !Array.isArray(v) && Object.values(v).every(isBool);
export const isClarity = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v) && v >= 2 && v <= 80;
export const PREF_KEYS = {
  waterClarity: 'typhon.waterClarity',
  hiddenCategories: 'typhon.hiddenCategories',
  showPerf: 'typhon.showPerf',
  autoQuality: 'typhon.autoQuality',
  showSimulatedArea: 'typhon.showSimulatedArea',
} as const;
