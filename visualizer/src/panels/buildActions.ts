import { getConfig, placeChamber, plumbing, removeVolcano, setConfigTrees } from '../net/connection';
import type { ConfigResult, ParamValue, XY } from '../protocol/messages';
import { useStore } from '../store/store';
import { pushStep, redoStep, restoreChange, undoStep, type BuildStep } from './builder';
import { showServerResult } from './serverResult';

/**
 * Runs a configuration request and follows the server through it: its answer is shown in its own
 * words; a reset asks the user (the server's description, Confirm/Cancel) and is re-sent with the token.
 * Resolves with the final successful result, or null when it failed or the user cancelled.
 */
export function runConfirmed(run: (confirm?: string) => Promise<ConfigResult>): Promise<ConfigResult | null> {
  return new Promise((resolve) => {
    void run().then((r) =>
      showServerResult(
        r,
        (token) => run(token),
        (ok) => resolve(ok),
        () => resolve(null),
      ),
    );
  });
}

async function snapshot(volcanoId: string): Promise<Record<string, unknown> | null> {
  const c = await getConfig();
  return c.volcanoes?.[volcanoId] ?? null;
}

/**
 * A builder edit of volcano `volcanoId` (null: it creates one, named by the result): applied through the
 * server, then recorded with the definition before and after so it can be undone and redone.
 */
async function edit(label: string, volcanoId: string | null, run: (confirm?: string) => Promise<ConfigResult>): Promise<ConfigResult | null> {
  const before = volcanoId ? await snapshot(volcanoId) : null;
  const r = await runConfirmed(run);
  if (!r) return null;
  const vid = volcanoId ?? r.volcanoId;
  if (vid) {
    const after = await snapshot(vid);
    const step: BuildStep = { label, volcanoId: vid, before, after };
    useStore.setState((s) => ({ buildHistory: pushStep(s.buildHistory, step) }));
  }
  return r;
}

const numbers = (values: Record<string, ParamValue>): Record<string, number> => {
  const out: Record<string, number> = {};
  for (const [k, v] of Object.entries(values)) if (typeof v === 'number' && k !== 'x' && k !== 'y') out[k] = v;
  return out;
};

/** Places a chamber: a new volcano, or a further chamber of `volcanoId`. */
export function placeDraftChamber(volcanoId: string | null, at: XY, values: Record<string, ParamValue>, name?: string): Promise<ConfigResult | null> {
  if (volcanoId === null) return edit('Place volcano', null, () => placeChamber(at, numbers(values), name));
  return edit('Add chamber', volcanoId, (confirm) => plumbing({ volcanoId, op: 'addChamber', at, fields: numbers(values), confirm }));
}

/** Moves or retunes an existing chamber (`main` is the volcano's eruptive one). */
export function editChamber(volcanoId: string, chamberId: string, at: XY | undefined, values: Record<string, ParamValue>): Promise<ConfigResult | null> {
  return edit(`Edit chamber ${chamberId}`, volcanoId, (confirm) => plumbing({ volcanoId, op: 'editChamber', chamberId, ...(at ? { at } : {}), fields: numbers(values), confirm }));
}

/** What moving or retuning a chamber would do, from the server (nothing is applied). */
export function previewChamberEdit(volcanoId: string, chamberId: string, at: XY | undefined, values: Record<string, ParamValue>): Promise<ConfigResult> {
  return plumbing({ volcanoId, op: 'editChamber', chamberId, ...(at ? { at } : {}), fields: numbers(values), dryRun: true });
}

export function removeChamber(volcanoId: string, chamberId: string): Promise<ConfigResult | null> {
  if (chamberId === 'main') return edit('Remove volcano', volcanoId, (confirm) => removeVolcano(volcanoId, confirm));
  return edit(`Remove chamber ${chamberId}`, volcanoId, (confirm) => plumbing({ volcanoId, op: 'removeChamber', chamberId, confirm }));
}

export function connectChambers(volcanoId: string, from: string, to: string, kind: 'conduit' | 'dike', values: Record<string, ParamValue>): Promise<ConfigResult | null> {
  const fields: Record<string, ParamValue> = {};
  for (const [k, v] of Object.entries(values)) if (k !== 'kind') fields[k] = v;
  return edit(`Connect ${from} → ${to}`, volcanoId, (confirm) => plumbing({ volcanoId, op: 'connect', from, to, kind, fields, confirm }));
}

export function editConnection(volcanoId: string, connectionId: string, values: Record<string, ParamValue>): Promise<ConfigResult | null> {
  return edit(`Edit pathway ${connectionId}`, volcanoId, (confirm) => plumbing({ volcanoId, op: 'editConnection', connectionId, fields: values, confirm }));
}

export function removeConnection(volcanoId: string, connectionId: string): Promise<ConfigResult | null> {
  return edit(`Remove pathway ${connectionId}`, volcanoId, (confirm) => plumbing({ volcanoId, op: 'removeConnection', connectionId, confirm }));
}

/** Sends a volcano back to a recorded definition (undo/redo), through the configuration API. */
async function restore(volcanoId: string, tree: Record<string, unknown> | null): Promise<ConfigResult | null> {
  const exists = (await snapshot(volcanoId)) !== null;
  const change = restoreChange(tree, exists);
  if (change === null) return exists ? runConfirmed((confirm) => removeVolcano(volcanoId, confirm)) : null;
  return runConfirmed((confirm) => setConfigTrees({ [volcanoId]: change }, { confirm }));
}

/** Undoes the last builder edit (Ctrl+Z). */
export async function undoBuild(): Promise<void> {
  const u = undoStep(useStore.getState().buildHistory);
  if (!u) return;
  const r = await restore(u.step.volcanoId, u.step.before);
  if (r) {
    useStore.setState({ buildHistory: u.history });
    useStore.getState().toast(`Undid: ${u.step.label}`, 'info');
  }
}

/** Redoes the last undone builder edit (Ctrl+Y / Ctrl+Shift+Z). */
export async function redoBuild(): Promise<void> {
  const u = redoStep(useStore.getState().buildHistory);
  if (!u) return;
  const r = await restore(u.step.volcanoId, u.step.after);
  if (r) {
    useStore.setState({ buildHistory: u.history });
    useStore.getState().toast(`Redid: ${u.step.label}`, 'info');
  }
}
