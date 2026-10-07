import { useEffect, useState, type ReactNode } from 'react';
import { X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { OVERLAY } from '../panels/Overlay';
import { useStore, type HudToast } from '../store/store';
import { NARROW_VIEWPORT, SHORT_VIEWPORT, useMediaQuery } from '../util/useMediaQuery';

/**
 * Layout of everything floating over the 3D view. Three columns (left, centre, right), each a
 * top-to-bottom stack: the top panel takes what height is left and scrolls inside, the bottom bars
 * keep their size. Panels in one column therefore never overlap. The centre column (toasts, tool
 * hint, camera readout) keeps at least 18 rem (or 40 %); in narrow views the side panels narrow
 * instead. Empty space passes clicks to the view.
 */
export function Hud({
  topLeft,
  bottomLeft,
  top,
  bottom,
  topRight,
  bottomRight,
}: {
  topLeft?: ReactNode;
  bottomLeft?: ReactNode;
  top?: ReactNode;
  bottom?: ReactNode;
  topRight?: ReactNode;
  bottomRight?: ReactNode;
}) {
  const narrow = useMediaQuery(NARROW_VIEWPORT);
  if (narrow) {
    // phones: toasts across the top, the status pill under them, bars along the bottom; the Inspector and
    // panels are bottom sheets (App), so nothing sits on the right
    return (
      <div
        className="pointer-events-none absolute inset-0 z-10 flex flex-col justify-between gap-2 p-2 pt-[max(0.5rem,env(safe-area-inset-top))] pr-[max(0.5rem,env(safe-area-inset-right))] pl-[max(0.5rem,env(safe-area-inset-left))]"
        data-hud
        data-hud-narrow
      >
        <div className="flex min-h-0 flex-col items-stretch gap-2">
          {top}
          <div className="flex min-h-0 items-start gap-2">{topLeft}</div>
        </div>
        <div className="flex flex-col items-stretch gap-2">
          {bottom}
          <div className="flex items-end justify-between gap-2">
            {bottomLeft}
            {bottomRight}
          </div>
        </div>
      </div>
    );
  }
  return (
    <div className="pointer-events-none absolute inset-0 z-10 grid grid-cols-[minmax(0,auto)_minmax(min(18rem,40%),1fr)_minmax(0,auto)] gap-3 p-3 short:gap-2 short:p-2" data-hud>
      <Column top={topLeft} bottom={bottomLeft} align="items-start" />
      <Column top={top} bottom={bottom} align="items-center" />
      <Column top={topRight} bottom={bottomRight} align="items-end" />
    </div>
  );
}

function Column({ top, bottom, align }: { top?: ReactNode; bottom?: ReactNode; align: string }) {
  return (
    <div className={cn('flex min-h-0 min-w-0 flex-col justify-between gap-2', align)}>
      <div className={cn('flex min-h-0 max-w-full flex-col gap-2', align)}>{top}</div>
      <div className={cn('flex max-w-full shrink-0 flex-col justify-end gap-2', align)}>{bottom}</div>
    </div>
  );
}

const TONE: Record<HudToast['tone'], string> = {
  info: '',
  warn: 'border-amber-500/60',
  alert: 'border-red-500/70 bg-red-950/80',
};

/** Notifications at the top of the centre column (one at a time on short viewports). */
export function HudToasts() {
  const toasts = useStore((s) => s.toasts);
  const short = useMediaQuery(SHORT_VIEWPORT);
  const narrow = useMediaQuery(NARROW_VIEWPORT);
  const shown = toasts.slice(short || narrow ? -1 : -3);
  return (
    <div className="flex w-[28rem] max-w-full min-h-0 flex-col gap-2" aria-live="polite" role="log" aria-label="Notifications">
      {shown.map((t) => (
        <Toast key={t.id} t={t} />
      ))}
    </div>
  );
}

function Toast({ t }: { t: HudToast }) {
  const dismiss = useStore((s) => s.dismissToast);
  const [expanded, setExpanded] = useState(false);
  const [hover, setHover] = useState(false);
  useEffect(() => {
    if (hover) return; // reading it: keep it
    const timer = setTimeout(() => dismiss(t.id), t.duration);
    return () => clearTimeout(timer);
  }, [t.id, t.serial, t.duration, hover, dismiss]);
  return (
    <div
      className={cn(OVERLAY, 'flex shrink-0 items-start gap-2 px-3 py-2 text-sm', TONE[t.tone])}
      role={t.tone === 'alert' ? 'alert' : 'status'}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
    >
      <p
        className={cn('min-w-0 flex-1 cursor-default break-words', !expanded && 'line-clamp-3')}
        title={expanded ? undefined : 'Click to read all'}
        onClick={() => setExpanded(!expanded)}
      >
        {t.text}
      </p>
      {t.action && (
        <Button
          size="xs"
          variant="secondary"
          onClick={() => {
            t.action!.onClick();
            dismiss(t.id);
          }}
        >
          {t.action.label}
        </Button>
      )}
      <Button size="icon-xs" variant="ghost" aria-label="Dismiss" onClick={() => dismiss(t.id)}>
        <X />
      </Button>
    </div>
  );
}
