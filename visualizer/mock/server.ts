// MOCK sim-server: speaks protocol v1 over WebSocket (ws://localhost:8787/ws) using the synthetic
// MockWorld. It exists so the visualizer can be built before the Java sim-server (M6) lands.
// Replay here is keyframe-only (no deltas) — see docs/protocol.md §7 for the real scheme.

import { WebSocketServer, type WebSocket } from 'ws';
import { FIELD_NAMES, type FieldId } from '../src/protocol/fields';
import { encodeSectionFrame, encodeValues } from '../src/protocol/frames';
import {
  PROTOCOL_VERSION,
  TILE_WINDOW,
  WS_SUBPROTOCOL,
  type ClientMessage,
  type PlaybackState,
  type ServerMessage,
  type SimEvent,
  type TransportMode,
} from '../src/protocol/messages';
import { CODEC_FOR, REFRESH_SECONDS, encodeTile, hashBytes } from './tiles';
import { MockWorld, TILES, type Snapshot } from './world';

const PORT = Number(process.env.MOCK_PORT ?? 8787);
const WALL_TICK_MS = 100;
/** Engine base step the mock pretends to use (s); the real engine defaults to 50 ms. */
const BASE_STEP = 0.05;
const BROADCAST_MS = 250;
const KEYFRAME_SECONDS = 300;
const MAX_KEYFRAMES = 400;
/** Skip tile frames for a client while this many bytes are still queued on its socket. */
const MAX_BUFFERED_BYTES = 128 * 1024;
/** Seconds per wall second in UNBOUNDED mode (the mock is not a real solver). */
const UNBOUNDED_RATE = 3000;

interface Client {
  ws: WebSocket;
  /** Tile frames sent since subscribe, and how many the client reported processed. */
  tilesSent: number;
  tilesAcked: number;
  fields: Set<FieldId>;
  /** Last version sent per `${field}:${tx}:${ty}`. */
  sent: Map<string, number>;
}

class Session {
  readonly id = 'mock';
  world = new MockWorld(1);
  mode: TransportMode = 'REALTIME';
  pauseAt: number | null = null;
  speed = 20;
  playback: PlaybackState = { slowOnEruption: true, eruptionSpeed: 20, slowOnEvents: [], eventHoldSeconds: 3600, slowed: false };
  replay = false;
  rate = 0;
  step = 0;
  readonly versions = new Map<string, { version: number; hash: number }>();
  readonly keyframes: Snapshot[] = [];
  readonly eventLog: SimEvent[] = [];
  lastKeyframe = -Infinity;

  reset(seed: number) {
    this.world = new MockWorld(seed);
    this.versions.clear();
    this.keyframes.length = 0;
    this.eventLog.length = 0;
    this.lastKeyframe = -Infinity;
    this.step = 0;
  }

  advance(wallSeconds: number): SimEvent[] {
    if (this.replay || this.mode === 'PAUSED') {
      this.rate = 0;
      return [];
    }
    let want = this.mode === 'UNBOUNDED' ? UNBOUNDED_RATE * wallSeconds : this.speed * wallSeconds;
    if (this.pauseAt !== null) want = Math.max(0, Math.min(want, this.pauseAt - this.world.time));
    const done = this.world.advance(want, WALL_TICK_MS * (this.mode === 'UNBOUNDED' ? 0.45 : 0.7));
    this.step++;
    this.rate = done / wallSeconds;
    if (this.pauseAt !== null && this.world.time >= this.pauseAt - 1e-9) {
      this.mode = 'PAUSED';
      this.pauseAt = null;
    }
    return this.collect();
  }

  stepSeconds(seconds: number): SimEvent[] {
    this.world.advance(seconds);
    this.step++;
    return this.collect();
  }

  private collect(): SimEvent[] {
    const ev = this.world.drainEvents();
    this.eventLog.push(...ev);
    if (this.eventLog.length > 20000) this.eventLog.splice(0, this.eventLog.length - 20000);
    if (this.world.time - this.lastKeyframe >= KEYFRAME_SECONDS) {
      this.keyframes.push(this.world.snapshot());
      if (this.keyframes.length > MAX_KEYFRAMES) this.keyframes.shift();
      this.lastKeyframe = this.world.time;
    }
    return ev;
  }

