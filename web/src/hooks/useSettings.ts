import { useEffect, useState } from 'react';
import type { SettingsData } from '@/transport/types';
import { getApi } from '@/transport/api';

let cached: SettingsData | null = null;

export function useSettings() {
  const [settings, setSettings] = useState<SettingsData | null>(cached);

  useEffect(() => {
    if (cached) return;
    getApi()
      .get<SettingsData>('/settings')
      .then((res) => {
        if (res.status === 200 && res.data) {
          cached = res.data;
          setSettings(res.data);
        }
      })
      .catch(() => {});
  }, []);

  return settings;
}
