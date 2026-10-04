import { useEffect, useRef } from 'react';
import uPlot from 'uplot';
import 'uplot/dist/uPlot.min.css';
import { formatSimTime } from '../util/world';

export interface SeriesSpec {
  label: string;
  color: string;
  scale?: string;
  width?: number;
}

interface Props {
  title?: string;
  series: SeriesSpec[];
  data: uPlot.AlignedData;
  height?: number;
  /** x is simulation time in seconds (formatted as hh:mm:ss). */
  timeAxis?: boolean;
  logY?: boolean;
  xLabel?: string;
  /** Extra y scales (e.g. a second axis on the right). */
  rightScale?: string;
}

/** Thin React wrapper around uPlot that resizes with its container. */
export function UChart({ title, series, data, height = 140, timeAxis = true, logY = false, xLabel, rightScale }: Props) {
  const host = useRef<HTMLDivElement>(null);
  const plot = useRef<uPlot | null>(null);
  const key = `${series.map((s) => s.label + s.color + (s.scale ?? '')).join('|')}|${logY}|${timeAxis}|${rightScale ?? ''}`;

  useEffect(() => {
    const el = host.current;
    if (!el) return;
    const axisStyle = { stroke: '#8b949e', grid: { stroke: '#21262d', width: 1 }, ticks: { stroke: '#30363d', width: 1 } };
    const opts: uPlot.Options = {
      title,
      width: el.clientWidth || 300,
      height,
      legend: { show: true, live: false },
      cursor: { drag: { x: false, y: false } },
      scales: {
        x: { time: false },
        y: logY ? { distr: 3 } : {},
        ...(rightScale ? { [rightScale]: {} } : {}),
      },
      axes: [
        { ...axisStyle, label: xLabel, values: timeAxis ? (_u, vals) => vals.map((v) => formatSimTime(v)) : undefined, size: 28 },
        { ...axisStyle, size: 48 },
        ...(rightScale ? [{ ...axisStyle, scale: rightScale, side: 1 as const, grid: { show: false }, size: 48 }] : []),
      ],
      series: [{}, ...series.map((s) => ({ label: s.label, stroke: s.color, width: s.width ?? 1.5, scale: s.scale ?? 'y', points: { show: !timeAxis } }))],
    };
    plot.current = new uPlot(opts, data, el);
    const ro = new ResizeObserver(() => plot.current?.setSize({ width: el.clientWidth || 300, height }));
    ro.observe(el);
    return () => {
      ro.disconnect();
      plot.current?.destroy();
      plot.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, height]);

  useEffect(() => {
    plot.current?.setData(data);
  }, [data]);

  return <div ref={host} className="uchart" />;
}
