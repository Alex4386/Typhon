// Binary frames of protocol v1 (tile fields and cross-sections): layout, encoders and decoders.
// All multi-byte values are little-endian. Compression is zlib (RFC 1950), as produced by
// java.util.zip.Deflater with default settings. Keep in sync with docs/protocol.md §5–6.

import { unzlibSync, zlibSync } from 'fflate';
import { Codec, DEFAULT_TMAX, type CodecId, type FieldId } from './fields';
import type { SectionMeta } from './messages';

export const FrameKind = { Tile: 1, Section: 2 } as const;
export const FRAME_VERSION = 1;
export const FLAG_ZLIB = 1;

export const TILE_HEADER_BYTES = 44;
export const SECTION_HEADER_BYTES = 28;

export interface TileFrame {
  /** Pyramid level (§5.5): 0 for the core's columns, > 0 coarse context, < 0 crater detail. */
  level: number;
  field: FieldId;
  codec: CodecId;
  tileX: number;
  tileY: number;
  /** Monotonic per (field, tile); clients drop frames older than what they hold. */
  version: number;
  width: number;
  height: number;
  /** Simulation time of the data (s). */
  time: number;
  /** Decoded values, row-major (row = y, south → north; column = x, west → east). */
  values: Float32Array;
}

export interface SectionFrame {
  meta: SectionMeta;
  nu: number;
  nz: number;
  time: number;
  /** Ground surface elevation per column (m). */
  surfaceZ: Float32Array;
  /** Water-table elevation per column (m), NaN where none. */
  waterTableZ: Float32Array;
  /** Per pixel, index = k * nu + i with k = 0 at zMin (bottom row). */
  material: Uint8Array;
  unit: Uint16Array;
  temperatureC: Float32Array;
  saturation: Float32Array;
  steam: Float32Array;
  flags: Uint8Array;
}

/** Section pixel flags. */
export const SectionFlag = { Void: 1, WaterBody: 2, Magma: 4, Air: 8 } as const;

// ───────────────────────── Codecs ─────────────────────────

export function tempToU8(t: number, tmax = DEFAULT_TMAX): number {
  const c = Math.min(Math.max(t, 0), tmax);
  return Math.round((255 * Math.log1p(c)) / Math.log1p(tmax));
}

export function u8ToTemp(v: number, tmax = DEFAULT_TMAX): number {
  return Math.exp((v / 255) * Math.log1p(tmax)) - 1;
}

export interface EncodedPayload {
  codec: CodecId;
  param0: number;
  param1: number;
  bytes: Uint8Array;
}

/** Encodes values with the given codec (uncompressed payload). */
export function encodeValues(values: ArrayLike<number>, codec: CodecId, opts: { tmax?: number; min?: number; max?: number } = {}): EncodedPayload {
  const n = values.length;
  switch (codec) {
    case Codec.ElevationU16CmDelta: {
      let min = Infinity;
      for (let i = 0; i < n; i++) min = Math.min(min, values[i]);
      if (!Number.isFinite(min)) min = 0;
      const out = new Uint8Array(n * 2);
      const dv = new DataView(out.buffer);
      let prev = 0;
      for (let i = 0; i < n; i++) {
        const cm = Math.min(65535, Math.max(0, Math.round((values[i] - min) * 100)));
        dv.setUint16(i * 2, (cm - prev) & 0xffff, true);
        prev = cm;
      }
      return { codec, param0: min, param1: 0, bytes: out };
    }
    case Codec.DepthU16MmSparse: {
      const idx: number[] = [];
      for (let i = 0; i < n; i++) if (values[i] > 0.0005) idx.push(i);
      const out = new Uint8Array(4 + idx.length * 4);
      const dv = new DataView(out.buffer);
      dv.setUint32(0, idx.length, true);
      idx.forEach((i, k) => {
        dv.setUint16(4 + k * 4, i, true);
        dv.setUint16(6 + k * 4, Math.min(65535, Math.round(values[i] * 1000)), true);
      });
      return { codec, param0: 0, param1: 0, bytes: out };
    }
    case Codec.DepthU16MmDense: {
      const out = new Uint8Array(n * 2);
      const dv = new DataView(out.buffer);
      for (let i = 0; i < n; i++) dv.setUint16(i * 2, Math.min(65535, Math.max(0, Math.round(values[i] * 1000))), true);
      return { codec, param0: 0, param1: 0, bytes: out };
    }
    case Codec.TemperatureU8Log: {
      const tmax = opts.tmax ?? DEFAULT_TMAX;
      const out = new Uint8Array(n);
      for (let i = 0; i < n; i++) out[i] = tempToU8(values[i], tmax);
      return { codec, param0: tmax, param1: 0, bytes: out };
    }
    case Codec.U16Raw: {
      const out = new Uint8Array(n * 2);
      const dv = new DataView(out.buffer);
      for (let i = 0; i < n; i++) dv.setUint16(i * 2, values[i] & 0xffff, true);
      return { codec, param0: 0, param1: 0, bytes: out };
    }
    case Codec.F32Raw: {
      const out = new Uint8Array(n * 4);
      const dv = new DataView(out.buffer);
      for (let i = 0; i < n; i++) dv.setFloat32(i * 4, values[i], true);
      return { codec, param0: 0, param1: 0, bytes: out };
    }
    case Codec.U8Linear: {
      const min = opts.min ?? 0;
      const max = opts.max ?? 1;
      const out = new Uint8Array(n);
      for (let i = 0; i < n; i++) {
        const t = (values[i] - min) / (max - min || 1);
        out[i] = Math.round(Math.min(1, Math.max(0, t)) * 255);
      }
      return { codec, param0: min, param1: max, bytes: out };
    }
  }
}

