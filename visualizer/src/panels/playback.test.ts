import { describe, expect, it } from 'vitest';
import type { PlaybackState } from '../protocol/messages';
import { formatSimTime } from '../util/format';
import { currentSpeedForEruptionsMessage, slowOnEruptionMessage, slowOnEventMessage, speedLabel, speedMessage, speedValue } from './playback';

const policy = (over: Partial<PlaybackState> = {}): PlaybackState => ({
  slowOnEruption: true,
  eruptionSpeed: 20,
  slowOnEvents: [],
  eventHoldSeconds: 3600,
  slowed: false,
  ...over,
});

describe('playback speed menu', () => {
  it('sends setSpeed for a speed or Max', () => {
    expect(speedMessage('3600')).toEqual({ type: 'setSpeed', speed: 3600 });
    expect(speedMessage('max')).toEqual({ type: 'setSpeed', speed: 'max' });
  });

  it('labels the speed, and the slow-down while it lasts', () => {
    expect(speedLabel({ mode: 'REALTIME', speed: 3600, playback: policy() })).toBe('×3 600');
    expect(speedLabel({ mode: 'UNBOUNDED', speed: 3600 })).toBe('Max');
    expect(speedLabel({ mode: 'REALTIME', speed: 20, playback: policy({ slowed: true, slowedBy: 'eruption', resumeSpeed: 1e6 }) })).toBe('×20 · eruption');
    expect(speedLabel({ mode: 'REALTIME', speed: 20, playback: policy({ slowed: true, slowedBy: 'pyroclasticFlow' }) })).toBe('×20 · pyroclastic flow');
    expect(speedLabel(null)).toBe('—');
  });

  it('highlights the offered speed nearest the current one', () => {
    expect(speedValue({ mode: 'REALTIME', speed: 3000 })).toBe('3600');
    expect(speedValue({ mode: 'REALTIME', speed: 20 })).toBe('20');
    expect(speedValue({ mode: 'UNBOUNDED', speed: 20 })).toBe('max');
  });

  it('wires the eruption switch and the "use current speed" button', () => {
    expect(slowOnEruptionMessage(false)).toEqual({ type: 'setPlaybackPolicy', slowOnEruption: false });
    expect(currentSpeedForEruptionsMessage({ mode: 'REALTIME', speed: 600 })).toEqual({ type: 'setPlaybackPolicy', eruptionSpeed: 600 });
    expect(currentSpeedForEruptionsMessage({ mode: 'PAUSED', speed: 60 })).toEqual({ type: 'setPlaybackPolicy', eruptionSpeed: 60 });
    expect(currentSpeedForEruptionsMessage({ mode: 'UNBOUNDED', speed: 60 })).toBeNull();
  });

  it('sends the whole event list when one checkbox changes', () => {
    expect(slowOnEventMessage(policy({ slowOnEvents: ['lahar'] }), 'dike', true)).toEqual({ type: 'setPlaybackPolicy', slowOnEvents: ['dike', 'lahar'] });
    expect(slowOnEventMessage(policy({ slowOnEvents: ['dike', 'lahar'] }), 'lahar', false)).toEqual({ type: 'setPlaybackPolicy', slowOnEvents: ['dike'] });
  });
});

describe('the clock', () => {
  it('shows days from day 1 and years after the first', () => {
    expect(formatSimTime(0)).toBe('Day 1, 00:00:00');
    expect(formatSimTime(11 * 86400 + 4 * 3600 + 31 * 60 + 7, false)).toBe('Day 12, 04:31');
    expect(formatSimTime(2 * 365 * 86400 + 44 * 86400 + 60, false)).toBe('Year 3, day 45, 00:01');
  });
});
