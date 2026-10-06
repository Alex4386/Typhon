import { describe, expect, it } from 'vitest';
import type { SimEvent } from '../protocol/messages';
import { describeEvent, describeRow, isImportant, keyEventRows, mergeByTime } from './events';

const at = (kind: string, time: number, extra: Record<string, unknown> = {}) => ({ kind, time, volcanoId: 'k', ...extra }) as unknown as SimEvent;

describe('key events', () => {
  it('classifies milestones, including style estimates and dikes', () => {
    for (const k of ['eruptionStarted', 'eruptionEnded', 'alertChanged', 'regimeChanged', 'styleEstimated', 'dikeStarted', 'dikeStalled', 'fissureOpened', 'ventState', 'message'])
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

  it('shows landslides, craters and collapses, summarising small slides', () => {
    const slide = (t: number, v: number, style = 'TALUS') => at('slopeFailure', t, { at: [0, 0, 1000], volumeM3: v, style, trigger: 'ALTERATION', factorOfSafety: 0.9 });
    expect(isImportant(slide(1, 500))).toBe(false); // ravelling: summarised in the volcano's totals
    expect(isImportant(slide(1, 20_000))).toBe(true);
    expect(isImportant(slide(1, 500, 'DEBRIS_AVALANCHE'))).toBe(true);
    expect(describeEvent(slide(1, 2.5e6, 'DEBRIS_AVALANCHE'))).toBe('Debris avalanche: 2.5 million m³ gave way (rock weakened by hot fluids)');
    const rows = keyEventRows([slide(10, 20_000), slide(20, 50_000), slide(30, 30_000), at('craterExcavated', 40, { at: [0, 0, 0], radiusM: 60, depthM: 25 })], 10);
    expect(rows.map(describeRow)).toEqual(['Explosion crater 120 m wide, 25 m deep', '3 landslides, 100 thousand m³ in all (largest 50 thousand m³)']);
    expect(describeEvent(at('ventState', 1, { ventId: 'f1', previous: 'waning', state: 'frozen' }))).toBe('Vent f1 froze shut');
    expect(describeEvent(at('calderaCollapse', 1, { at: [0, 0, 0], radiusM: 400, subsidenceM: 12.4 }))).toBe('Crater floor collapsing: down 12 m over 800 m');
  });
});