/** Decodes an (uncompressed) payload into `n` float values. */
export function decodeValues(bytes: Uint8Array, codec: CodecId, n: number, param0: number, param1: number): Float32Array {
  const out = new Float32Array(n);
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  switch (codec) {
    case Codec.ElevationU16CmDelta: {
      let cm = 0;
      for (let i = 0; i < n; i++) {
        cm = (cm + dv.getUint16(i * 2, true)) & 0xffff;
        out[i] = param0 + cm / 100;
      }
      return out;
    }
    case Codec.DepthU16MmSparse: {
      const count = dv.getUint32(0, true);
      for (let k = 0; k < count; k++) {
        const i = dv.getUint16(4 + k * 4, true);
        if (i < n) out[i] = dv.getUint16(6 + k * 4, true) / 1000;
      }
      return out;
    }
    case Codec.DepthU16MmDense:
      for (let i = 0; i < n; i++) out[i] = dv.getUint16(i * 2, true) / 1000;
      return out;
    case Codec.TemperatureU8Log:
      for (let i = 0; i < n; i++) out[i] = u8ToTemp(bytes[i], param0 || DEFAULT_TMAX);
      return out;
    case Codec.U16Raw:
      for (let i = 0; i < n; i++) out[i] = dv.getUint16(i * 2, true);
      return out;
    case Codec.F32Raw:
      for (let i = 0; i < n; i++) out[i] = dv.getFloat32(i * 4, true);
      return out;
    case Codec.U8Linear:
      for (let i = 0; i < n; i++) out[i] = param0 + (bytes[i] / 255) * (param1 - param0);
      return out;
    default:
      throw new Error(`Unknown codec ${codec as number}`);
  }
}

// ───────────────────────── Tile frames ─────────────────────────

export interface TileFrameInput {
  /** Pyramid level (default 0). */
  level?: number;
  field: FieldId;
  codec: CodecId;
  tileX: number;
  tileY: number;
  version: number;
  width: number;
  height: number;
  time: number;
  values: ArrayLike<number>;
  tmax?: number;
  min?: number;
  max?: number;
  compress?: boolean;
}

export function encodeTileFrame(f: TileFrameInput): Uint8Array {
  const enc = encodeValues(f.values, f.codec, f);
  const compress = f.compress ?? true;
  const payload = compress ? zlibSync(enc.bytes, { level: 6 }) : enc.bytes;
  const out = new Uint8Array(TILE_HEADER_BYTES + payload.length);
  const dv = new DataView(out.buffer);
  dv.setUint8(0, FrameKind.Tile);
  dv.setUint8(1, FRAME_VERSION);
  dv.setInt16(2, f.level ?? 0, true);
  dv.setUint16(4, f.field, true);
  dv.setUint8(6, f.codec);
  dv.setUint8(7, compress ? FLAG_ZLIB : 0);
  dv.setInt32(8, f.tileX, true);
  dv.setInt32(12, f.tileY, true);
  dv.setUint32(16, f.version >>> 0, true);
  dv.setUint16(20, f.width, true);
  dv.setUint16(22, f.height, true);
  dv.setFloat32(24, enc.param0, true);
  dv.setFloat32(28, enc.param1, true);
  dv.setFloat64(32, f.time, true);
  dv.setUint32(40, payload.length, true);
  out.set(payload, TILE_HEADER_BYTES);
  return out;
}

export function frameKind(buf: Uint8Array): number {
  return buf[0];
}

export function decodeTileFrame(buf: Uint8Array): TileFrame {
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  if (dv.getUint8(0) !== FrameKind.Tile) throw new Error('Not a tile frame');
  if (dv.getUint8(1) !== FRAME_VERSION) throw new Error(`Unsupported frame version ${dv.getUint8(1)}`);
  const field = dv.getUint16(4, true) as FieldId;
  const codec = dv.getUint8(6) as CodecId;
  const flags = dv.getUint8(7);
  const width = dv.getUint16(20, true);
  const height = dv.getUint16(22, true);
  const param0 = dv.getFloat32(24, true);
  const param1 = dv.getFloat32(28, true);
  const len = dv.getUint32(40, true);
  let payload = buf.subarray(TILE_HEADER_BYTES, TILE_HEADER_BYTES + len);
  if (flags & FLAG_ZLIB) payload = unzlibSync(payload);
  return {
    level: dv.getInt16(2, true),
    field,
    codec,
    tileX: dv.getInt32(8, true),
    tileY: dv.getInt32(12, true),
    version: dv.getUint32(16, true),
    width,
    height,
    time: dv.getFloat64(32, true),
    values: decodeValues(payload, codec, width * height, param0, param1),
  };
}

