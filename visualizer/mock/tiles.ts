// MOCK — tile encoding policy of the mock server (which codec per field). The Java server may
// choose differently per frame; clients must honour the codec in each frame header.

import { Codec, Field, type CodecId, type FieldId } from '../src/protocol/fields';
import { encodeTileFrame } from '../src/protocol/frames';
import { MockWorld, TILE, TILES } from './world';

export const CODEC_FOR: Record<FieldId, CodecId> = {
  [Field.SurfaceElevation]: Codec.ElevationU16CmDelta,
  [Field.LavaDepth]: Codec.DepthU16MmSparse,
  [Field.LavaTemperature]: Codec.TemperatureU8Log,
  [Field.WaterDepth]: Codec.F32Raw, // sea depth can exceed the 65.535 m u16-mm range
  [Field.PdcDepth]: Codec.DepthU16MmSparse,
  [Field.LaharDepth]: Codec.DepthU16MmSparse,
  [Field.AshDepth]: Codec.DepthU16MmSparse,
  [Field.SurfaceTemperature]: Codec.TemperatureU8Log,
  [Field.WaterTableDepth]: Codec.F32Raw,
  [Field.TopUnit]: Codec.U16Raw,
  [Field.Uplift]: Codec.F32Raw,
  [Field.SteamFraction]: Codec.U8Linear,
};

export function encodeTile(world: MockWorld, field: FieldId, tx: number, ty: number, version: number): Uint8Array {
  return encodeTileFrame({
    field,
    codec: CODEC_FOR[field],
    tileX: tx,
    tileY: ty,
    version,
    width: TILE,
    height: TILE,
    time: world.time,
    values: world.tileValues(field, tx, ty),
    min: 0,
    max: 1,
  });
}

/** Cheap content hash of encoded bytes, used to detect tiles whose *quantised* values changed. */
export function hashBytes(a: Uint8Array): number {
  let h = 2166136261;
  for (let i = 0; i < a.length; i++) h = Math.imul(h ^ a[i], 16777619);
  return h >>> 0;
}

/** Seconds between change checks per field: slow fields are refreshed less often. */
export const REFRESH_SECONDS: Record<FieldId, number> = {
  [Field.SurfaceElevation]: 1,
  [Field.LavaDepth]: 0.25,
  [Field.LavaTemperature]: 0.5,
  [Field.WaterDepth]: 1,
  [Field.PdcDepth]: 0.25,
  [Field.LaharDepth]: 0.5,
  [Field.AshDepth]: 2,
  [Field.SurfaceTemperature]: 3,
  [Field.WaterTableDepth]: 4,
  [Field.TopUnit]: 2,
  [Field.Uplift]: 4,
  [Field.SteamFraction]: 4,
};

/** Every field of a few tiles of a freshly built world (used by codec tests). */
export function encodeSyntheticTiles(): Uint8Array[] {
  const world = new MockWorld(7);
  world.forceEruption('mock-fuji');
  world.advance(600);
  const out: Uint8Array[] = [];
  for (const field of Object.keys(CODEC_FOR).map(Number) as FieldId[]) {
    for (const [tx, ty] of [
      [TILES / 2, TILES / 2],
      [0, 0],
    ]) {
      out.push(encodeTile(world, field, tx, ty, 1));
    }
  }
  return out;
}
