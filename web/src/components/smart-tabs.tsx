'use client';

import { useState } from 'react';
import React from 'react';
import { cn } from '@/lib/utils';
import { Tabs } from '@/components/ui/tabs';

export default function SmartTabs({
  children,
  defaultValue,
  ...props
}: Parameters<typeof Tabs>[0]) {
  const [activeTab, setActiveTab] = useState(defaultValue || 'overview');
  return (
    <Tabs
      {...props}
      defaultValue={activeTab}
      onValueChange={setActiveTab}
      className={cn('space-y-2', props.className)}
    >
      {children}
    </Tabs>
  );
}
