import { Field, type FieldId } from '../protocol/fields';
import { FrameKind, decodeSectionFrame, decodeTileFrame, type TileFrame } from '../protocol/frames';
import {
  PROTOCOL_VERSION,
  WS_SUBPROTOCOL,
  type ClientMessage,
  type ParamValue,
  type SectionDatum,
  type ServerMessage,
  type SessionAction,
  type SessionInfo,
  type SimCommand,
  type SimEvent,
  type XY,
} from '../protocol/messages';
import { rememberSession, rememberedSession, useStore } from '../store/store';
import { formatPlace, KIND_LABEL, type EntityView } from '../store/entities';
import { useCamera } from '../camera/cameraStore';
import { describeEvent, toastTone } from '../panels/events';

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
    // The server forgets the attachment with the connection; reattach on reconnect.
    useStore.getState().set({ sessionId: null });
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

/** Switches this client to another loaded world. */
export function attachSession(sessionId: string): void {
  if (useStore.getState().sessionId === sessionId) return;
  userDetached = false;
  send({ type: 'attach', sessionId });
}

export interface NewSessionOptions {
  preset?: string;
  world?: string;
  name?: string;
  seed?: number;
  dormant?: number;
  eruptive?: number;
  paused?: boolean;
  /** Switch to the new world (default true). */
  attach?: boolean;
}

/** Starts a world from a preset (written to the worlds dir) or opens a world directory. */
export function createSession(o: NewSessionOptions): void {
  const timeCompression = o.dormant !== undefined || o.eruptive !== undefined ? { dormant: o.dormant, eruptive: o.eruptive } : undefined;
  send({
    type: 'createSession',
    requestId: requestSeq++,
    preset: o.preset,
    world: o.world,
    name: o.name,
    seed: o.seed,
    paused: o.paused,
    attach: o.attach ?? true,
    ...(timeCompression ? { timeCompression } : {}),
  });
}

export function controlSession(sessionId: string, action: SessionAction): void {
  send({ type: 'sessionControl', requestId: requestSeq++, sessionId, action });
}

export function deleteWorld(name: string): void {
  send({ type: 'deleteWorld', requestId: requestSeq++, name });
}

/** Changes tunable parameters of the attached session (`null` = back to default). */
export function setParams(values: Record<string, ParamValue | null>, restart = false): void {
  send({ type: 'setParams', requestId: requestSeq++, values, ...(restart ? { restart } : {}) });
}

export function refreshCatalog(): void {
  send({ type: 'listCatalog' });
}

/** Stable key of a session across server restarts: its world directory, else its name. */
export function sessionKey(s: SessionInfo): string {
  return s.world ? `world:${s.world}` : `name:${s.name}`;
}

/** True once the user chose to leave a world (do not auto-attach somewhere else). */
let userDetached = false;
/** Events arriving right after an attach are backlog: no toasts for them. */
let toastsQuietUntil = 0;

/** Repeats of the same kind of notification (per volcano) are held back for this long. */
const TOAST_REPEAT_MS = 120_000;
const lastToast = new Map<string, number>();

function maybeToast(events: SimEvent[]): void {
  const now = performance.now();
  if (now < toastsQuietUntil) return;
  const s = useStore.getState();
  for (const e of events) {
    const tone = toastTone(e);
    if (!tone) continue;
    // eruptions and status changes always show; recurring phenomena (quakes, steam blasts) at most every 2 min
    if (tone !== 'alert' && e.kind !== 'alertChanged') {
      const key = `${e.kind}:${'volcanoId' in e ? e.volcanoId : ''}`;
      if (now - (lastToast.get(key) ?? -Infinity) < TOAST_REPEAT_MS) continue;
      lastToast.set(key, now);
    }
    const who = 'volcanoId' in e ? s.world?.volcanoes.find((v) => v.id === e.volcanoId)?.name ?? e.volcanoId : null;
    s.toast(`${who ? who + ': ' : ''}${describeEvent(e)}`, tone);
  }
}

/** Picks a session to watch when the client is not attached to one. */
function autoAttach(sessions: SessionInfo[]): void {
  const s = useStore.getState();
  if (s.sessionId || userDetached) return;
  if (sessions.length === 0) {
    if (!s.drawer) s.set({ drawer: 'sims' });
    return;
  }
  const remembered = rememberedSession();
  const pick = sessions.find((x) => sessionKey(x) === remembered) ?? sessions[0];
  send({ type: 'attach', sessionId: pick.id });
}

/** Requests a section; `datum: 'surface'` asks for the ground-relative (shallow) companion view. */
export function requestSection(polyline: XY[], zMin: number, zMax: number, nu: number, nz: number, datum: SectionDatum = 'absolute'): void {
  const requestId = requestSeq++;
  sectionDatum.set(requestId, datum);
  useStore.getState().set(datum === 'surface' ? { sectionShallowPending: requestId } : { sectionPending: requestId });
  send({ type: 'section', requestId, polyline, zMin, zMax, nu, nz, ...(datum === 'surface' ? { datum } : {}) });
}

