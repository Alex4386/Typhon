/**
 * What kind of device this is, and the rendering budget that follows (phones and tablets have far
 * less GPU, memory and bandwidth than a desktop). One place decides; the store, the renderer, the water
 * and the tile streaming read the result.
 */

export interface DeviceInfo {
  /** Finger input (pointer: coarse). */
  touch: boolean;
  /** Shorter screen side (CSS px). */
  shortSide: number;
  /** navigator.deviceMemory (GB) when the browser tells. */
  memoryGB?: number;
  /** Logical cores. */
  cores?: number;
  /** Data saver on, or a 2G/3G-class connection. */
  slowNetwork: boolean;
  /** Mobile user agent (phones and tablets). */
  mobileUA: boolean;
}

export type DeviceClass = 'phone' | 'tablet' | 'desktop';

export interface DeviceTier {
  device: DeviceClass;
  /** Rendering quality preset to start with (the user can change it in View → Graphics). */
  quality: 'low' | 'medium' | 'high';
  /** Highest devicePixelRatio to render at (sharpness vs fill-rate). */
  dprCap: number;
  /** Cheaper water: fewer wave trains, no foam. */
  liteWater: boolean;
  /** At most this many crater-detail levels at once (each finer level is 4× the tiles). */
  maxDetailLevels: number;
  /** Detail is fetched only this close (fraction of the desktop distance). */
  detailReach: number;
  /** Tiles applied (and acknowledged to the server) per frame: fewer on phones and slow links, so the
   *  server's credit window (§5.4) paces the stream and memory spikes stay small. */
  tilesPerFrame: number;
  /** Fewer particles/billboards (fraction of the quality preset's counts). */
  particleScale: number;
}

export function detectDevice(win: Window = window): DeviceInfo {
  // every API is optional: older browsers, workers and test stubs lack some of them
  const nav = (win.navigator ?? {}) as Partial<Navigator> & { deviceMemory?: number; connection?: { saveData?: boolean; effectiveType?: string } };
  const conn = nav.connection;
  const touch = !!win.matchMedia?.('(pointer: coarse)')?.matches;
  const ua = nav.userAgent ?? '';
  return {
    touch,
    shortSide: Math.min(win.screen?.width ?? win.innerWidth ?? 1080, win.screen?.height ?? win.innerHeight ?? 1080),
    memoryGB: nav.deviceMemory,
    cores: nav.hardwareConcurrency,
    slowNetwork: !!conn?.saveData || /(^|-)(2g|3g)$/.test(conn?.effectiveType ?? ''),
    mobileUA: /Android|iPhone|iPad|iPod|Mobile/i.test(ua) || (/Macintosh/.test(ua) && (nav.maxTouchPoints ?? 0) > 1),
  };
}

/** The budget for a device. */
export function deviceTier(d: DeviceInfo): DeviceTier {
  const mobile = d.mobileUA || d.touch;
  const device: DeviceClass = !mobile ? 'desktop' : d.shortSide < 600 ? 'phone' : 'tablet';
  const lowMemory = d.memoryGB !== undefined && d.memoryGB <= 3;
  const tiles = (n: number) => (d.slowNetwork ? Math.min(n, 8) : n);
  if (device === 'phone' || (device === 'tablet' && lowMemory)) {
    return { device, quality: 'low', dprCap: 1.5, liteWater: true, maxDetailLevels: 1, detailReach: 0.6, tilesPerFrame: tiles(24), particleScale: 0.5 };
  }
  if (device === 'tablet') {
    return { device, quality: 'medium', dprCap: 1.5, liteWater: true, maxDetailLevels: 1, detailReach: 0.8, tilesPerFrame: tiles(48), particleScale: 0.7 };
  }
  return { device, quality: lowMemory ? 'low' : 'medium', dprCap: 2, liteWater: false, maxDetailLevels: 3, detailReach: 1, tilesPerFrame: tiles(100_000), particleScale: 1 };
}

/**
 * Battery saver: on when the battery is discharging and below 20 % (Battery Status API, where the
 * browser has it). The frame scheduler then renders at DPR 1 and halves the ambient animation rate.
 */
export const powerSave = { on: false };

interface BatteryLike {
  charging: boolean;
  level: number;
  addEventListener(type: string, fn: () => void): void;
}

/** Starts watching the battery (no-op without the API). */
export function watchBattery(nav: Navigator = navigator): void {
  const get = (nav as Navigator & { getBattery?: () => Promise<BatteryLike> }).getBattery;
  if (!get) return;
  get
    .call(nav)
    .then((b) => {
      const update = () => (powerSave.on = !b.charging && b.level < 0.2);
      update();
      b.addEventListener('chargingchange', update);
      b.addEventListener('levelchange', update);
    })
    .catch(() => undefined);
}

/** A particle/billboard budget scaled to this device (never below a handful). */
export function scaledBudget(n: number): number {
  return Math.max(8, Math.round(n * currentTier().particleScale));
}

let cached: DeviceTier | null = null;

/** This device's tier (computed once; `?device=phone|tablet|desktop` overrides for testing). */
export function currentTier(): DeviceTier {
  if (cached) return cached;
  if (typeof window === 'undefined') return (cached = deviceTier({ touch: false, shortSide: 1080, slowNetwork: false, mobileUA: false }));
  const forced = new URLSearchParams(window.location.search).get('device');
  const d = detectDevice();
  if (forced === 'phone') cached = deviceTier({ ...d, touch: true, mobileUA: true, shortSide: 390 });
  else if (forced === 'tablet') cached = deviceTier({ ...d, touch: true, mobileUA: true, shortSide: 820, memoryGB: undefined });
  else if (forced === 'desktop') cached = deviceTier({ ...d, touch: false, mobileUA: false, shortSide: 1080 });
  else cached = deviceTier(d);
  return cached;
}
