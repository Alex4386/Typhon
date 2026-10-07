import type { ParamSpec, SchemaMessage, VolcanoState } from '../protocol/messages';
import type { EntityMap, Selection } from '../store/entities';

/** Something the Inspector offers for the current selection. */
export type ContextAction =
  | { id: 'inject'; label: string; volcanoId: string }
  | { id: 'startEruption' | 'stopEruption' | 'forceDike'; label: string; volcanoId: string }
  | { id: 'frame'; label: string }
  | { id: 'section'; label: string }
  | { id: 'water' | 'dig'; label: string }
  | { id: 'removeVent'; label: string; volcanoId: string; ventId: string }
  | { id: 'removeDike'; label: string; volcanoId: string; dikeId: number }
  | { id: 'removeVolcano'; label: string; volcanoId: string }
  | { id: 'inspect'; label: string; target: string };

/** A state the Inspector shows as a checkbox (a setting that stays, unlike a one-off action). */
export type ContextToggle =
  | { id: 'blockDikes'; label: string; help: string; checked: boolean; volcanoId: string }
  | { id: 'sealVent'; label: string; help: string; checked: boolean; volcanoId: string; ventId: string; disabled?: string };

/** Actions that cannot be undone: the Inspector asks first. */
export const DESTRUCTIVE: ReadonlySet<ContextAction['id']> = new Set(['removeVent', 'removeDike']);

/** Lifecycle of a vent or fissure as the server reports it (`props.state`, `ventState` events). */
export type VentLifecycle = 'idle' | 'active' | 'waning' | 'frozen' | 'sealed' | 'removed';

/** A vent's state for display: the server's, else derived from its flags (older servers). */
export function ventLifecycle(props: Record<string, unknown>): VentLifecycle {
  const s = props.state;
  if (s === 'idle' || s === 'active' || s === 'waning' || s === 'frozen' || s === 'sealed' || s === 'removed') return s;
  if (props.sealed === true) return 'sealed';
  return props.erupting === true ? 'active' : 'idle';
}

/** The dike number of a `dike:<volcano>:<id>` entity. */
export function dikeNumber(entityId: string): number | null {
  const n = Number(entityId.slice(entityId.lastIndexOf(':') + 1));
  return Number.isInteger(n) ? n : null;
}

/** Volcano a selection belongs to (its entity's volcano; chambers, vents and dikes always have one). */
export function selectionVolcano(sel: Selection | null, entities: EntityMap): string | null {
  if (!sel) return null;
  if (sel.type === 'entity') return entities[sel.id]?.volcanoId ?? null;
  if (sel.type === 'quake') return sel.event.volcanoId ?? null;
  return null;
}

function eruptionToggle(volcanoId: string, vs: VolcanoState | undefined): ContextAction {
  const erupting = (vs?.chamber.eruptionRate ?? 0) > 0 || vs?.alert.level === 'ERUPTING';
  return erupting ? { id: 'stopEruption', label: 'Stop eruption', volcanoId } : { id: 'startEruption', label: 'Start eruption', volcanoId };
}

/**
 * One-off actions for a selection, in display order: a chamber offers magma (add a batch), eruption
 * control and a dike; a vent or fissure eruption control, framing and a section; a dike a section along
 * it; a ground point pouring water or digging there; everything framing. Lasting states (dikes blocked,
 * a vent sealed) are {@link contextToggles}.
 */
