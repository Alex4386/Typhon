'use client';

import { TabsList } from '@/components/ui/tabs';
import { cn } from '@/lib/utils';

interface WrappedTabListProps extends React.HTMLAttributes<HTMLDivElement> {
  children: React.ReactNode;
  style?: React.CSSProperties;
}

export function WrappedTabsList({
  className,
  children,
  ...props
}: WrappedTabListProps) {
  return (
    <TabsList
      className={cn(
        // Override the default w-fit to allow full width
        'w-fit max-w-full',
        // Enable horizontal scrolling
        'overflow-x-auto overflow-y-hidden',
        // Keep items in a row and prevent wrapping
        'flex items-center justify-start flex-nowrap',
        // Ensure minimum width for scroll to work
        'min-w-0',
        className
      )}
      style={{
        // Custom scrollbar styling for better UX
        scrollbarWidth: 'thin',
        scrollbarColor: '#cbd5e1 transparent',
        ...props.style,
      }}
      {...props}
    >
      {children}
    </TabsList>
  );
}