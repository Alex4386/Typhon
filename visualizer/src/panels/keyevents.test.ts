import { describe, expect, it } from 'vitest';
import type { SimEvent } from '../protocol/messages';
import { describeEvent, describeRow, isImportant, keyEventRows, mergeByTime } from './events';

const at = (kind: string, time: number, extra: Record<string, unknown> = {}) => ({ kind, time, volcanoId: 'k', ...extra }) as unknown as SimEvent;

describe('key events', () => {
  it('classifies milestones, including style estimates and dikes', () => {
    for (const k of ['eruptionStarted', 'eruptionEnded', 'alertChanged', 'regimeChanged', 'styleEstimated', 'dikeStarted', 'dikeStalled', 'fissureOpened', 'message'])
      expect(isImportant(at(k, 1)), k).toBe(true);
    expect(isImportant(at('plume', 1))).toBe(false);
    expect(isImportant(at('dikeAdvanced', 1))).toBe(false);
    expect(isImportant(at('seismic', 1, { magnitude: 2 }))).toBe(false);
    expect(isImportant(at('seismic', 1, { magnitude: 3.2 }))).toBe(true);
    expect(isImportant(at('bombLaunched', 1))).toBe(true);
  });

  it('keeps milestones as rows and aggregates bursts, big quakes and ocean entries per hour', () => {
    const events: SimEvent[] = [
      at('eruptionStarted', 5, { cause: 'OVERPRESSURE', ventIds: [] }),
      at('styleEstimated', 6, { previous: null, current: 'STROMBOLIAN', vei: 1, forecast: false, probabilities: {} }),
      ...Array.from({ length: 300 }, (_, k) => at('bombLaunched', 10 + k)),
      at('seismic', 20, { magnitude: 3.1, type: 'VT', hypocenter: [0, 0, -2000] }),
      at('seismic', 40, { magnitude: 3.6, type: 'VT', hypocenter: [0, 0, -2000] }),
      at('oceanEntry', 50, { at: [0, 0], powerMW: 30, littoralExplosion: false }),
      at('oceanEntry', 60, { at: [0, 0], powerMW: 40, littoralExplosion: false }),
      ...Array.from({ length: 50 }, (_, k) => at('bombLaunched', 5000 + k)),
      at('dikeStalled', 6000, { dikeId: 2, tip: [0, 0, -500], depthM: 800, volumeM3: 1e5, reason: 'FROZE' }),
    ];
    const rows = keyEventRows(events, 50);
    expect(rows.map((r) => `${r.event.kind}×${r.count}`)).toEqual([
      'dikeStalled×1',
      'bombLaunched×50',
      'bombLaunched×300',
      'oceanEntry×1',
      'oceanEntry×1',
      'seismic×2',
      'styleEstimated×1',
      'eruptionStarted×1',
    ]);
    expect(describeRow(rows.find((r) => r.event.kind === 'seismic')!)).toContain('M3.6');
    expect(describeRow(rows[4])).toContain('reached the sea');
    expect(describeEvent(rows[6].event)).toContain('Strombolian');
    expect(describeEvent(rows[0].event)).toContain('froze');
  });

  it('merges a re-attach backlog into the key-event list without duplicates', () => {
    const merged = mergeByTime([at('eruptionStarted', 5)], [at('eruptionStarted', 5), at('alertChanged', 9, { previous: null, current: 'ERUPTING' })]);
    expect(merged.map((e) => e.kind)).toEqual(['eruptionStarted', 'alertChanged']);
  });
});
