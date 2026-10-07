import { describe, expect, it } from 'vitest';
import { deviceTier, type DeviceInfo } from './device';

const base: DeviceInfo = { touch: false, shortSide: 1080, slowNetwork: false, mobileUA: false };

describe('device tier', () => {
  it('desktops keep the full budget', () => {
    const t = deviceTier(base);
    expect(t).toMatchObject({ device: 'desktop', quality: 'medium', dprCap: 2, liteWater: false });
    expect(deviceTier({ ...base, memoryGB: 2 }).quality).toBe('low');
  });

  it('phones get low quality, a 1.5 DPR cap and cheap water', () => {
    const t = deviceTier({ ...base, touch: true, mobileUA: true, shortSide: 390 });
    expect(t).toMatchObject({ device: 'phone', quality: 'low', dprCap: 1.5, liteWater: true, maxDetailLevels: 1 });
    expect(t.particleScale).toBeLessThan(1);
    expect(t.detailReach).toBeLessThan(1);
  });

  it('tablets sit in between; low-memory tablets drop to the phone budget', () => {
    expect(deviceTier({ ...base, touch: true, mobileUA: true, shortSide: 834 })).toMatchObject({ device: 'tablet', quality: 'medium', dprCap: 1.5 });
    expect(deviceTier({ ...base, touch: true, mobileUA: true, shortSide: 834, memoryGB: 2 }).quality).toBe('low');
  });

  it('paces tile intake on phones and slow links', () => {
    const phone = deviceTier({ ...base, touch: true, mobileUA: true, shortSide: 390 });
    expect(phone.tilesPerFrame).toBeLessThan(deviceTier(base).tilesPerFrame);
    expect(deviceTier({ ...base, slowNetwork: true }).tilesPerFrame).toBeLessThanOrEqual(8);
  });

  it('a touch laptop without a mobile UA counts by its screen', () => {
    expect(deviceTier({ ...base, touch: true, shortSide: 900 }).device).toBe('tablet');
  });
});
