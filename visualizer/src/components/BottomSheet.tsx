import { useEffect, useRef, useState, type ReactNode } from 'react';
import { cn } from '@/lib/utils';
import { settle, snapHeight, type SheetSnap } from './sheetMath';

/**
 * A panel docked to the bottom of a phone screen: drag its handle to resize between peek, half and
 * full height (or flick it down to close). Its body scrolls by itself, so page scrolling stays off.
 * Not modal: the 3D view above stays usable.
 */
export function BottomSheet({
  open,
  snap,
  onSnap,
  onClose,
  label,
  children,
  className,
  z = 30,
}: {
  open: boolean;
  snap: SheetSnap;
  onSnap: (s: SheetSnap) => void;
  onClose: () => void;
  label: string;
  children: ReactNode;
  className?: string;
  z?: number;
}) {
  const [vh, setVh] = useState(() => (typeof window !== 'undefined' ? window.innerHeight : 800));
  const [drag, setDrag] = useState<number | null>(null);
  const start = useRef({ y: 0, h: 0, t: 0, lastY: 0, lastT: 0 });
  useEffect(() => {
    const onResize = () => setVh(window.visualViewport?.height ?? window.innerHeight);
    onResize();
    window.addEventListener('resize', onResize);
    window.visualViewport?.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      window.visualViewport?.removeEventListener('resize', onResize);
    };
  }, []);
  if (!open) return null;
  const height = drag ?? snapHeight(snap, vh);

  const onDown = (e: React.PointerEvent) => {
    (e.target as HTMLElement).setPointerCapture?.(e.pointerId);
    const now = performance.now();
    start.current = { y: e.clientY, h: height, t: now, lastY: e.clientY, lastT: now };
    setDrag(height);
  };
  const onMove = (e: React.PointerEvent) => {
    if (drag === null) return;
    const s = start.current;
    s.lastY = e.clientY;
    s.lastT = performance.now();
    setDrag(Math.max(0, Math.min(vh, s.h + (s.y - e.clientY))));
  };
  const onUp = (e: React.PointerEvent) => {
    if (drag === null) return;
    const s = start.current;
    const dt = Math.max(1, performance.now() - s.t);
    const velocity = (e.clientY - s.y) / dt; // px/ms, positive = down
    const moved = Math.abs(e.clientY - s.y);
    setDrag(null);
    if (moved < 6) {
      // a tap on the handle cycles peek → half → full → peek
      onSnap(snap === 'peek' ? 'half' : snap === 'half' ? 'full' : 'peek');
      return;
    }
    const r = settle(drag, velocity, vh);
    if (r === 'close') onClose();
    else onSnap(r);
  };

  return (
    <section
      role="dialog"
      aria-label={label}
      aria-modal={false}
      data-sheet
      className={cn(
        'fixed inset-x-0 bottom-0 flex flex-col overflow-hidden rounded-t-2xl border-t bg-card text-card-foreground shadow-[0_-8px_30px_rgba(0,0,0,0.45)]',
        drag === null && 'transition-[height] duration-200 ease-out',
        className,
      )}
      style={{ height, zIndex: z, paddingBottom: 'env(safe-area-inset-bottom)', paddingLeft: 'env(safe-area-inset-left)', paddingRight: 'env(safe-area-inset-right)' }}
    >
      <div
        className="flex h-11 shrink-0 cursor-grab touch-none items-center justify-center active:cursor-grabbing"
        onPointerDown={onDown}
        onPointerMove={onMove}
        onPointerUp={onUp}
        onPointerCancel={onUp}
        role="separator"
        aria-orientation="horizontal"
        aria-label={`Resize ${label}`}
        data-sheet-handle
      >
        <span className="h-1.5 w-10 rounded-full bg-muted-foreground/50" />
      </div>
      <div className="flex min-h-0 flex-1 flex-col overflow-y-auto overscroll-contain [touch-action:pan-y]">{children}</div>
    </section>
  );
}