  /** Encoded frame per tile key at its current version (re-used for every client). */
  readonly frames = new Map<string, Uint8Array>();
  private readonly lastRefresh = new Map<FieldId, number>();

  /**
   * Re-encodes tiles of fields whose refresh interval elapsed and bumps the version of tiles whose
   * encoded (quantised) payload changed, so tiny float drift does not resend whole tiles.
   */
  refreshVersions(fields: Iterable<FieldId>, force = false): void {
    const now = performance.now() / 1000;
    for (const field of fields) {
      if (!force && now - (this.lastRefresh.get(field) ?? -Infinity) < REFRESH_SECONDS[field]) continue;
      this.lastRefresh.set(field, now);
      for (let ty = 0; ty < TILES; ty++) {
        for (let tx = 0; tx < TILES; tx++) {
          const key = `${field}:${tx}:${ty}`;
          const cur = this.versions.get(key);
          // hash the quantised (uncompressed) payload; compress only tiles that changed
          const values = this.world.tileValues(field, tx, ty);
          const h = hashBytes(encodeValues(values, CODEC_FOR[field], { min: 0, max: 1 }).bytes);
          if (!cur || cur.hash !== h) {
            const version = (cur?.version ?? 0) + 1;
            this.versions.set(key, { version, hash: h });
            this.frames.set(key, encodeTile(this.world, field, tx, ty, version));
          }
        }
      }
    }
  }
}

/** Backlog for a (re)attaching client: all non-seismic events plus the latest seismic ones. */
function recentEvents(log: SimEvent[], until: number): SimEvent[] {
  const upto = log.filter((e) => e.time <= until);
  const seismic = upto.filter((e) => e.kind === 'seismic' || e.kind === 'bombLaunched').slice(-600);
  const other = upto.filter((e) => e.kind !== 'seismic' && e.kind !== 'bombLaunched').slice(-1500);
  return [...other, ...seismic].sort((a, b) => a.time - b.time);
}

const session = new Session();
const clients = new Set<Client>();

function send(c: Client, m: ServerMessage) {
  if (c.ws.readyState === c.ws.OPEN) c.ws.send(JSON.stringify(m));
}

function broadcast(m: ServerMessage) {
  for (const c of clients) send(c, m);
}

/** Tile coordinates ordered centre-first, so the volcanoes appear before the margins. */
const TILE_ORDER: [number, number][] = (() => {
  const out: [number, number][] = [];
  for (let ty = 0; ty < TILES; ty++) for (let tx = 0; tx < TILES; tx++) out.push([tx, ty]);
  const c = (TILES - 1) / 2;
  return out.sort((a, b) => Math.hypot(a[0] - c, a[1] - c) - Math.hypot(b[0] - c, b[1] - c));
})();

/**
 * Streams tiles the client does not have yet, within a socket-buffer budget, so tile bursts never
 * starve JSON messages and section responses. `resend` forgets what the client already holds
 * (after a replay jump) so every tile is sent again.
 */
function sendTiles(c: Client, resend = false) {
  if (resend) c.sent.clear();
  for (const [tx, ty] of TILE_ORDER) {
    for (const field of c.fields) {
      if (c.tilesSent - c.tilesAcked >= TILE_WINDOW) return; // window full: wait for `flow`
      if (c.ws.bufferedAmount > MAX_BUFFERED_BYTES) return; // slow socket: continue on the next pump
      const key = `${field}:${tx}:${ty}`;
      const v = session.versions.get(key);
      if (!v) continue;
      if ((c.sent.get(key) ?? 0) >= v.version) continue;
      c.ws.send(session.frames.get(key) ?? encodeTile(session.world, field, tx, ty, v.version));
      c.sent.set(key, v.version);
      c.tilesSent++;
    }
  }
}

