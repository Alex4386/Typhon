import { Mountain } from 'lucide-react';
import { LocationCard, buildBlueMapUrl } from '@/components/volcano/LocationCard';
import { LiveVent, ActivityCards, EjectaCards, StatCard } from './shared';

export function OverviewTab({ vent, blueMapBaseUrl }: {
  vent: LiveVent;
  blueMapBaseUrl: string | null;
}) {
  return (
    <div className="space-y-4">
      <ActivityCards vent={vent} />
      <EjectaCards vent={vent} />

      <div className="grid grid-cols-2 gap-3">
        {vent.location && (
          <LocationCard label="Location" location={vent.location} blueMapUrl={blueMapBaseUrl ? buildBlueMapUrl(blueMapBaseUrl, vent.location) : null} />
        )}
        {vent.summitBlock && (
          <LocationCard label="Summit" icon={Mountain} location={vent.summitBlock} blueMapUrl={blueMapBaseUrl ? buildBlueMapUrl(blueMapBaseUrl, vent.summitBlock) : null} />
        )}
      </div>
      <div className="grid grid-cols-2 gap-3">
        <StatCard label="Crater Radius" value={`${vent.craterRadius}`} />
        <StatCard label="Caldera" value={vent.isCaldera ? `r=${vent.calderaRadius}` : 'None'} />
      </div>
    </div>
  );
}
