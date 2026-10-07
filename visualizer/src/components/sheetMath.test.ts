import { describe, expect, it } from 'vitest';
import { settle, snapHeight } from './sheetMath';

describe('bottom sheet snaps', () => {
  const vh = 844; // iPhone 14 portrait

  it('orders peek < half < full and keeps the header visible', () => {
    const [p, h, f] = (['peek', 'half', 'full'] as const).map((s) => snapHeight(s, vh));
    expect(p).toBeLessThan(h);
    expect(h).toBeLessThan(f);
    expect(f).toBe(vh - 56);
    // very short (landscape phone): never taller than the space under the header
    for (const s of ['peek', 'half', 'full'] as const) expect(snapHeight(s, 390)).toBeLessThanOrEqual(390 - 56);
  });

  it('settles on the nearest snap', () => {
    expect(settle(snapHeight('half', vh) + 20, 0, vh)).toBe('half');
    expect(settle(snapHeight('full', vh) - 30, 0, vh)).toBe('full');
    expect(settle(snapHeight('peek', vh) + 10, 0, vh)).toBe('peek');
  });

  it('flicks move one snap in their direction', () => {
    const between = (snapHeight('peek', vh) + snapHeight('half', vh)) / 2 + 5;
    expect(settle(between, -1.5, vh)).toBe('half'); // up
    expect(settle(between, 1.5, vh)).toBe('peek'); // down
  });

  it('closes when dragged or flicked below the peek', () => {
    expect(settle(40, 0, vh)).toBe('close');
    expect(settle(snapHeight('peek', vh), 2, vh)).toBe('close');
  });
});
