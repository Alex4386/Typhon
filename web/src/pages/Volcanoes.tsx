import { useConnectionStatus } from '@/hooks/useConnectionStatus';
import { Badge } from '@/components/ui/badge';
import { VolcanoIcon } from '@/components/icons/VolcanoIcon';
import { Link } from 'react-router-dom';

const STATUS_COLORS: Record<string, string> = {
  ERUPTING: 'bg-red-500',
  ERUPTION_IMMINENT: 'bg-orange-500',
  MAJOR_ACTIVITY: 'bg-amber-500',
  MINOR_ACTIVITY: 'bg-yellow-500',
  DORMANT: 'bg-blue-400',
  EXTINCT: 'bg-gray-400',
};

export default function Volcanoes() {
  const { volcanoes, online } = useConnectionStatus();

  return (
    <div className="p-6 space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Volcanoes</h1>
        <p className="text-sm text-muted-foreground">
          {volcanoes.length} volcano{volcanoes.length !== 1 ? 'es' : ''} registered
        </p>
      </div>

      {volcanoes.length === 0 ? (
        <div className="rounded-lg border border-border bg-muted/50 p-12 text-center text-sm text-muted-foreground">
          {online ? 'No volcanoes registered on this server.' : 'Server not connected.'}
        </div>
      ) : (
        <div className="rounded-lg border border-border overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/50">
                <th className="text-left px-4 py-3 font-medium text-muted-foreground">Name</th>
                <th className="text-left px-4 py-3 font-medium text-muted-foreground hidden sm:table-cell">Style</th>
                <th className="text-left px-4 py-3 font-medium text-muted-foreground hidden md:table-cell">Location</th>
                <th className="text-center px-4 py-3 font-medium text-muted-foreground">Vents</th>
                <th className="text-right px-4 py-3 font-medium text-muted-foreground">Status</th>
              </tr>
            </thead>
            <tbody>
              {volcanoes.map((v) => (
                <tr key={v.name} className="border-b border-border last:border-0 hover:bg-muted/30 transition-colors">
                  <td className="px-4 py-3">
                    <Link
                      to={`/volcanoes/${encodeURIComponent(v.name)}`}
                      className="flex items-center gap-2 font-medium hover:text-primary transition-colors"
                    >
                      <VolcanoIcon className="size-4 text-muted-foreground" />
                      {v.name}
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground hidden sm:table-cell">{v.style}</td>
                  <td className="px-4 py-3 text-muted-foreground text-xs hidden md:table-cell">
                    {v.location.world
                      ? `${v.location.world} (${v.location.x}, ${v.location.y}, ${v.location.z})`
                      : '--'}
                  </td>
                  <td className="px-4 py-3 text-center text-muted-foreground">{v.ventCount}</td>
                  <td className="px-4 py-3 text-right">
                    <Badge variant="secondary" className="gap-1">
                      <span className={`inline-block size-2 rounded-full ${STATUS_COLORS[v.status] || 'bg-gray-400'}`} />
                      {v.status.replace(/_/g, ' ')}
                    </Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
