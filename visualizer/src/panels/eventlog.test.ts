import { describe, expect, it } from 'vitest';
import type { SimEvent } from '../protocol/messages';
import { collapse, isImportant } from './events';

const feature = (time: number, f: string): SimEvent =>
  ({ kind: 'geothermalFeature', time, volcanoId: 'k', feature: f, at: [0, 0, 0] }) as SimEvent;

describe('event log', () => {
  it('keeps diffuse features out of the important list', () => {
    expect(isImportant(feature(1, 'SULFUR_DEPOSIT'))).toBe(false);
    expect(isImportant(feature(1, 'FUMAROLE'))).toBe(false);
    expect(isImportant(feature(1, 'GEYSER'))).toBe(true);
  });

  it('collapses consecutive repeats newest first', () => {
    const events = [
      feature(1, 'SULFUR_DEPOSIT'),
      feature(2, 'SULFUR_DEPOSIT'),
      feature(3, 'GEYSER'),
      feature(4, 'SULFUR_DEPOSIT'),
      feature(5, 'SULFUR_DEPOSIT'),
    ];
    const rows = collapse(events, 10);
    expect(rows.map((r) => [r.event.time, r.count, r.firstTime])).toEqual([
      [5, 2, 4],
      [3, 1, 3],
      [2, 2, 1],
    ]);
  });
});
