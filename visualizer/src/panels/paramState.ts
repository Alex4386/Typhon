import type { ParamSpec, ParamValue } from '../protocol/messages';

/**
 * Editing rules of the parameters page. A pending edit is a value, or `null` for "back to the
 * default"; for an auto parameter (`spec.auto`) `null` means "computed by the engine", which is
 * also what a `null` spec value says.
 */

export function same(a: ParamValue | null | undefined, b: ParamValue | null | undefined): boolean {
  if (typeof a === 'number' && typeof b === 'number') return Math.abs(a - b) <= 1e-9 * Math.max(1, Math.abs(a), Math.abs(b));
  return a === b;
}

/** Whether the parameter is computed by the engine right now (shown edit, else the server's value). */
export function isComputed(spec: ParamSpec, pending: ParamValue | null | undefined): boolean {
  if (!spec.auto) return false;
  if (pending !== undefined) return pending === null;
  return spec.value === null || spec.value === undefined;
}

/**
 * The value the editor shows: the pending edit, else the server's value. Reset (`null`) shows the
 * default, or for an auto parameter the computed value. Undefined when there is nothing to show.
 */
export function shownValue(spec: ParamSpec, pending: ParamValue | null | undefined): ParamValue | undefined {
  if (isComputed(spec, pending)) return spec.computed ?? undefined;
  if (pending === null) return spec.default ?? undefined;
  return pending ?? spec.value ?? undefined;
}

/** Whether the server now reports a pending edit as applied (so it can be dropped). */
export function editApplied(spec: ParamSpec, pending: ParamValue | null): boolean {
  if (pending === null) return spec.auto ? spec.value === null || spec.value === undefined : same(spec.value, spec.default);
  return same(spec.value, pending);
}

/** Whether the reset button has nothing to do: at the default, or computed for an auto parameter. */
export function atRest(spec: ParamSpec, pending: ParamValue | null | undefined): boolean {
  if (spec.auto) return isComputed(spec, pending);
  return spec.default === undefined || same(shownValue(spec, pending), spec.default);
}

/** Value an override starts from: the computed value, else the default, else the range's middle. */
export function overrideSeed(spec: ParamSpec): number {
  if (typeof spec.computed === 'number' && Number.isFinite(spec.computed)) return spec.computed;
  if (typeof spec.default === 'number' && Number.isFinite(spec.default)) return spec.default;
  if (spec.min !== undefined && spec.max !== undefined) return (spec.min + spec.max) / 2;
  return spec.min ?? 0;
}
