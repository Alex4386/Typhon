// Cross-implementation check: frames encoded by the Java sim-server (sim-server GoldenFramesTest
// writes golden-java.json) must decode here, and the TypeScript encoder must produce the same
// uncompressed payload bytes for the same values.
import { describe, expect, it } from 'vitest';
import golden from './golden-java.json';
import { Codec, type CodecId } from './fields';
import { decodeSectionFrame, decodeTileFrame, encodeValues } from './frames';

const hex = (s: string) => Uint8Array.from(s.match(/../g) ?? [], (b) => parseInt(b, 16));
const toHex = (a: Uint8Array) => Array.from(a, (b) => b.toString(16).padStart(2, '0')).join('');

describe('Java golden frames', () => {
  for (const t of golden.tiles) {
    it(`decodes codec ${t.codec} and re-encodes identical payload bytes`, () => {
      const frame = decodeTileFrame(hex(t.frameHex));
      expect(frame.codec).toBe(t.codec);
      expect(frame.tileX).toBe(3);
      expect(frame.tileY).toBe(-2);
      expect(frame.version).toBe(42);
      expect(frame.time).toBe(1234.5);
      const tol: Record<number, (v: number) => number> = {
        [Codec.ElevationU16CmDelta]: () => 0.006,
        [Codec.DepthU16MmSparse]: () => 0.0006,
        [Codec.DepthU16MmDense]: () => 0.0006,
        [Codec.TemperatureU8Log]: (v) => Math.max(0.5, v * 0.03),
        [Codec.U8Linear]: () => 0.002,
      };
      t.values.forEach((v: number, i: number) => {
        const allowed = tol[t.codec]?.(v) ?? 0;
        expect(Math.abs(frame.values[i] - v)).toBeLessThanOrEqual(allowed + 1e-6);
      });
      const values = Float32Array.from(t.values);
      const enc = encodeValues(values, t.codec as CodecId, { tmax: t.a || undefined, min: t.a, max: t.b });
      expect(toHex(enc.bytes)).toBe(t.payloadHex);
    });
  }

  it('decodes the Java section frame', () => {
    const s = decodeSectionFrame(hex(golden.section.frameHex));
    expect(s.meta.requestId).toBe(9);
    expect(s.nu).toBe(golden.section.nu);
    expect(s.nz).toBe(golden.section.nz);
    expect(Array.from(s.unit)).toEqual(golden.section.unit);
    golden.section.surfaceZ.forEach((v: number, i: number) => expect(s.surfaceZ[i]).toBeCloseTo(v, 4));
    expect(Number.isNaN(s.waterTableZ[0])).toBe(true);
  });
});
