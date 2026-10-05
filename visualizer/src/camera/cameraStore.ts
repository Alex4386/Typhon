import { create } from 'zustand';
import type { XY } from '../protocol/messages';
import { decodePose, parseBookmarks, serializeBookmarks, type Bookmark, type CameraMode, type CameraPose, type FollowTarget } from './math';

/** What the camera rig should do next (consumed once, identified by `seq`). */
export type CameraRequest =
  | { kind: 'mode'; mode: CameraMode }
  | { kind: 'pose'; pose: CameraPose; instant?: boolean }
  | { kind: 'frame'; what: 'volcano' | 'plume' | 'overview' | 'section' }
  | { kind: 'flyTo'; at: XY }
  | { kind: 'focus'; point: [number, number, number] };

/** Live camera readout for the HUD (scene → world metres), updated a few times a second. */
export interface CameraReadout {
  /** World east/north (m). */
  x: number;
  y: number;
  /** Elevation above the model datum / sea level (m, without vertical exaggeration). */
  altitude: number;
  /** Height above the ground below the camera (m). */
  aboveGround: number;
  heading: number;
  pitch: number;
  /** Current movement speed (m/s), 0 when still. */
  speed: number;
  /** Vertical field of view (deg) and viewport aspect, for the minimap frustum. */
  fov: number;
  aspect: number;
}

interface CameraStore {
  mode: CameraMode;
  followTarget: FollowTarget;
  /** Wheel / [ ] multiplier on fly speed. */
  speedMultiplier: number;
  /** Minimum height above ground in fly mode (m). */
  clearance: number;
  allowUnderground: boolean;
  tourSpeed: number;
  bookmarks: Bookmark[];
  helpOpen: boolean;
  pointerLocked: boolean;
  readout: CameraReadout | null;
  request: CameraRequest | null;
  requestSeq: number;
  /** Pose to start from (URL or last session), consumed by the rig on first frame. */
  initialPose: CameraPose | null;

  set: (partial: Partial<CameraStore>) => void;
  requestCamera: (r: CameraRequest) => void;
}

const SETTINGS_KEY = 'typhon.camera.settings';

function storage(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

interface Settings {
  speedMultiplier: number;
  clearance: number;
  allowUnderground: boolean;
  tourSpeed: number;
}

function loadSettings(): Settings {
  const d: Settings = { speedMultiplier: 1, clearance: 3, allowUnderground: false, tourSpeed: 1 };
  try {
    const raw = storage()?.getItem(SETTINGS_KEY);
    if (!raw) return d;
    const s = JSON.parse(raw) as Partial<Settings>;
    return {
      speedMultiplier: typeof s.speedMultiplier === 'number' && s.speedMultiplier > 0 ? s.speedMultiplier : d.speedMultiplier,
      clearance: typeof s.clearance === 'number' && s.clearance >= 0 ? s.clearance : d.clearance,
      allowUnderground: s.allowUnderground === true,
      tourSpeed: typeof s.tourSpeed === 'number' && s.tourSpeed > 0 ? s.tourSpeed : d.tourSpeed,
    };
  } catch {
    return d;
  }
}

const initial = loadSettings();

export const useCamera = create<CameraStore>((set, get) => ({
  mode: 'orbit',
  followTarget: 'vent',
  speedMultiplier: initial.speedMultiplier,
  clearance: initial.clearance,
  allowUnderground: initial.allowUnderground,
  tourSpeed: initial.tourSpeed,
  bookmarks: [],
  helpOpen: false,
  pointerLocked: false,
  readout: null,
  request: null,
  requestSeq: 0,
  initialPose: decodePose(new URLSearchParams(window.location.search).get('cam')),

  set: (partial) => {
    set(partial);
    if ('speedMultiplier' in partial || 'clearance' in partial || 'allowUnderground' in partial || 'tourSpeed' in partial) {
      const s = get();
      try {
        storage()?.setItem(
          SETTINGS_KEY,
          JSON.stringify({ speedMultiplier: s.speedMultiplier, clearance: s.clearance, allowUnderground: s.allowUnderground, tourSpeed: s.tourSpeed }),
        );
      } catch {
        // ignore
      }
    }
  },
  requestCamera: (r) => set({ request: r, requestSeq: get().requestSeq + 1 }),
}));

// ── Per-world persistence (bookmarks and last pose) ──

const bookmarkKey = (world: string) => `typhon.camera.bookmarks.${world}`;
const poseKey = (world: string) => `typhon.camera.pose.${world}`;

export function loadBookmarks(world: string): Bookmark[] {
  try {
    return parseBookmarks(storage()?.getItem(bookmarkKey(world)));
  } catch {
    return [];
  }
}

export function saveBookmarks(world: string, list: Bookmark[]): void {
  try {
    storage()?.setItem(bookmarkKey(world), serializeBookmarks(list));
  } catch {
    // ignore
  }
}

export function loadLastPose(world: string): CameraPose | null {
  try {
    return decodePose(storage()?.getItem(poseKey(world)));
  } catch {
    return null;
  }
}

export function saveLastPose(world: string, encoded: string): void {
  try {
    storage()?.setItem(poseKey(world), encoded);
  } catch {
    // ignore
  }
}

export function prefersReducedMotion(): boolean {
  try {
    return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
  } catch {
    return false;
  }
}
