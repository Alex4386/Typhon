import { useConnectionStatus } from '@/hooks/useConnectionStatus';
import { Badge } from '@/components/ui/badge';
import { VolcanoIcon } from '@/components/icons/VolcanoIcon';
import { Link } from 'react-router-dom';
import {
  Table, TableHeader, TableBody, TableHead, TableRow, TableCell,
} from '@/components/ui/table';

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
          <Table>
            <TableHeader>
              <TableRow className="bg-muted/50">
                <TableHead>Name</TableHead>
                <TableHead className="hidden sm:table-cell">Style</TableHead>
                <TableHead className="hidden md:table-cell">Location</TableHead>
                <TableHead className="text-center">Vents</TableHead>
                <TableHead className="text-right">Status</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {volcanoes.map((v) => (
                <TableRow key={v.name}>
                  <TableCell>
                    <Link
                      to={`/volcanoes/${encodeURIComponent(v.name)}`}
                      className="flex items-center gap-2 font-medium hover:text-primary transition-colors"
                    >
                      <VolcanoIcon className="size-4 text-muted-foreground" />
                      {v.name}
                    </Link>
                  </TableCell>
                  <TableCell className="text-muted-foreground hidden sm:table-cell">{v.style}</TableCell>
                  <TableCell className="text-muted-foreground text-xs hidden md:table-cell">
                    {v.location.world
                      ? `${v.location.world} (${v.location.x}, ${v.location.y}, ${v.location.z})`
                      : '--'}
                  </TableCell>
                  <TableCell className="text-center text-muted-foreground">{v.ventCount}</TableCell>
                  <TableCell className="text-right">
                    <Badge variant="secondary" className="gap-1">
                      <span className={`inline-block size-2 rounded-full ${STATUS_COLORS[v.status] || 'bg-gray-400'}`} />
                      {v.status.replace(/_/g, ' ')}
                    </Badge>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  );
}
