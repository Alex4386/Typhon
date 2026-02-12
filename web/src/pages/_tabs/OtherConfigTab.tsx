import type { ConfigNode } from '@/transport/types';
import { ConfigSection } from './shared';

export function OtherConfigTab({ groups, onSetConfig }: {
  groups: Record<string, ConfigNode[]>;
  onSetConfig: (key: string, value: string) => void;
}) {
  const sections = [
    { key: 'succession', title: 'Succession' },
    { key: 'ash', title: 'Ash / Pyroclastic' },
  ];

  return (
    <div className="space-y-4">
      {sections.map(({ key, title }) =>
        groups[key] && groups[key].length > 0 ? (
          <ConfigSection key={key} title={title} nodes={groups[key]} onSetConfig={onSetConfig} />
        ) : null
      )}
    </div>
  );
}