function attach(c: Client) {
  send(c, { type: 'attached', sessionId: session.id, world: session.world.worldInfo() });
  send(c, { type: 'units', units: session.world.units, replace: true });
  send(c, session.world.state());
  send(c, { type: 'events', events: recentEvents(session.eventLog, Infinity), dropped: 0 });
  sendReplayInfo(c);
  send(c, schema());
}

/** The mock world is not tunable; it still describes the injection fields. */
function schema(): ServerMessage {
  const field = (id: string, label: string, unit: string, min: number, max: number, def: number, log = false) =>
    ({ id, label, unit, min, max, default: def, log, group: id === 'volumeM3' ? 'Batch' : 'Magma', type: 'number', apply: 'live' }) as const;
  return {
    type: 'schema',
    sessionId: session.id,
    tunable: false,
    reason: 'The mock server has no world files to change.',
    params: [],
    commands: {
      injectMagma: [
        field('volumeM3', 'Volume', 'm³', 1e3, 1e10, 5e6, true),
        field('temperatureC', 'Temperature', '°C', 650, 1350, 1150),
        field('silicaWt', 'Silica (SiO₂)', 'wt%', 42, 78, 50),
        field('waterWt', 'Water (H₂O)', 'wt%', 0, 8, 0.5),
      ],
    },
    audit: [],
  };
}

function sessionsMessage(): ServerMessage {
  return {
    type: 'sessions',
    sessions: [{ id: session.id, name: 'Mock island (MOCK)', preset: 'mock', time: session.world.time, mode: session.mode, speed: session.speed, rate: session.rate, clients: clients.size }],
  };
}

function sendReplayInfo(c?: Client) {
  const kf = session.keyframes;
  const msg: ServerMessage = { type: 'replayInfo', start: kf[0]?.time ?? 0, end: session.world.time, keyframes: kf.map((k) => k.time) };
  if (c) send(c, msg);
  else broadcast(msg);
}

function clock(): ServerMessage {
  return { type: 'clock', time: session.world.time, step: Math.round(session.world.time / BASE_STEP), baseStep: BASE_STEP, mode: session.mode, speed: session.speed, rate: session.rate, replay: session.replay, playback: session.playback };
}

