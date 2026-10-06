import { useEffect, useState } from 'react';

/** Viewports this short condense the HUD (matches the {@code short:} Tailwind variant in index.css). */
export const SHORT_VIEWPORT = '(max-height: 640px)';

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
