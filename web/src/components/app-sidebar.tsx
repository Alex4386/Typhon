import { NavLink, useLocation } from 'react-router-dom';
import {
  LayoutDashboard,
  Settings,
  Wifi,
  WifiOff,
} from 'lucide-react';
import { VolcanoIcon } from '@/components/icons/VolcanoIcon';
import { useConnectionStatus } from '@/hooks/useConnectionStatus';
import { Badge } from '@/components/ui/badge';
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarRail,
} from '@/components/ui/sidebar';

const NAV_ITEMS = [
  { to: '/', icon: LayoutDashboard, label: 'Overview' },
  { to: '/volcanoes', icon: VolcanoIcon, label: 'Volcanoes' },
  { to: '/settings', icon: Settings, label: 'Settings' },
];

export function AppSidebar(props: React.ComponentProps<typeof Sidebar>) {
  const { online } = useConnectionStatus();
  const location = useLocation();

  return (
    <Sidebar {...props}>
      <SidebarHeader>
        <div className="flex items-center gap-2 px-2 py-1">
          <div className="flex size-7 items-center justify-center rounded-md bg-primary text-primary-foreground">
            <VolcanoIcon className="size-4" />
          </div>
          <span className="text-base font-bold tracking-wide">Typhon</span>
        </div>
      </SidebarHeader>
      <SidebarContent>
        <SidebarGroup>
          <SidebarGroupContent>
            <SidebarMenu>
              {NAV_ITEMS.map(({ to, icon: Icon, label }) => {
                const isActive = to === '/'
                  ? location.pathname === '/'
                  : location.pathname.startsWith(to);
                return (
                  <SidebarMenuItem key={to}>
                    <SidebarMenuButton asChild isActive={isActive}>
                      <NavLink to={to}>
                        <Icon />
                        <span>{label}</span>
                      </NavLink>
                    </SidebarMenuButton>
                  </SidebarMenuItem>
                );
              })}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      </SidebarContent>
      <SidebarFooter>
        <div className="px-2 py-1">
          {online ? (
            <Badge variant="secondary" className="w-full justify-center gap-1.5 py-1">
              <Wifi className="size-3" /> Server Online
            </Badge>
          ) : (
            <Badge variant="outline" className="w-full justify-center gap-1.5 py-1">
              <WifiOff className="size-3" /> Offline
            </Badge>
          )}
        </div>
      </SidebarFooter>
      <SidebarRail />
    </Sidebar>
  );
}
