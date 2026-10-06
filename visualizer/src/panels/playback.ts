/** Playback speed menu: speeds, labels and the messages its controls send (no DOM, for tests). */
import type { ClientMessage, ClockMessage, PlaybackPolicy, SlowEventKind } from '../protocol/messages';
import { formatFactor } from '../util/format';

/** Speeds offered in the menu (seconds per wall second), with what they mean. */
export const SPEEDS: readonly (readonly [number, string])[] = [
  [1, 'real time'],
  [20, ''],
  [60, 'a minute a second'],
  [600, '10 minutes a second'],
  [3600, 'an hour a second'],
  [86_400, 'a day a second'],
  [1e6, '≈ 12 days a second'],
  [1e7, '≈ 4 months a second'],
];

/** Events the menu can slow down for, with their labels. */
export const SLOW_EVENTS: readonly (readonly [SlowEventKind, string])[] = [
  ['unrest', 'Unrest (alert level rises)'],
  ['dike', 'A dike starts rising'],
  ['fissure', 'A fissure opens'],
  ['pyroclasticFlow', 'Pyroclastic flow'],
  ['lahar', 'Lahar (mudflow)'],
  ['avalanche', 'Debris avalanche'],
];

const SLOWED_BY: Record<string, string> = {
  eruption: 'eruption',
  unrest: 'unrest',
  dike: 'dike',
  fissure: 'fissure',
  pyroclasticFlow: 'pyroclastic flow',
  lahar: 'lahar',
  avalanche: 'avalanche',
};

/** The speed button's label: "×3 600", "Max", "×20 · eruption" while slowed down. */
export function speedLabel(clock: Pick<ClockMessage, 'mode' | 'speed' | 'playback'> | null | undefined): string {
  if (!clock) return '—';
  const base = clock.mode === 'UNBOUNDED' ? 'Max' : formatFactor(clock.speed);
  const by = clock.playback?.slowed ? clock.playback.slowedBy : undefined;
  return by ? `${base} · ${SLOWED_BY[by] ?? by}` : base;
}

/** Menu value of the current speed: `max`, or the offered speed nearest to it. */
export function speedValue(clock: Pick<ClockMessage, 'mode' | 'speed'> | null | undefined): string {
  if (!clock) return '';
  if (clock.mode === 'UNBOUNDED') return 'max';
  return String(SPEEDS.map(([s]) => s).reduce((a, b) => (Math.abs(Math.log(b / clock.speed)) < Math.abs(Math.log(a / clock.speed)) ? b : a)));
}

/** Choosing a speed in the menu (`max` or a number as a string). */
export function speedMessage(value: string): ClientMessage {
  return { type: 'setSpeed', speed: value === 'max' ? 'max' : Number(value) };
}

/** The switch "Slow down when an eruption starts". */
export function slowOnEruptionMessage(on: boolean): ClientMessage {
  return { type: 'setPlaybackPolicy', slowOnEruption: on };
}

/**
 * "Use current speed for eruptions": the eruption speed becomes the speed playing now; null when
 * there is no finite speed to take (Max, or no clock yet).
 */
export function currentSpeedForEruptionsMessage(clock: Pick<ClockMessage, 'mode' | 'speed'> | null | undefined): ClientMessage | null {
  if (!clock || clock.mode === 'UNBOUNDED' || !(clock.speed > 0)) return null;
  return { type: 'setPlaybackPolicy', eruptionSpeed: clock.speed };
}

/** Toggling one event checkbox: the full new list. */
export function slowOnEventMessage(policy: Pick<PlaybackPolicy, 'slowOnEvents'> | undefined, kind: SlowEventKind, on: boolean): ClientMessage {
  const now = new Set(policy?.slowOnEvents ?? []);
  if (on) now.add(kind);
  else now.delete(kind);
  return { type: 'setPlaybackPolicy', slowOnEvents: SLOW_EVENTS.map(([k]) => k).filter((k) => now.has(k)) };
}