// ───────────────────────── Section frames ─────────────────────────

export interface SectionFrameInput {
  meta: SectionMeta;
  nu: number;
  nz: number;
  time: number;
  surfaceZ: ArrayLike<number>;
  waterTableZ: ArrayLike<number>;
  material: ArrayLike<number>;
  unit: ArrayLike<number>;
  temperatureC: ArrayLike<number>;
  saturation: ArrayLike<number>;
  steam: ArrayLike<number>;
  flags: ArrayLike<number>;
  compress?: boolean;
}

export function encodeSectionFrame(s: SectionFrameInput): Uint8Array {
  const metaBytes = new TextEncoder().encode(JSON.stringify(s.meta));
  const px = s.nu * s.nz;
  const bodyLen = 4 + metaBytes.length + s.nu * 8 + px * (1 + 2 + 1 + 1 + 1 + 1);
  const body = new Uint8Array(bodyLen);
  const dv = new DataView(body.buffer);
  let o = 0;
  dv.setUint32(o, metaBytes.length, true);
  o += 4;
  body.set(metaBytes, o);
  o += metaBytes.length;
  for (let i = 0; i < s.nu; i++, o += 4) dv.setFloat32(o, s.surfaceZ[i], true);
  for (let i = 0; i < s.nu; i++, o += 4) dv.setFloat32(o, s.waterTableZ[i], true);
  for (let p = 0; p < px; p++) body[o++] = s.material[p];
  for (let p = 0; p < px; p++, o += 2) dv.setUint16(o, s.unit[p], true);
  for (let p = 0; p < px; p++) body[o++] = tempToU8(s.temperatureC[p]);
  for (let p = 0; p < px; p++) body[o++] = Math.round(Math.min(1, Math.max(0, s.saturation[p])) * 255);
  for (let p = 0; p < px; p++) body[o++] = Math.round(Math.min(1, Math.max(0, s.steam[p])) * 255);
  for (let p = 0; p < px; p++) body[o++] = s.flags[p];

  const compress = s.compress ?? true;
  const payload = compress ? zlibSync(body, { level: 6 }) : body;
  const out = new Uint8Array(SECTION_HEADER_BYTES + payload.length);
  const h = new DataView(out.buffer);
  h.setUint8(0, FrameKind.Section);
  h.setUint8(1, FRAME_VERSION);
  h.setUint16(2, 0, true);
  h.setUint32(4, s.meta.requestId >>> 0, true);
  h.setUint16(8, s.nu, true);
  h.setUint16(10, s.nz, true);
  h.setUint8(12, compress ? FLAG_ZLIB : 0);
  h.setFloat64(16, s.time, true);
  h.setUint32(24, payload.length, true);
  out.set(payload, SECTION_HEADER_BYTES);
  return out;
}

export function decodeSectionFrame(buf: Uint8Array): SectionFrame {
  const h = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  if (h.getUint8(0) !== FrameKind.Section) throw new Error('Not a section frame');
  if (h.getUint8(1) !== FRAME_VERSION) throw new Error(`Unsupported frame version ${h.getUint8(1)}`);
  const nu = h.getUint16(8, true);
  const nz = h.getUint16(10, true);
  const flags = h.getUint8(12);
  const time = h.getFloat64(16, true);
  const len = h.getUint32(24, true);
  let body = buf.subarray(SECTION_HEADER_BYTES, SECTION_HEADER_BYTES + len);
  if (flags & FLAG_ZLIB) body = unzlibSync(body);
  const dv = new DataView(body.buffer, body.byteOffset, body.byteLength);
  let o = 0;
  const metaLen = dv.getUint32(o, true);
  o += 4;
  const meta = JSON.parse(new TextDecoder().decode(body.subarray(o, o + metaLen))) as SectionMeta;
  o += metaLen;
  const px = nu * nz;
  const surfaceZ = new Float32Array(nu);
  const waterTableZ = new Float32Array(nu);
  for (let i = 0; i < nu; i++, o += 4) surfaceZ[i] = dv.getFloat32(o, true);
  for (let i = 0; i < nu; i++, o += 4) waterTableZ[i] = dv.getFloat32(o, true);
  const material = body.slice(o, o + px);
  o += px;
  const unit = new Uint16Array(px);
  for (let p = 0; p < px; p++, o += 2) unit[p] = dv.getUint16(o, true);
  const temperatureC = new Float32Array(px);
  for (let p = 0; p < px; p++) temperatureC[p] = u8ToTemp(body[o++]);
  const saturation = new Float32Array(px);
  for (let p = 0; p < px; p++) saturation[p] = body[o++] / 255;
  const steam = new Float32Array(px);
  for (let p = 0; p < px; p++) steam[p] = body[o++] / 255;
  const pxFlags = body.slice(o, o + px);
  return { meta, nu, nz, time, surfaceZ, waterTableZ, material, unit, temperatureC, saturation, steam, flags: pxFlags };
}
