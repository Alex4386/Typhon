import { useEffect, useState } from 'react';
import { useStore } from '../store/store';
import { perfStats } from './perf';

const fmt = (n: number) => (n >= 1e6 ? `${(n / 1e6).toFixed(2)} M` : n >= 1e4 ? `${(n / 1e3).toFixed(0)} k` : String(Math.round(n)));

/**
 * Frame statistics over the 3D view (View → Graphics → "Frame statistics"): rendered frames per
 * second (0 while the scene is idle: it is only drawn when something changes), frame time, JS time
 * per frame, terrain rebuild time and backlog, draw calls, triangles, entity markers drawn and the
 * current pixel ratio.
 */
export function PerfHud() {
  const show = useStore((s) => s.showPerf);
  const [, tick] = useState(0);
  useEffect(() => {
    if (!show) return;
    const id = window.setInterval(() => tick((k) => k + 1), 500);
    return () => window.clearInterval(id);
  }, [show]);
  if (!show) return null;
  const p = perfStats;
  const rows: [string, string][] = [
    ['fps', p.fps.toFixed(p.fps < 10 ? 1 : 0)],
    ['frame', `${p.frameMs.toFixed(1)} ms`],
    ['JS', `${p.cpuMs.toFixed(1)} ms`],
    ['rebuild', `${p.rebuildMs.toFixed(1)} ms`],
    ['queued', fmt(p.rebuildQueue)],
    ['draws', fmt(p.calls)],
    ['tris', fmt(p.triangles)],
    ['markers', fmt(p.entities)],
    ['dpr', p.dpr.toFixed(2)],
  ];
  return (
    <div
      className="pointer-events-none grid shrink-0 grid-cols-[auto_auto] gap-x-2 rounded-md border bg-background/80 px-2.5 py-1.5 font-mono text-[11px] leading-4 tabular-nums shadow-sm backdrop-blur-none"
      role="status"
      aria-label="Frame statistics"
    >
      {rows.map(([k, v]) => (
        <span key={k} className="contents">
          <span className="text-muted-foreground">{k}</span>
          <span className="text-right">{v}</span>
        </span>
      ))}
    </div>
  );
}
