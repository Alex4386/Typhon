import { useEffect, useState } from 'react';
import { Loader2 } from 'lucide-react';
import { cn } from '@/lib/utils';
import { OVERLAY } from '../panels/Overlay';
import { Field } from '../protocol/fields';
import { perfStats } from '../scene/perf';
import { tileStore, useStore } from '../store/store';
import { loadingState } from './loading';

/**
 * A small pill at the top of the view while the server connection, the world or its terrain is still
 * loading, so an empty view never looks broken. Tile data lives outside React state, so it is polled.
 */
export function LoadingIndicator() {
  const status = useStore((s) => s.status);
  const world = useStore((s) => s.world);
  const [text, setText] = useState<string | null>(null);
  useEffect(() => {
    const tick = () => {
      const b = world?.tiles;
      const expected = b ? (b.maxTx - b.minTx + 1) * (b.maxTy - b.minTy + 1) : 0;
      const received = tileStore.get(Field.SurfaceElevation)?.size ?? 0;
      setText(loadingState(status, world != null, expected, received, perfStats.rebuildQueue));
    };
    tick();
    const id = window.setInterval(tick, 300);
    return () => window.clearInterval(id);
  }, [status, world]);
  if (!text) return null;
  return (
    <div className={cn(OVERLAY, 'flex shrink-0 items-center gap-2 px-3 py-1.5 text-xs text-muted-foreground')} role="status" aria-live="polite">
      <Loader2 className="size-3.5 animate-spin" aria-hidden />
      {text}
    </div>
  );
}
