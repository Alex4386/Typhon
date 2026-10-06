import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

// The store reads the page location and storage when it loads: give it a minimal window.
const memory = new Map<string, string>();
vi.stubGlobal('window', {
  location: new URL('http://localhost:5180/'),
  localStorage: { getItem: (k: string) => memory.get(k) ?? null, setItem: (k: string, v: string) => memory.set(k, v) },
  setInterval: () => 0,
});
let useStore: typeof import('./store').useStore;
beforeAll(async () => {
  ({ useStore } = await import('./store'));
});

describe('HUD toasts', () => {
  beforeEach(() => useStore.setState({ toasts: [] }));

  it('the same text replaces the older toast and restarts its timer', () => {
    const s = useStore.getState();
    s.toast('Applied 1 change', 'info');
    s.toast('Something else', 'warn');
    s.toast('Applied 1 change', 'info');
    const toasts = useStore.getState().toasts;
    expect(toasts.map((t) => t.text)).toEqual(['Something else', 'Applied 1 change']);
    expect(toasts[1].serial).toBe(2);
  });

  it('keeps only the newest few and lets one be dismissed', () => {
    const s = useStore.getState();
    for (let i = 0; i < 10; i++) s.toast(`t${i}`);
    expect(useStore.getState().toasts.map((t) => t.text)).toEqual(['t4', 't5', 't6', 't7', 't8', 't9']);
    s.dismissToast('t9');
    expect(useStore.getState().toasts.at(-1)?.text).toBe('t8');
  });

  it('alerts stay longer than information', () => {
    const s = useStore.getState();
    s.toast('info');
    s.toast('alert', 'alert');
    const [info, alert] = useStore.getState().toasts;
    expect(alert.duration).toBeGreaterThan(info.duration);
  });
});
