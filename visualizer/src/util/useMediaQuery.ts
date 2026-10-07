import { useEffect, useState } from 'react';

/** Viewports this short condense the HUD (matches the {@code short:} Tailwind variant in index.css). */
export const SHORT_VIEWPORT = '(max-height: 640px)';

/**
 * Phone layout: narrow screens, and touch screens too short for the desktop header (phones in
 * landscape). Panels become bottom sheets and the header collapses (Tailwind `phone:`, index.css).
 */
export const NARROW_VIEWPORT = '(max-width: 639.98px), (pointer: coarse) and (max-height: 500px)';

/** Touch screens (fingers, not a mouse or pen): bigger targets and gestures instead of hover. */
export const COARSE_POINTER = '(pointer: coarse)';

/** Whether a CSS media query matches, updated live. */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => (typeof window !== 'undefined' && window.matchMedia?.(query).matches) || false);
  useEffect(() => {
    const mq = window.matchMedia?.(query);
    if (!mq) return;
    const update = () => setMatches(mq.matches);
    update();
    mq.addEventListener('change', update);
    return () => mq.removeEventListener('change', update);
  }, [query]);
  return matches;
}
