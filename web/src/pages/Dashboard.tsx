import { useConnectionStatus } from '@/hooks/useConnectionStatus';
import { Badge } from '@/components/ui/badge';
import {
  Flame,
  Activity,
  Moon,
  RefreshCw,
} from 'lucide-react';
import { VolcanoIcon } from '@/components/icons/VolcanoIcon';
import { Button } from '@/components/ui/button';
import { Link } from 'react-router-dom';

const STATUS_COLORS: Record<string, string> = {
  ERUPTING: 'bg-red-500',
  ERUPTION_IMMINENT: 'bg-orange-500',
  MAJOR_ACTIVITY: 'bg-amber-500',
  MINOR_ACTIVITY: 'bg-yellow-500',
  DORMANT: 'bg-blue-400',
  EXTINCT: 'bg-gray-400',
};

export default function Dashboard() {
  const { version, volcanoes, online, error, refresh } = useConnectionStatus();

  const erupting = volcanoes.filter((v) => v.status === 'ERUPTING').length;
  const active = volcanoes.filter((v) =>
    ['ERUPTION_IMMINENT', 'MAJOR_ACTIVITY', 'MINOR_ACTIVITY'].includes(v.status)
  ).length;
  const dormant = volcanoes.filter((v) => v.status === 'DORMANT').length;
  const extinct = volcanoes.filter((v) => v.status === 'EXTINCT').length;

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">Overview</h1>
          <p className="text-sm text-muted-foreground">
            {version
              ? `${version.plugin} v${version.version}`
              : 'Connecting...'}
          </p>
        </div>
        <Button variant="ghost" size="icon-sm" onClick={refresh}>
          <RefreshCw className="size-4" />
        </Button>
      </div>

      {error && (
        <div className="rounded-lg border border-destructive bg-destructive/10 p-3 text-sm text-destructive">
          {error}
        </div>
      )}

      {/* Stats */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <StatCard
          icon={<VolcanoIcon className="size-4" />}
          label="Total"
          value={volcanoes.length}
          color="text-info"
        />
        <StatCard
          icon={<Flame className="size-4" />}
          label="Erupting"
          value={erupting}
          color="text-red-400"
        />
        <StatCard
          icon={<Activity className="size-4" />}
          label="Active"
          value={active}
          color="text-amber-400"
        />
        <StatCard
          icon={<Moon className="size-4" />}
          label="Dormant"
          value={dormant + extinct}
          color="text-blue-400"
        />
      </div>

      {/* Volcano list */}
      <div>
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-lg font-semibold">Volcanoes</h2>
          <Link to="/volcanoes" className="text-sm text-muted-foreground hover:text-foreground transition-colors">
            View all
          </Link>
        </div>

        {volcanoes.length === 0 ? (
          <div className="rounded-lg border border-border bg-muted/50 p-8 text-center text-sm text-muted-foreground">
            {online ? 'No volcanoes registered.' : 'Server not connected.'}
          </div>
        ) : (
          <div className="space-y-1">
            {volcanoes.slice(0, 10).map((v) => (
              <Link
                key={v.name}
                to={`/volcanoes/${encodeURIComponent(v.name)}`}
                className="flex items-center justify-between rounded-lg px-4 py-3 hover:bg-muted/50 transition-colors group"
              >
                <div className="flex items-center gap-3 min-w-0">
                  <VolcanoIcon className="size-4 shrink-0 text-muted-foreground group-hover:text-foreground transition-colors" />
                  <div className="min-w-0">
                    <div className="font-medium truncate">{v.name}</div>
                    <div className="text-xs text-muted-foreground">
                      {v.style} &middot; {v.ventCount} vent{v.ventCount !== 1 ? 's' : ''}
                      {v.location.world && (
                        <span className="hidden sm:inline">
                          {' '}&middot; {v.location.world}
                        </span>
                      )}
                    </div>
                  </div>
                </div>
                <Badge variant="secondary" className="gap-1 shrink-0">
                  <span className={`inline-block size-2 rounded-full ${STATUS_COLORS[v.status] || 'bg-gray-400'}`} />
                  {v.status.replace(/_/g, ' ')}
                </Badge>
              </Link>
            ))}
            {volcanoes.length > 10 && (
              <div className="text-center py-2">
                <Link to="/volcanoes" className="text-sm text-muted-foreground hover:text-foreground">
                  +{volcanoes.length - 10} more
                </Link>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function StatCard({
  icon,
  label,
  value,
  color,
}: {
  icon: React.ReactNode;
  label: string;
  value: number;
  color: string;
}) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <div className="flex items-center gap-2 text-xs text-muted-foreground mb-2">
        {icon}
        <span className="uppercase tracking-wider">{label}</span>
      </div>
      <div className={`text-2xl font-bold ${color}`}>{value}</div>
    </div>
  );
}
