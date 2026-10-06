/**
 * Groups the cells of a flow field (pyroclastic density current depth) into a few clusters, each
 * summarised by an oriented box, so a cloud can be drawn per flow instead of a puff per cell.
 *
 * 1. Cells deeper than `minDepthM` are aggregated into blocks of `block` × `block` columns, which
 *    bridges gaps of one or two cells between parts of the same flow.
 * 2. Connected components of occupied blocks (8-neighbour) are the clusters.
 * 3. Each cluster gets a depth-weighted centroid, its principal axis (second moments), extents along
 *    and across it (±2σ) and its mean and maximum depth. A cluster much longer than wide is split
 *    along the axis into segments about as long as it is wide.
 * 4. At most `maxClusters` are kept: the smallest merge into their nearest larger neighbour.
 */

export interface FlowCluster {
  /** Depth-weighted centroid (map metres). */
  x: number;
  y: number;
  /** Unit principal axis in the map plane (east, north). */
  ax: number;
  ay: number;
  /** Half-extents along and across the axis (m). */
  halfLength: number;
  halfWidth: number;
  meanDepthM: number;
  maxDepthM: number;
  /** Occupied area (m²). */
  areaM2: number;
}

export interface FlowGrid {
  /** Map coordinates (m) of cell (i, j)'s centre: originX + (i + 0.5)·cellSize. */
  originX: number;
  originY: number;
  cellSize: number;
  /** Cells per row and column. */
  nx: number;
  ny: number;
  depth: (i: number, j: number) => number;
}

interface Acc {
  w: number;
  sx: number;
  sy: number;
  sxx: number;
  syy: number;
  sxy: number;
  dSum: number;
  dMax: number;
  n: number;
  cells: [number, number, number][];
}

export function flowClusters(g: FlowGrid, opts: { minDepthM?: number; block?: number; maxClusters?: number } = {}): FlowCluster[] {
  const minDepth = opts.minDepthM ?? 0.3;
  const block = Math.max(1, opts.block ?? 3);
  const maxClusters = opts.maxClusters ?? 12;
  const bx = Math.ceil(g.nx / block);
  const by = Math.ceil(g.ny / block);
  const occupied = new Uint8Array(bx * by);
  const cellsOf = new Map<number, [number, number, number][]>();
  for (let j = 0; j < g.ny; j++) {
    for (let i = 0; i < g.nx; i++) {
      const d = g.depth(i, j);
      if (!(d > minDepth)) continue;
      const b = Math.floor(j / block) * bx + Math.floor(i / block);
      occupied[b] = 1;
      let list = cellsOf.get(b);
      if (!list) cellsOf.set(b, (list = []));
      list.push([g.originX + (i + 0.5) * g.cellSize, g.originY + (j + 0.5) * g.cellSize, d]);
    }
  }
  // connected components over blocks
  const label = new Int32Array(bx * by).fill(-1);
  const accs: Acc[] = [];
  const stack: number[] = [];
  for (let start = 0; start < occupied.length; start++) {
    if (!occupied[start] || label[start] >= 0) continue;
    const acc: Acc = { w: 0, sx: 0, sy: 0, sxx: 0, syy: 0, sxy: 0, dSum: 0, dMax: 0, n: 0, cells: [] };
    const id = accs.length;
    accs.push(acc);
    label[start] = id;
    stack.push(start);
    while (stack.length) {
      const b = stack.pop()!;
      for (const c of cellsOf.get(b) ?? []) add(acc, c);
      const ix = b % bx;
      const iy = (b - ix) / bx;
      for (let dy = -1; dy <= 1; dy++) {
        for (let dx = -1; dx <= 1; dx++) {
          const nx = ix + dx;
          const ny = iy + dy;
          if (nx < 0 || ny < 0 || nx >= bx || ny >= by) continue;
          const nb = ny * bx + nx;
          if (occupied[nb] && label[nb] < 0) {
            label[nb] = id;
            stack.push(nb);
          }
        }
      }
    }
  }
  const area = g.cellSize * g.cellSize;
  let clusters: FlowCluster[] = [];
  for (const a of accs) clusters.push(...split(summarise(a, area), a, area));
  // keep the largest; the rest merge into their nearest kept cluster
  clusters.sort((p, q) => q.areaM2 - p.areaM2);
  if (clusters.length > maxClusters) {
    const kept = clusters.slice(0, maxClusters);
    for (const small of clusters.slice(maxClusters)) {
      let best = kept[0];
      let bestD = Infinity;
      for (const k of kept) {
        const d = (k.x - small.x) ** 2 + (k.y - small.y) ** 2;
        if (d < bestD) {
          bestD = d;
          best = k;
        }
      }
      merge(best, small);
    }
    clusters = kept;
  }
  return clusters;
}

