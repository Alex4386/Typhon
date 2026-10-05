import { Field, type FieldId } from '../protocol/fields';
import { FrameKind, decodeSectionFrame, decodeTileFrame, type TileFrame } from '../protocol/frames';
import {
  PROTOCOL_VERSION,
  WS_SUBPROTOCOL,
  type ClientMessage,
  type SectionDatum,
  type ServerMessage,
  type SimCommand,
  type XY,
} from '../protocol/messages';
import { useStore } from '../store/store';

/** Fields the visualizer subscribes to. */
export const SUBSCRIBED_FIELDS: FieldId[] = [
  Field.SurfaceElevation,
  Field.LavaDepth,
  Field.LavaTemperature,
  Field.WaterDepth,
  Field.PdcDepth,
  Field.LaharDepth,
  Field.AshDepth,
  Field.SurfaceTemperature,
  Field.WaterTableDepth,
  Field.TopUnit,
  Field.Uplift,
  Field.SteamFraction,
];

/** Per-type message counters (debugging aid, exposed as window.__typhonMessages in dev). */
export const messageCounts: Record<string, number> = {};
if (import.meta.env.DEV || new URLSearchParams(window.location.search).has("debug")) {
  (window as unknown as { __typhonMessages: typeof messageCounts }).__typhonMessages = messageCounts;
}

let ws: WebSocket | null = null;
let retry: number | undefined;
let requestSeq = 1;
let pendingTiles: TileFrame[] = [];
let tilesProcessed = 0;
let flushScheduled = false;

/** Tiles arrive in bursts; apply them to the store at most once per animation frame. */
function queueTile(f: TileFrame): void {
  pendingTiles.push(f);
  if (flushScheduled) return;
  flushScheduled = true;
  requestAnimationFrame(() => {
    flushScheduled = false;
    const batch = pendingTiles;
    pendingTiles = [];
    useStore.getState().applyTiles(batch);
    tilesProcessed += batch.length;
    send({ type: 'flow', tilesProcessed });
  });
}

export function connect(): void {
  const store = useStore.getState();
  window.clearTimeout(retry);
  if (ws) {
    const old = ws;
    ws = null;
    old.onclose = null;
    old.close();
  }
  store.setStatus('connecting');
  const sock = new WebSocket(store.serverUrl, WS_SUBPROTOCOL);
  sock.binaryType = 'arraybuffer';
  ws = sock;
  sock.onopen = () => {
    useStore.getState().setStatus('open');
    send({ type: 'hello', protocol: PROTOCOL_VERSION, client: 'typhon-visualizer/0.1' });
    send({ type: 'listSessions' });
  };
  sock.onclose = () => {
    if (ws !== sock) return;
    useStore.getState().setStatus('closed');
    retry = window.setTimeout(connect, 2000);
  };
  sock.onerror = () => sock.close();
  sock.onmessage = (ev) => {
    if (typeof ev.data === 'string') onText(JSON.parse(ev.data) as ServerMessage);
    else onBinary(new Uint8Array(ev.data as ArrayBuffer));
  };
}

export function send(m: ClientMessage): void {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(m));
}

export function command(c: SimCommand): void {
  send({ type: 'command', requestId: requestSeq++, command: c });
}

/** Requests a section; `datum: 'surface'` asks for the ground-relative (shallow) companion view. */
export function requestSection(polyline: XY[], zMin: number, zMax: number, nu: number, nz: number, datum: SectionDatum = 'absolute'): void {
  const requestId = requestSeq++;
  sectionDatum.set(requestId, datum);
  useStore.getState().set(datum === 'surface' ? { sectionShallowPending: requestId } : { sectionPending: requestId });
  send({ type: 'section', requestId, polyline, zMin, zMax, nu, nz, ...(datum === 'surface' ? { datum } : {}) });
}

/** Datum of each outstanding section request (replies from older servers carry no `datum`). */
const sectionDatum = new Map<number, SectionDatum>();

function onText(m: ServerMessage): void {
  const s = useStore.getState();
  messageCounts[m.type] = (messageCounts[m.type] ?? 0) + 1;
  switch (m.type) {
    case 'welcome':
      s.set({ welcome: m });
      return;
    case 'sessions':
      if (m.sessions.length > 0) send({ type: 'attach', sessionId: m.sessions[0].id });
      else send({ type: 'createSession', preset: 'default' });
      return;
    case 'attached':
      s.set({ world: m.world, section: null, sectionShallow: null, sectionPolyline: [] });
      tilesProcessed = 0;
      send({ type: 'subscribe', fields: SUBSCRIBED_FIELDS });
      return;
    case 'clock':
      s.set({ clock: { ...m, receivedAt: performance.now() } });
      return;
    case 'state':
      s.applyState(m);
      return;
    case 'events':
      s.addEvents(m.events, m.dropped);
      return;
    case 'replayInfo':
      s.set({ replayInfo: m });
      return;
    case 'replayReset':
      s.clearForReplay(m.time);
      return;
    case 'units': {
      const units = m.replace ? {} : { ...s.units };
      for (const u of m.units) units[u.id] = u;
      s.set({ units });
      return;
    }
    case 'ack':
      if (!m.ok) s.pushError(m.message ?? `Request ${m.requestId} failed`);
      return;
    case 'error':
      s.pushError(`${m.code}: ${m.message}`);
      return;
  }
}

function onBinary(buf: Uint8Array): void {
  const s = useStore.getState();
  messageCounts.binary = (messageCounts.binary ?? 0) + 1;
  switch (buf[0]) {
    case FrameKind.Tile:
      queueTile(decodeTileFrame(buf));
      return;
    case FrameKind.Section: {
      messageCounts.section = (messageCounts.section ?? 0) + 1;
      const sec = decodeSectionFrame(buf);
      const datum = sec.meta.datum ?? sectionDatum.get(sec.meta.requestId) ?? 'absolute';
      sectionDatum.delete(sec.meta.requestId);
      if (datum === 'surface') {
        if (s.sectionShallowPending === null || sec.meta.requestId >= s.sectionShallowPending) s.set({ sectionShallow: sec, sectionShallowPending: null });
      } else if (s.sectionPending === null || sec.meta.requestId >= s.sectionPending) s.set({ section: sec, sectionPending: null });
      return;
    }
    default:
      s.pushError(`Unknown binary frame kind ${buf[0]}`);
  }
}