/** Asks the server about the column at (x, y); the reply lands in `inspection`. */
export function inspect(x: number, y: number): void {
  const requestId = requestSeq++;
  useStore.getState().set({ inspectPending: requestId });
  send({ type: 'inspect', requestId, x, y });
}

/** Selects an entity and flies the camera to it. */
export function showEntity(id: string): void {
  const s = useStore.getState();
  const e = s.entities[id];
  if (!e) return;
  s.select({ type: 'entity', id });
  useCamera.getState().requestCamera({ kind: 'frameSelection' });
}

/** Kinds worth a notification when they appear while watching. */
const ANNOUNCED = new Set(['vent', 'fissure', 'dike', 'feature', 'pdc', 'lahar', 'lavaFront']);

/** “New hot spring at E … N …” with a button that shows it; several at once are summarised. */
function announce(added: EntityView[]): void {
  if (performance.now() < toastsQuietUntil) return;
  const s = useStore.getState();
  const news = added.filter((e) => ANNOUNCED.has(e.kind) && !e.hidden);
  if (news.length === 0) return;
  if (news.length > 2) {
    const kinds = [...new Set(news.map((e) => (e.kind === 'feature' ? e.label.toLowerCase() : KIND_LABEL[e.kind]?.toLowerCase() ?? e.kind)))];
    s.toast(`${news.length} new: ${kinds.slice(0, 3).join(', ')}${kinds.length > 3 ? '…' : ''}`, 'info', {
      label: 'List',
      onClick: () => useStore.getState().set({ drawer: 'entities' }),
    });
    return;
  }
  for (const e of news) {
    const what = e.kind === 'feature' ? e.label : e.label || KIND_LABEL[e.kind] || e.kind;
    s.toast(`New ${what.charAt(0).toLowerCase() + what.slice(1)} at ${formatPlace(e.at)}`, e.kind === 'fissure' || e.kind === 'pdc' ? 'warn' : 'info', {
      label: 'Show',
      onClick: () => showEntity(e.id),
    });
  }
}

/** Datum of each outstanding section request (replies from older servers carry no `datum`). */
const sectionDatum = new Map<number, SectionDatum>();

function onText(m: ServerMessage): void {
  const s = useStore.getState();
  messageCounts[m.type] = (messageCounts[m.type] ?? 0) + 1;
  switch (m.type) {
    case 'welcome':
      s.set({ welcome: m });
      send({ type: 'listCatalog' });
      return;
    case 'sessions':
      s.set({ sessions: m.sessions, serverInfo: m.server ?? s.serverInfo });
      if (s.sessionId && !m.sessions.some((x) => x.id === s.sessionId)) {
        s.resetSession();
        s.set({ sessionId: null });
      }
      autoAttach(m.sessions);
      return;
    case 'catalog':
      s.set({ catalog: m, serverInfo: m.server ?? s.serverInfo });
      return;
    case 'detached':
      if (s.sessionId === m.sessionId) {
        s.resetSession();
        s.set({ sessionId: null });
        s.toast('The world you were watching was closed. Pick another one.', 'info');
        send({ type: 'listSessions' });
      }
      return;
    case 'attached': {
      s.resetSession();
      tilesProcessed = 0;
      pendingTiles = [];
      toastsQuietUntil = performance.now() + 2500;
      s.set({ world: m.world, sessionId: m.sessionId });
      const info = s.sessions.find((x) => x.id === m.sessionId);
      rememberSession(info ? sessionKey(info) : `name:${m.world.name}`);
      send({ type: 'subscribe', fields: SUBSCRIBED_FIELDS });
      return;
    }
    case 'clock':
      s.set({ clock: { ...m, receivedAt: performance.now() } });
      return;
    case 'state':
      s.applyState(m);
      return;
    case 'events':
      s.addEvents(m.events, m.dropped);
      maybeToast(m.events);
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
      else if (m.message && /^(Saved|Loaded|Deleted|Applied|Restarted)/.test(m.message)) s.toast(m.message, 'info');
      return;
    case 'schema':
      if (m.sessionId === s.sessionId) s.set({ schema: m });
      return;
    case 'entities':
      announce(s.applyEntities(m));
      return;
    case 'inspection':
      if (s.inspectPending === null || (m.requestId ?? 0) >= s.inspectPending) s.set({ inspection: m, inspectPending: null });
      return;
    case 'error':
      if (m.requestId !== undefined && m.requestId === s.inspectPending) s.set({ inspectPending: null });
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