function add(a: Acc, c: [number, number, number]): void {
  const [x, y, d] = c;
  a.w += d;
  a.sx += x * d;
  a.sy += y * d;
  a.sxx += x * x * d;
  a.syy += y * y * d;
  a.sxy += x * y * d;
  a.dSum += d;
  a.dMax = Math.max(a.dMax, d);
  a.n++;
  a.cells.push(c);
}

function summarise(a: Acc, cellArea: number): FlowCluster {
  const x = a.sx / a.w;
  const y = a.sy / a.w;
  const cxx = Math.max(0, a.sxx / a.w - x * x);
  const cyy = Math.max(0, a.syy / a.w - y * y);
  const cxy = a.sxy / a.w - x * y;
  // principal axis of the 2×2 covariance
  const angle = 0.5 * Math.atan2(2 * cxy, cxx - cyy);
  const ax = Math.cos(angle);
  const ay = Math.sin(angle);
  const tr = cxx + cyy;
  const det = cxx * cyy - cxy * cxy;
  const disc = Math.sqrt(Math.max(0, (tr * tr) / 4 - det));
  const l1 = tr / 2 + disc;
  const l2 = Math.max(0, tr / 2 - disc);
  const minHalf = Math.sqrt(cellArea) * 0.75;
  return {
    x,
    y,
    ax,
    ay,
    halfLength: Math.max(minHalf, 2 * Math.sqrt(l1)),
    halfWidth: Math.max(minHalf, 2 * Math.sqrt(l2)),
    meanDepthM: a.dSum / a.n,
    maxDepthM: a.dMax,
    areaM2: a.n * cellArea,
  };
}

/** Splits a cluster much longer than wide into segments about as long as it is wide. */
function split(c: FlowCluster, a: Acc, cellArea: number): FlowCluster[] {
  const ratio = c.halfLength / c.halfWidth;
  if (ratio < 3 || a.cells.length < 6) return [c];
  const parts = Math.min(6, Math.ceil(ratio / 1.5));
  const accs: Acc[] = Array.from({ length: parts }, () => ({ w: 0, sx: 0, sy: 0, sxx: 0, syy: 0, sxy: 0, dSum: 0, dMax: 0, n: 0, cells: [] }));
  for (const cell of a.cells) {
    const t = ((cell[0] - c.x) * c.ax + (cell[1] - c.y) * c.ay) / (2 * c.halfLength) + 0.5;
    add(accs[Math.min(parts - 1, Math.max(0, Math.floor(t * parts)))], cell);
  }
  return accs.filter((p) => p.n > 0).map((p) => summarise(p, cellArea));
}

function merge(into: FlowCluster, other: FlowCluster): void {
  const w1 = into.areaM2;
  const w2 = other.areaM2;
  const w = w1 + w2;
  into.x = (into.x * w1 + other.x * w2) / w;
  into.y = (into.y * w1 + other.y * w2) / w;
  into.meanDepthM = (into.meanDepthM * w1 + other.meanDepthM * w2) / w;
  into.maxDepthM = Math.max(into.maxDepthM, other.maxDepthM);
  into.areaM2 = w;
}

/**
 * Eases clusters towards a new set, so the cloud does not pop when flow cells change: each new
 * cluster is matched to the nearest previous one (within its length) and its shape blended by `k`
 * (0 = keep the old, 1 = take the new). New clusters appear at full shape; vanished ones are dropped
 * (the caller fades them).
 */
export function easeClusters(prev: FlowCluster[], next: FlowCluster[], k: number): FlowCluster[] {
  const used = new Set<number>();
  return next.map((n) => {
    let best = -1;
    let bestD = Infinity;
    prev.forEach((p, i) => {
      if (used.has(i)) return;
      const d = Math.hypot(p.x - n.x, p.y - n.y);
      if (d < bestD && d < n.halfLength + p.halfLength) {
        bestD = d;
        best = i;
      }
    });
    if (best < 0) return { ...n };
    used.add(best);
    const p = prev[best];
    // keep the axis sign continuous
    const sign = p.ax * n.ax + p.ay * n.ay < 0 ? -1 : 1;
    const ax = p.ax + (n.ax * sign - p.ax) * k;
    const ay = p.ay + (n.ay * sign - p.ay) * k;
    const al = Math.hypot(ax, ay) || 1;
    const lerp = (a: number, b: number) => a + (b - a) * k;
    return {
      x: lerp(p.x, n.x),
      y: lerp(p.y, n.y),
      ax: ax / al,
      ay: ay / al,
      halfLength: lerp(p.halfLength, n.halfLength),
      halfWidth: lerp(p.halfWidth, n.halfWidth),
      meanDepthM: lerp(p.meanDepthM, n.meanDepthM),
      maxDepthM: lerp(p.maxDepthM, n.maxDepthM),
      areaM2: lerp(p.areaM2, n.areaM2),
    };
  });
}
