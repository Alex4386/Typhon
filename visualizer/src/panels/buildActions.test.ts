import { beforeAll, describe, expect, it, vi } from 'vitest';
import type { ConfigResult } from '../protocol/messages';
import { draftAtClick } from './builder';

// The store reads the page location and storage when it loads: give it a minimal window.
const memory = new Map<string, string>();
vi.stubGlobal('window', {
  location: new URL('http://localhost:5180/'),
  localStorage: { getItem: (k: string) => memory.get(k) ?? null, setItem: (k: string, v: string) => memory.set(k, v) },
  setInterval: () => 0,
});

// The server: records what the builder sends and answers like the real one.
const sent: { kind: string; args: unknown[] }[] = [];
const ok = (extra: Partial<ConfigResult> = {}): ConfigResult => ({ ok: true, applied: 'reload', consequences: [{ kind: 'reload', target: 'volcano', message: 'Adds it.' }], ...extra });
let definitions: Record<string, Record<string, unknown>> = {};
vi.mock('../net/connection', () => ({
  placeChamber: vi.fn(async (...args: unknown[]) => {
    sent.push({ kind: 'placeChamber', args });
    definitions['volcano-1'] = { id: 'volcano-1', magma: { chamber: {} } };
    return ok({ volcanoId: 'volcano-1' });
  }),
  plumbing: vi.fn(async (...args: unknown[]) => {
    sent.push({ kind: 'plumbing', args });
    return ok({ chamberId: 'chamber-1' });
  }),
  getConfig: vi.fn(async () => ({ type: 'config', world: {}, volcanoes: definitions })),
  removeVolcano: vi.fn(async () => ok()),
  setConfigTrees: vi.fn(async (...args: unknown[]) => {
    sent.push({ kind: 'setConfig', args });
    return ok();
  }),
}));

let actions: typeof import('./buildActions');
let store: typeof import('../store/store');
beforeAll(async () => {
  store = await import('../store/store');
  actions = await import('./buildActions');
});

describe('place-chamber wiring', () => {
  it('a map click with the tool opens a draft at the point, in the right volcano', () => {
    const empty = draftAtClick({ buildDraft: null, selectedVolcano: null, world: { volcanoes: [] } }, [120, -40]);
    expect(empty).toMatchObject({ drawer: 'build', tool: 'orbit', buildDraft: { volcanoId: null, at: [120, -40] } });
    const into = draftAtClick({ buildDraft: null, selectedVolcano: 'b', world: { volcanoes: [{ id: 'a' }, { id: 'b' }] } }, [0, 0]);
    expect(into.buildDraft.volcanoId).toBe('b');
    const moved = draftAtClick({ buildDraft: { kind: 'chamber', volcanoId: 'a', at: [1, 1], values: { depthM: 2500 } }, selectedVolcano: null, world: null }, [5, 6]);
    expect(moved.buildDraft).toEqual({ kind: 'chamber', volcanoId: 'a', at: [5, 6], values: { depthM: 2500 } });
  });

  it('placing sends the position and numeric fields, and records an undoable step', async () => {
    sent.length = 0;
    definitions = {};
    const r = await actions.placeDraftChamber(null, [120, -40], { x: 120, y: -40, depthM: 3000, volumeM3: 2e9 });
    expect(r?.volcanoId).toBe('volcano-1');
    expect(sent[0]).toEqual({ kind: 'placeChamber', args: [[120, -40], { depthM: 3000, volumeM3: 2e9 }, undefined] });
    const h = store.useStore.getState().buildHistory;
    expect(h.undo.at(-1)).toMatchObject({ label: 'Place volcano', volcanoId: 'volcano-1', before: null });
  });

  it('a further chamber goes through the plumbing edit of its volcano', async () => {
    sent.length = 0;
    await actions.placeDraftChamber('volcano-1', [0, 0], { depthM: 5000 });
    expect(sent[0].kind).toBe('plumbing');
    expect(sent[0].args[0]).toMatchObject({ volcanoId: 'volcano-1', op: 'addChamber', at: [0, 0], fields: { depthM: 5000 } });
  });

  it('undo sends the recorded definition back through the configuration API', async () => {
    sent.length = 0;
    definitions['volcano-1'] = { id: 'volcano-1', magma: { chamber: {}, chambers: [{ id: 'chamber-1' }] } };
    store.useStore.setState({
      buildHistory: { undo: [{ label: 'Add chamber', volcanoId: 'volcano-1', before: { id: 'volcano-1', magma: { chamber: { volume: 1 } } }, after: null }], redo: [] },
    });
    await actions.undoBuild();
    expect(sent[0].kind).toBe('setConfig');
    expect(sent[0].args[0]).toEqual({ 'volcano-1': { magma: { chamber: { volume: 1 }, chambers: [], connections: [] } } });
    expect(store.useStore.getState().buildHistory.redo).toHaveLength(1);
  });
});