function handle(c: Client, msg: ClientMessage) {
  switch (msg.type) {
    case 'hello':
      if (msg.protocol !== PROTOCOL_VERSION) {
        send(c, { type: 'error', code: 'protocol', message: `Server speaks protocol ${PROTOCOL_VERSION}` });
        c.ws.close();
        return;
      }
      send(c, { type: 'welcome', protocol: PROTOCOL_VERSION, server: 'typhon-mock/0.1', fields: Object.keys(CODEC_FOR).map(Number) as FieldId[], mock: true });
      return;
    case 'listSessions':
      send(c, sessionsMessage());
      return;
    case 'listCatalog':
      send(c, { type: 'catalog', presets: [{ name: 'mock', title: 'Mock island', description: 'Synthetic data for UI work' }], worlds: [] });
      return;
    case 'sessionControl':
      if (msg.action === 'pause' || msg.action === 'resume') {
        session.mode = msg.action === 'pause' ? 'PAUSED' : 'REALTIME';
        broadcast(clock());
        broadcast(sessionsMessage());
        if (msg.requestId !== undefined) send(c, { type: 'ack', requestId: msg.requestId, ok: true });
      } else send(c, { type: 'error', code: 'unsupported', message: 'The mock server has one world and cannot close it', requestId: msg.requestId });
      return;
    case 'deleteWorld':
    case 'setParams':
      send(c, { type: 'error', code: 'unsupported', message: 'The mock server has no world files', requestId: msg.requestId });
      return;
    case 'setConfig':
      send(c, { type: 'configResult', requestId: msg.requestId, ok: false, errors: [{ path: 'world', message: 'The mock server has no world files' }] });
      return;
    case 'getSchema':
      send(c, schema());
      return;
    case 'createSession':
      session.reset(msg.seed ?? 1);
      for (const cl of clients) {
        cl.sent.clear();
        attach(cl);
      }
      return;
    case 'attach':
      if (msg.sessionId !== session.id) {
        send(c, { type: 'error', code: 'noSession', message: `No session ${msg.sessionId}` });
        return;
      }
      attach(c);
      return;
    case 'flow':
      c.tilesAcked = Math.min(c.tilesSent, Math.max(c.tilesAcked, msg.tilesProcessed));
      sendTiles(c);
      return;
    case 'subscribe':
      c.fields = new Set(msg.fields);
      c.tilesSent = 0;
      c.tilesAcked = 0;
      session.refreshVersions(c.fields, true);
      sendTiles(c, true);
      return;
    case 'transport':
      session.mode = msg.mode;
      if (msg.speed !== undefined) session.speed = Math.min(1e7, Math.max(1, msg.speed));
      broadcast(clock());
      return;
    case 'setSpeed':
      if (msg.speed === 'max') {
        if (session.mode !== 'PAUSED') session.mode = 'UNBOUNDED';
      } else {
        session.speed = Math.min(1e7, Math.max(1, msg.speed));
        if (session.mode === 'UNBOUNDED') session.mode = 'REALTIME';
      }
      session.playback = { ...session.playback, slowed: false, slowedBy: undefined };
      broadcast(clock());
      return;
    case 'setPlaybackPolicy': {
      const { type: _t, sessionId: _s, requestId: _r, ...policy } = msg;
      session.playback = { ...session.playback, ...policy };
      broadcast(clock());
      return;
    }
    case 'step': {
      if (session.replay) return;
      session.mode = 'PAUSED';
      const seconds = msg.steps !== undefined ? msg.steps * BASE_STEP : Math.ceil((msg.seconds ?? BASE_STEP) / BASE_STEP - 1e-9) * BASE_STEP;
      broadcast({ type: 'events', events: session.stepSeconds(Math.max(BASE_STEP, seconds)), dropped: 0 });
      broadcast(clock());
      return;
    }
    case 'pauseAt':
      session.pauseAt = msg.time;
      return;
    case 'command': {
      const w = session.world;
      const cmd = msg.command;
      let ok = true;
      switch (cmd.kind) {
        case 'startEruption':
          ok = w.forceEruption(cmd.volcanoId);
          break;
        case 'stopEruption':
          ok = w.stopEruption(cmd.volcanoId);
          break;
        case 'forceDike':
          ok = w.forceDike(cmd.volcanoId);
          break;
        case 'injectMagma':
          ok = w.injectMagma(cmd.volcanoId, cmd.volumeM3);
          break;
        case 'rain':
          w.rain = Math.max(0, cmd.mmPerHour);
          break;
        case 'addWater':
          w.addWater(cmd.at, cmd.volumeM3, cmd.seconds);
          break;
        case 'dig':
          w.dig(cmd.at, cmd.radius, cmd.depth);
          break;
        case 'setWind':
          w.wind = { speed: cmd.speed, bearingDeg: cmd.bearingDeg };
          break;
        default:
          // vent/dike lifecycle controls need the real engine
          send(c, { type: 'ack', requestId: msg.requestId ?? 0, ok: false, message: `The mock server does not support ${cmd.kind}` });
          send(c, { type: 'error', code: 'unsupported', message: `The mock server does not support ${cmd.kind}`, requestId: msg.requestId });
          return;
      }
      if (msg.requestId !== undefined) send(c, { type: 'ack', requestId: msg.requestId, ok, message: ok ? undefined : 'unknown volcano' });
      if (!ok) send(c, { type: 'error', code: 'unknownVolcano', message: 'Unknown volcano', requestId: msg.requestId });
      return;
    }
    case 'section': {
      if (process.env.MOCK_DEBUG) console.log('[MOCK] section request', JSON.stringify(msg));
      const nu = Math.min(1024, Math.max(8, msg.nu));
      const nz = Math.min(512, Math.max(8, msg.nz));
      if (msg.polyline.length < 2) {
        send(c, { type: 'error', code: 'badRequest', message: 'Section needs at least two points', requestId: msg.requestId });
        return;
      }
      const frame = encodeSectionFrame(session.world.section(msg.requestId, msg.polyline, msg.zMin, msg.zMax, nu, nz, msg.datum ?? 'absolute'));
      if (process.env.MOCK_DEBUG) console.log('[MOCK] section frame', frame.length, 'bytes; buffered', c.ws.bufferedAmount, 'clients', clients.size);
      c.ws.send(frame, (err) => err && console.log('[MOCK] section send failed', err));
      return;
    }
    case 'save':
    case 'load':
      send(c, { type: 'error', code: 'unsupported', message: 'The mock server does not persist saves' });
      return;
    case 'replay':
      if (msg.action === 'enter') {
        session.replay = true;
        session.keyframes.push(session.world.snapshot()); // remember "live" to return to
        session.lastKeyframe = session.world.time;
      } else {
        session.replay = false;
        const live = session.keyframes[session.keyframes.length - 1];
        if (live) session.world.restore(live);
        broadcast({ type: 'replayReset', time: session.world.time });
        for (const cl of clients) {
          session.refreshVersions(cl.fields, true);
          sendTiles(cl, true);
        }
      }
      sendReplayInfo();
      broadcast(clock());
      return;
    case 'seek': {
      if (!session.replay) {
        send(c, { type: 'error', code: 'badRequest', message: 'Enter replay mode before seeking' });
        return;
      }
      let best = session.keyframes[0];
      for (const k of session.keyframes) if (k.time <= msg.time) best = k;
      if (!best) return;
      session.world.restore(best);
      broadcast({ type: 'replayReset', time: best.time });
      for (const cl of clients) {
        session.refreshVersions(cl.fields, true);
        sendTiles(cl, true);
      }
      broadcast(session.world.state());
      broadcast({ type: 'events', events: recentEvents(session.eventLog, best.time), dropped: 0 });
      broadcast(clock());
      return;
    }
  }
}

