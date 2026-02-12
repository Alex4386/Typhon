import { Outlet } from 'react-router-dom';
import { AppSidebar } from '@/components/app-sidebar';
import {
  SidebarInset,
  SidebarProvider,
  SidebarTrigger,
} from '@/components/ui/sidebar';
import { Separator } from '@/components/ui/separator';
import { useConnectionStatus } from '@/hooks/useConnectionStatus';

function tpsColor(tps: number): string {
  if (tps >= 19) return 'text-green-500';
  if (tps >= 15) return 'text-yellow-500';
  return 'text-red-500';
}

function TpsIndicator() {
  const { tps } = useConnectionStatus();
  if (!tps) return null;

  return (
    <div className="flex items-center gap-3 text-xs tabular-nums">
      <span className={tpsColor(tps.tps1m)}>
        {tps.tps1m.toFixed(1)} TPS
      </span>
      {tps.mspt > 0 && (
        <span className="text-muted-foreground">
          {tps.mspt.toFixed(1)}ms
        </span>
      )}
    </div>
  );
}

export default function AppLayout() {
  return (
    <SidebarProvider>
      <AppSidebar />
      <SidebarInset>
        <header className="flex h-12 shrink-0 items-center gap-2 border-b border-border px-4">
          <SidebarTrigger className="-ml-1" />
          <Separator orientation="vertical" className="mr-2 !h-4" />
          <span className="text-sm text-muted-foreground">Typhon Dashboard</span>
          <div className="ml-auto">
            <TpsIndicator />
          </div>
        </header>
        <Outlet />
      </SidebarInset>
    </SidebarProvider>
  );
}
