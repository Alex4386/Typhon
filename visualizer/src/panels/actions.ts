import type { ParamSpec, SchemaMessage, VolcanoState } from '../protocol/messages';
import type { EntityMap, Selection } from '../store/entities';

/** Something the Inspector offers for the current selection. */
export type ContextAction =
  | { id: 'inject'; label: string; volcanoId: string }
  | { id: 'startEruption' | 'stopEruption' | 'forceDike'; label: string; volcanoId: string }
  | { id: 'frame'; label: string }
  | { id: 'section'; label: string }
  | { id: 'water' | 'dig'; label: string }
  | { id: 'supply'; label: string; volcanoId: string };

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
 * Actions for a selection, in display order: a chamber offers magma (add a batch, its supply
 * settings), eruption control and a dike; a vent or fissure eruption control, framing and a section;
 * a dike a section along it; a ground point pouring water or digging there; everything framing.
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
        out.push({ id: 'inject', label: 'Add magma…', volcanoId: v }, eruptionToggle(v, volcanoes?.[v]), { id: 'forceDike', label: 'Push magma up (dike)', volcanoId: v }, { id: 'supply', label: 'Magma supply', volcanoId: v });
      }
      out.push({ id: 'section', label: 'Cross-section' });
      return out;
    case 'vent':
    case 'fissure':
      if (v) out.push(eruptionToggle(v, volcanoes?.[v]));
      out.push({ id: 'frame', label: 'Frame' }, { id: 'section', label: 'Cross-section' });
      return out;
    case 'dike':
      out.push({ id: 'section', label: 'Section along the dike' }, { id: 'frame', label: 'Frame' });
      return out;
    default:
      return [{ id: 'frame', label: 'Frame' }, { id: 'section', label: 'Cross-section' }];
  }
}

/** Injection fields for a volcano: its own (defaults from its supply magma), else the generic ones. */
export function injectFieldsFor(schema: SchemaMessage | null, volcanoId: string | null | undefined): ParamSpec[] | undefined {
  if (!schema) return undefined;
  return (volcanoId ? schema.commands[`injectMagma@${volcanoId}`] : undefined) ?? schema.commands.injectMagma;
}

/** The volcano's magma-supply settings (rate, variability, recharge temperature and composition). */
export function supplyParams(schema: SchemaMessage | null, volcanoId: string): ParamSpec[] {
  if (!schema) return [];
  return schema.params.filter((p) => p.volcanoId === volcanoId && /supply|recharge/i.test(p.id) && p.apply === 'hot');
}