const wss = new WebSocketServer({
  port: PORT,
  path: '/ws',
  handleProtocols: (protocols) => (protocols.has(WS_SUBPROTOCOL) ? WS_SUBPROTOCOL : false),
});

wss.on('connection', (ws) => {
  const c: Client = { ws, fields: new Set(), sent: new Map(), tilesSent: 0, tilesAcked: 0 };
  clients.add(c);
  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      send(c, { type: 'error', code: 'protocol', message: 'Clients send JSON text frames only' });
      return;
    }
    try {
      handle(c, JSON.parse(data.toString()) as ClientMessage);
    } catch (e) {
      send(c, { type: 'error', code: 'internal', message: String(e) });
    }
  });
  ws.on('close', () => clients.delete(c));
});

// Tile pump: keeps streaming pending tiles to every client within its buffer budget
setInterval(() => {
  for (const c of clients) sendTiles(c);
}, 50);

// Simulation clock
let last = performance.now();
setInterval(() => {
  const now = performance.now();
  const wall = (now - last) / 1000;
  last = now;
  const events = session.advance(wall);
  if (events.length) broadcast({ type: 'events', events, dropped: 0 });
}, WALL_TICK_MS);

// Fan-out of state, clock and changed tiles
let lastReplayInfo = 0;
let sentUnits = 0;
setInterval(() => {
  if (clients.size === 0) return;
  const units = session.world.units;
  if (units.length < sentUnits) {
    broadcast({ type: 'units', units, replace: true });
    sentUnits = units.length;
  } else if (units.length > sentUnits) {
    broadcast({ type: 'units', units: units.slice(sentUnits), replace: false });
    sentUnits = units.length;
  }
  const fields = new Set<FieldId>();
  for (const c of clients) for (const f of c.fields) fields.add(f);
  session.refreshVersions(fields);
  broadcast(clock());
  broadcast(session.world.state());
  for (const c of clients) sendTiles(c);
  if (performance.now() - lastReplayInfo > 2000) {
    sendReplayInfo();
    lastReplayInfo = performance.now();
  }
}, BROADCAST_MS);

console.log(`[MOCK] Typhon mock sim-server on ws://localhost:${PORT}/ws (protocol v${PROTOCOL_VERSION}, subprotocol ${WS_SUBPROTOCOL})`);
console.log(`[MOCK] fields: ${Object.values(FIELD_NAMES).join(', ')}`);
