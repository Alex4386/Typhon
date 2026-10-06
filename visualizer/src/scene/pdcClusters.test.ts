import { describe, expect, it } from 'vitest';
import { easeClusters, flowClusters, type FlowGrid } from './pdcClusters';

function grid(nx: number, ny: number, depth: (i: number, j: number) => number): FlowGrid {
  return { originX: 0, originY: 0, cellSize: 10, nx, ny, depth };
}

describe('flow clusters', () => {
  it('one blob is one cluster, with gaps of a cell bridged', () => {
    // a 10×4 strip with a one-cell gap in the middle
    const g = grid(40, 40, (i, j) => (i >= 10 && i < 20 && i !== 15 && j >= 10 && j < 14 ? 2 : 0));
    const c = flowClusters(g);
    expect(c.length).toBe(1);
    expect(c[0].x).toBeCloseTo(150, -1);
    expect(Math.abs(c[0].ax)).toBeGreaterThan(0.9); // elongated east–west
    expect(c[0].halfLength).toBeGreaterThan(c[0].halfWidth);
  });

  it('separate flows are separate clusters; tiny films are ignored', () => {
    const g = grid(60, 60, (i, j) => ((i < 6 && j < 6) || (i > 40 && j > 40) ? 3 : 0.1));
    expect(flowClusters(g).length).toBe(2);
  });

  it('a long thin flow is split into segments and the count is capped', () => {
    const long = flowClusters(grid(200, 10, (_i, j) => (j >= 4 && j < 6 ? 1 : 0)));
    expect(long.length).toBeGreaterThan(1);
    const many = flowClusters(
      grid(100, 100, (i, j) => (i % 10 === 0 && j % 10 === 0 ? 2 : 0)),
      { block: 1, maxClusters: 5 },
    );
    expect(many.length).toBe(5);
  });

  it('easing moves part of the way and keeps the axis direction continuous', () => {
    const a = flowClusters(grid(40, 40, (i, j) => (i >= 10 && i < 20 && j >= 10 && j < 14 ? 2 : 0)));
    const b = flowClusters(grid(40, 40, (i, j) => (i >= 14 && i < 24 && j >= 10 && j < 14 ? 2 : 0)));
    const e = easeClusters(a, b, 0.5);
    expect(e[0].x).toBeGreaterThan(a[0].x);
    expect(e[0].x).toBeLessThan(b[0].x);
    expect(e[0].ax * a[0].ax + e[0].ay * a[0].ay).toBeGreaterThan(0);
  });
});
