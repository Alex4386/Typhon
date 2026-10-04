import { describe, expect, it } from 'vitest';
import { Codec, Field } from './fields';
import {
  SectionFlag,
  decodeSectionFrame,
  decodeTileFrame,
  encodeSectionFrame,
  encodeTileFrame,
  tempToU8,
  u8ToTemp,
} from './frames';
import { encodeSyntheticTiles } from '../../mock/tiles';

const W = 32;
const H = 24;

function ramp(f: (i: number) => number): Float32Array {
  const a = new Float32Array(W * H);
  for (let i = 0; i < a.length; i++) a[i] = f(i);
  return a;
}

describe('tile frames', () => {
  it('elevation round-trips to the centimetre', () => {
    const values = ramp((i) => 412.37 + Math.sin(i / 7) * 80 + (i % W) * 0.013);
    const frame = decodeTileFrame(
      encodeTileFrame({ field: Field.SurfaceElevation, codec: Codec.ElevationU16CmDelta, tileX: -3, tileY: 7, version: 42, width: W, height: H, time: 3600.5, values }),
    );
    expect(frame).toMatchObject({ field: Field.SurfaceElevation, tileX: -3, tileY: 7, version: 42, width: W, height: H, time: 3600.5 });
    for (let i = 0; i < values.length; i++) expect(Math.abs(frame.values[i] - values[i])).toBeLessThanOrEqual(0.006);
  });

  it('sparse depth keeps only non-zero cells, to the millimetre', () => {
    const values = ramp((i) => (i % 5 === 0 ? 0.25 + i * 0.001 : 0));
    const frame = decodeTileFrame(
      encodeTileFrame({ field: Field.LavaDepth, codec: Codec.DepthU16MmSparse, tileX: 0, tileY: 0, version: 1, width: W, height: H, time: 0, values }),
    );
    for (let i = 0; i < values.length; i++) expect(frame.values[i]).toBeCloseTo(values[i], 3);
  });

  it('dense depth, raw and linear codecs round-trip', () => {
    const depth = ramp((i) => i * 0.0123);
    expect(decodeTileFrame(encodeTileFrame({ field: Field.WaterDepth, codec: Codec.DepthU16MmDense, tileX: 1, tileY: 1, version: 2, width: W, height: H, time: 1, values: depth })).values[100]).toBeCloseTo(depth[100], 3);

    const units = ramp((i) => i % 900);
    const u = decodeTileFrame(encodeTileFrame({ field: Field.TopUnit, codec: Codec.U16Raw, tileX: 1, tileY: 1, version: 2, width: W, height: H, time: 1, values: units, compress: false }));
    expect(Array.from(u.values)).toEqual(Array.from(units));

    const uplift = ramp((i) => (i - 300) * 1e-4);
    const f = decodeTileFrame(encodeTileFrame({ field: Field.Uplift, codec: Codec.F32Raw, tileX: 1, tileY: 1, version: 2, width: W, height: H, time: 1, values: uplift }));
    expect(f.values[17]).toBeCloseTo(uplift[17], 7);

    const steam = ramp((i) => (i % 101) / 100);
    const s = decodeTileFrame(encodeTileFrame({ field: Field.SteamFraction, codec: Codec.U8Linear, tileX: 1, tileY: 1, version: 2, width: W, height: H, time: 1, values: steam, min: 0, max: 1 }));
    for (let i = 0; i < steam.length; i++) expect(Math.abs(s.values[i] - steam[i])).toBeLessThanOrEqual(1 / 255);
  });

  it('log temperature keeps relative precision across 0–1300 °C', () => {
    for (const t of [0, 5, 20, 100, 350, 900, 1150, 1300]) {
      const back = u8ToTemp(tempToU8(t));
      expect(Math.abs(back - t)).toBeLessThanOrEqual(Math.max(0.5, t * 0.03));
    }
  });

  it('decodes what the mock server encodes', () => {
    const frames = encodeSyntheticTiles();
    expect(frames.length).toBeGreaterThan(0);
    for (const buf of frames) {
      const f = decodeTileFrame(buf);
      expect(f.values.length).toBe(f.width * f.height);
      expect(Number.isFinite(f.values[0])).toBe(true);
    }
  });
});

describe('section frames', () => {
  it('round-trips meta, profiles and pixel channels', () => {
    const nu = 40;
    const nz = 30;
    const px = nu * nz;
    const input = {
      meta: { requestId: 9, length: 1234.5, zMin: -500, zMax: 900, units: [{ id: 3, volcanoId: 'v', depositType: 1, eruption: 2, time: 99, label: 'lava #2' }], overlays: [{ kind: 'chamber' as const, u: 600, z: -300, rx: 200, rz: 90, temperatureC: 1050 }] },
      nu,
      nz,
      time: 77.25,
      surfaceZ: Array.from({ length: nu }, (_, i) => 100 + i),
      waterTableZ: Array.from({ length: nu }, (_, i) => (i % 3 === 0 ? NaN : 50 + i)),
      material: Array.from({ length: px }, (_, p) => p % 7),
      unit: Array.from({ length: px }, (_, p) => p % 1000),
      temperatureC: Array.from({ length: px }, (_, p) => (p % 1300)),
      saturation: Array.from({ length: px }, (_, p) => (p % 11) / 10),
      steam: Array.from({ length: px }, (_, p) => (p % 5) / 4),
      flags: Array.from({ length: px }, (_, p) => (p % 9 === 0 ? SectionFlag.Void : 0)),
    };
    const out = decodeSectionFrame(encodeSectionFrame(input));
    expect(out.meta).toEqual(input.meta);
    expect(out.nu).toBe(nu);
    expect(out.time).toBe(77.25);
    expect(out.surfaceZ[5]).toBe(105);
    expect(Number.isNaN(out.waterTableZ[0])).toBe(true);
    expect(out.waterTableZ[1]).toBe(51);
    expect(Array.from(out.material)).toEqual(input.material);
    expect(Array.from(out.unit)).toEqual(input.unit);
    expect(out.saturation[10]).toBeCloseTo(1, 2);
    expect(out.flags[9]).toBe(SectionFlag.Void);
  });
});
