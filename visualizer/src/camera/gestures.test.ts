import { describe, expect, it } from 'vitest';
import { gesture, isDoubleTap, notATap, tapSlop, twoFinger, twoFingerDelta, wrapDelta } from './gestures';

describe('touch gestures', () => {
  it('reads pinch, pan and twist from two fingers', () => {
    const a = twoFinger({ x: 100, y: 100 }, { x: 200, y: 100 });
    const spread = twoFinger({ x: 80, y: 100 }, { x: 220, y: 100 });
    expect(twoFingerDelta(a, spread).scale).toBeCloseTo(1.4);
    expect(twoFingerDelta(a, spread).twist).toBe(0);
    const moved = twoFinger({ x: 110, y: 130 }, { x: 210, y: 130 });
    const d = twoFingerDelta(a, moved);
    expect([d.panX, d.panY, d.scale]).toEqual([10, 30, 1]);
    const turned = twoFinger({ x: 150 - 50 * Math.cos(0.3), y: 100 - 50 * Math.sin(0.3) }, { x: 150 + 50 * Math.cos(0.3), y: 100 + 50 * Math.sin(0.3) });
    expect(twoFingerDelta(a, turned).twist).toBeCloseTo(0.3);
  });

  it('ignores tiny twists and wraps across ±π', () => {
    const a = twoFinger({ x: 0, y: 0 }, { x: 100, y: 0 });
    const b = twoFinger({ x: 0, y: 0 }, { x: 100, y: 1 });
    expect(twoFingerDelta(a, b).twist).toBe(0);
    expect(wrapDelta(3.1 - -3.1)).toBeCloseTo(6.2 - 2 * Math.PI);
  });

  it('tells taps from drags per pointer type and never selects during a pinch', () => {
    expect(tapSlop('touch')).toBeGreaterThan(tapSlop('mouse'));
    expect(notATap({ delta: 8, nativeEvent: { pointerType: 'touch' } })).toBe(false);
    expect(notATap({ delta: 8, nativeEvent: { pointerType: 'mouse' } })).toBe(true);
    gesture.multi = true;
    expect(notATap({ delta: 0, nativeEvent: { pointerType: 'touch' } })).toBe(true);
    gesture.multi = false;
    gesture.longPressed = true;
    expect(notATap({ delta: 0, nativeEvent: { pointerType: 'touch' } })).toBe(true);
    gesture.longPressed = false;
  });

  it('pairs two quick nearby taps into a double tap', () => {
    expect(isDoubleTap({ t: 1000, x: 50, y: 50 }, 1200, 60, 55)).toBe(true);
    expect(isDoubleTap({ t: 1000, x: 50, y: 50 }, 1500, 60, 55)).toBe(false);
    expect(isDoubleTap({ t: 1000, x: 50, y: 50 }, 1100, 150, 55)).toBe(false);
    expect(isDoubleTap(null, 1100, 50, 50)).toBe(false);
  });
});
