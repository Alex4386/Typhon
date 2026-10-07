/** Resting heights of a bottom sheet on a phone. */
export type SheetSnap = 'peek' | 'half' | 'full';

export const SHEET_SNAPS: SheetSnap[] = ['peek', 'half', 'full'];

/** Height (px) of a snap point in a viewport `vh` px tall; `top` is what must stay visible above (header). */
export function snapHeight(snap: SheetSnap, vh: number, top = 56): number {
  const full = Math.max(160, vh - top);
  switch (snap) {
    case 'peek':
      return Math.min(full, Math.max(140, Math.round(vh * 0.28)));
    case 'half':
      return Math.min(full, Math.round(vh * 0.55));
    case 'full':
      return full;
  }
}

/**
 * Where a released drag settles: the nearest snap, biased one step in the direction of a quick flick
 * (`velocity` px/ms, positive = downwards); `'close'` when dragged well below the peek height.
 */
export function settle(height: number, velocity: number, vh: number, top = 56): SheetSnap | 'close' {
  const heights = SHEET_SNAPS.map((s) => snapHeight(s, vh, top));
  if (height < heights[0] * 0.6 || (velocity > 1.2 && height < heights[0] * 1.1)) return 'close';
  let best = 0;
  for (let k = 1; k < heights.length; k++) if (Math.abs(heights[k] - height) < Math.abs(heights[best] - height)) best = k;
  if (Math.abs(velocity) > 0.6) {
    // a flick moves one snap in its direction from where the drag ended
    const dir = velocity > 0 ? -1 : 1;
    let k = heights.findIndex((h) => h >= height);
    if (k < 0) k = heights.length - 1;
    const target = dir > 0 ? Math.max(k, best) : Math.min(best, Math.max(0, k - 1));
    best = Math.max(0, Math.min(heights.length - 1, target));
  }
  return SHEET_SNAPS[best];
}