export function contextActions(sel: Selection | null, entities: EntityMap, volcanoes: Record<string, VolcanoState> | undefined): ContextAction[] {
  if (!sel) return [];
  const out: ContextAction[] = [];
  if (sel.type === 'point') {
    out.push({ id: 'water', label: 'Pour water here' }, { id: 'dig', label: 'Dig here' }, { id: 'section', label: 'Cross-section' });
    return out;
  }
  if (sel.type === 'quake') return [{ id: 'frame', label: 'Frame' }, { id: 'section', label: 'Cross-section' }];
  const e = entities[sel.id];
  if (!e) return [];
  const v = e.volcanoId;
  switch (e.kind) {
    case 'chamber':
      if (v) {
        out.push({ id: 'inject', label: 'Add magma…', volcanoId: v }, eruptionToggle(v, volcanoes?.[v]), { id: 'forceDike', label: 'Push magma up (dike)', volcanoId: v });
      }
      out.push({ id: 'section', label: 'Cross-section' });
      // removal is a reset: the server describes it and asks to confirm
      if (v) out.push({ id: 'removeVolcano', label: 'Remove volcano…', volcanoId: v });
      return out;
    case 'vent':
    case 'fissure': {
      if (v) out.push(eruptionToggle(v, volcanoes?.[v]));
      const ventId = typeof e.props.ventId === 'string' ? e.props.ventId : null;
      const state = ventLifecycle(e.props);
      if (v && ventId) out.push(...relatedDikes(entities, v, ventId));
      if (v && entities[`chamber:${v}`]) out.push({ id: 'inspect', label: 'Inspect chamber', target: `chamber:${v}` });
      // only dike-fed fissures can be deleted; a summit vent is sealed instead
      if (v && ventId && state !== 'removed' && e.kind === 'fissure') out.push({ id: 'removeVent', label: 'Remove…', volcanoId: v, ventId });
      out.push({ id: 'frame', label: 'Frame' }, { id: 'section', label: 'Cross-section' });
      return out;
    }
    case 'dike': {
      const fissure = typeof e.props.fissure === 'string' && v ? `vent:${v}:${e.props.fissure}` : null;
      if (fissure && entities[fissure]) out.push({ id: 'inspect', label: 'Inspect fissure', target: fissure });
      if (v && entities[`chamber:${v}`]) out.push({ id: 'inspect', label: 'Inspect chamber', target: `chamber:${v}` });
      out.push({ id: 'section', label: 'Section along the dike' }, { id: 'frame', label: 'Frame' });
      const n = dikeNumber(e.id);
      if (v && n !== null) out.push({ id: 'removeDike', label: 'Remove…', volcanoId: v, dikeId: n });
      return out;
    }
    default:
      return [{ id: 'frame', label: 'Frame' }, { id: 'section', label: 'Cross-section' }];
  }
}

/** Links to the dikes that feed a vent (a dike's `fissure` prop names the vent it opened). */
function relatedDikes(entities: EntityMap, volcanoId: string, ventId: string): ContextAction[] {
  return Object.values(entities)
    .filter((d) => d.kind === 'dike' && d.volcanoId === volcanoId && d.props.fissure === ventId && d.removedAt === undefined)
    .sort((a, b) => a.id.localeCompare(b.id))
    .map((d) => ({ id: 'inspect' as const, label: `Inspect ${d.label || 'dike'}`, target: d.id }));
}

/** Lasting states of a selection, shown as checkboxes: a chamber's dike blocking, a vent's seal. */
export function contextToggles(sel: Selection | null, entities: EntityMap): ContextToggle[] {
  if (!sel || sel.type !== 'entity') return [];
  const e = entities[sel.id];
  const v = e?.volcanoId;
  if (!e || !v) return [];
  if (e.kind === 'chamber') {
    return [{ id: 'blockDikes', label: 'Block new dikes', help: 'No new dikes start from this chamber (rising ones go on); magma beyond the walls\' limit then grows the chamber.', checked: e.props.dikesBlocked === true, volcanoId: v }];
  }
  if (e.kind === 'vent' || e.kind === 'fissure') {
    const ventId = typeof e.props.ventId === 'string' ? e.props.ventId : null;
    const state = ventLifecycle(e.props);
    if (!ventId || state === 'removed') return [];
    return [
      {
        id: 'sealVent',
        label: 'Sealed',
        help: 'Plugged: magma leaves through the other open vents; with none left the eruption ends and pressure builds.',
        checked: state === 'sealed',
        volcanoId: v,
        ventId,
        ...(state === 'frozen' ? { disabled: 'Its feeder froze shut; a seal changes nothing' } : {}),
      },
    ];
  }
  return [];
}

/** Injection fields for a volcano: its own (defaults from its supply magma), else the generic ones. */
export function injectFieldsFor(schema: SchemaMessage | null, volcanoId: string | null | undefined): ParamSpec[] | undefined {
  if (!schema) return undefined;
  return (volcanoId ? schema.commands[`injectMagma@${volcanoId}`] : undefined) ?? schema.commands.injectMagma;
}
