import type { LocationData } from '@/transport/types';
import { MapPin, Mountain, ExternalLink } from 'lucide-react';
import type { ElementType } from 'react';

export function buildBlueMapUrl(baseUrl: string, loc: LocationData): string | null {
  if (!loc.world) return null;
  const url = baseUrl.replace(/\/+$/, '');
  // Format: #world:x:y:z:distance:rotX:rotY:0:0:perspective
  return `${url}#${loc.world}:${loc.x}:${loc.y}:${loc.z}:1500:0:0:0:0:perspective`;
}

interface LocationCardProps {
  label: string;
  icon?: ElementType;
  location: LocationData;
  blueMapUrl?: string | null;
}

export function LocationCard({ label, icon: Icon = MapPin, location, blueMapUrl }: LocationCardProps) {
  const coords = location.world
    ? `${location.world} — ${location.x}, ${location.y}, ${location.z}`
    : `${location.x}, ${location.y}, ${location.z}`;

  return (
    <div className="rounded-lg border border-border bg-card p-4 space-y-1">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2 text-sm font-medium">
          <Icon className="size-4 text-muted-foreground" />
          {label}
        </div>
        {blueMapUrl && (
          <a
            href={blueMapUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
          >
            View in BlueMap
            <ExternalLink className="size-3" />
          </a>
        )}
      </div>
      <div className="text-sm text-muted-foreground">{coords}</div>
    </div>
  );
}
