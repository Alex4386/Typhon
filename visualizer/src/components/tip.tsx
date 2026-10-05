import type { ReactElement, ReactNode } from 'react';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';

/**
 * Tooltip around one element (rendered as the trigger itself, so no extra wrapper). `side`
 * defaults to below, which suits the header and top-left overlays.
 */
export function Tip({ content, children, side = 'bottom' }: { content: ReactNode; children: ReactElement; side?: 'top' | 'bottom' | 'left' | 'right' }) {
  if (!content) return children;
  return (
    <Tooltip>
      <TooltipTrigger render={children} />
      <TooltipContent side={side} className="max-w-sm whitespace-pre-line">
        {content}
      </TooltipContent>
    </Tooltip>
  );
}

/** Section of a side panel: small caps title and content. */
export function PanelSection({ title, children, action }: { title: ReactNode; children: ReactNode; action?: ReactNode }) {
  return (
    <section className="flex flex-col gap-2">
      <div className="flex items-center gap-2">
        <h3 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{title}</h3>
        <span className="flex-1" />
        {action}
      </div>
      {children}
    </section>
  );
}
