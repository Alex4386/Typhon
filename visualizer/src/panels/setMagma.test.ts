import { describe, expect, it } from 'vitest';
import type { EntityView } from '../store/entities';
import { magmaCommand } from './setMagma';

const main = { id: 'chamber:v', kind: 'chamber', volcanoId: 'v', props: {} } as unknown as EntityView;
const side = { id: 'chamber:v:deep', kind: 'chamber', volcanoId: 'v', props: { chamberId: 'deep' } } as unknown as EntityView;

describe('magmaCommand', () => {
  it('builds the command for the main or a further chamber, leaving blank fields out', () => {
    expect(magmaCommand(main, { temperatureC: '950', silicaWt: '62', waterWt: '', co2Wt: '0.1' }).command)
      .toEqual({ kind: 'setChamberMagma', volcanoId: 'v', temperatureC: 950, silicaWt: 62, co2Wt: 0.1 });
    expect(magmaCommand(side, { waterWt: '3' }).command).toEqual({ kind: 'setChamberMagma', volcanoId: 'v', chamberId: 'deep', waterWt: 3 });
  });
  it('rejects values out of range', () => {
    expect(magmaCommand(main, { silicaWt: '90' }).error).toMatch(/Silica/);
    expect(magmaCommand(main, { temperatureC: 'hot' }).command).toBeUndefined();
  });
});
