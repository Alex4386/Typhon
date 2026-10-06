import { describe, expect, it } from 'vitest';
import { EMPTY_HISTORY, elevationOnVertical, positionReadout, pushStep, radiusFromVolume, redoStep, restoreChange, undoStep, volumeFromRadius, type BuildStep } from './builder';

describe('chamber geometry', () => {
  it('converts volume and radius', () => {
    expect(radiusFromVolume(volumeFromRadius(620))).toBeCloseTo(620, 9);
    expect(radiusFromVolume(1e9)).toBeCloseTo(620.35, 1); // 1 km³
  });

  it('reads the depth a vertical drag points at (screen ray ↔ world elevation)', () => {
    // camera 2 km south of the chamber line, 1 km up (scene y = 1000·vExag), looking down at 45°
    const vExag = 1.5;
    const origin = { x: 0, y: 1000 * vExag, z: 2000 };
    const s = Math.SQRT1_2;
    const z = elevationOnVertical(origin, { x: 0, y: -s, z: -s }, { x: 0, z: 0 }, vExag);
    // the ray reaches the line after 2000 m horizontally, dropping 2000 scene metres
    expect(z).toBeCloseTo((1000 * vExag - 2000) / vExag, 6);
    // the line seen straight on: the elevation the pointer marks follows the ray
    expect(elevationOnVertical({ x: 0, y: 0, z: 100 }, { x: 0, y: 0, z: -1 }, { x: 0, z: 0 }, 1)).toBeCloseTo(0, 9);
  });

  it('reads out depth below the sea and distance from the volcano', () => {
    const r = positionReadout([300, 400], 3000, -130, 0, [0, 0]);
    expect(r.centreZ).toBe(-3130);
    expect(r.belowSea).toBe(3130);
    expect(r.distance).toBe(500);
    expect(positionReadout([0, 0], 1000, 500, undefined).belowSea).toBeUndefined();
  });
});

describe('builder undo', () => {
  const step = (label: string): BuildStep => ({ label, volcanoId: 'v', before: { magma: { a: label } }, after: { magma: { b: label } } });

  it('undoes and redoes in order; a new edit clears the redo stack', () => {
    let h = pushStep(pushStep(EMPTY_HISTORY, step('add')), step('move'));
    const u1 = undoStep(h)!;
    expect(u1.step.label).toBe('move');
    h = u1.history;
    const u2 = undoStep(h)!;
    expect(u2.step.label).toBe('add');
    h = u2.history;
    expect(undoStep(h)).toBeNull();
    const r1 = redoStep(h)!;
    expect(r1.step.label).toBe('add');
    h = pushStep(r1.history, step('connect'));
    expect(h.redo).toEqual([]);
    expect(h.undo.map((s) => s.label)).toEqual(['add', 'connect']);
  });

  it('keeps a bounded history', () => {
    let h = EMPTY_HISTORY;
    for (let i = 0; i < 60; i++) h = pushStep(h, step(String(i)), 50);
    expect(h.undo).toHaveLength(50);
    expect(h.undo[0].label).toBe('10');
  });

  it('restores a volcano: removes, re-adds whole, or sets its magma back with cleared lists', () => {
    expect(restoreChange(null, true)).toBeNull();
    const tree = { id: 'v', magma: { chamber: { volume: 1 } } };
    expect(restoreChange(tree, false)).toBe(tree);
    expect(restoreChange(tree, true)).toEqual({ magma: { chamber: { volume: 1 }, chambers: [], connections: [] } });
  });
});
