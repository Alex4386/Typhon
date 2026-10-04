import { useEffect, useRef } from 'react';
import type { SimEvent } from '../protocol/messages';
import { simNow, useStore } from '../store/store';
import { formatSimTime } from '../util/world';

const ROWS = 12;
const ROW_SECONDS = 600;

type Quake = Extract<SimEvent, { kind: 'seismic' }>;

/** Dominant frequency (Hz) used to draw each event type. */
const FREQ: Record<Quake['type'], number> = { VT: 6, LP: 1.2, TREMOR: 2, EXPLOSION: 3 };

/**
 * Drum-recorder style seismogram synthesised from seismic events: each event is a damped
 * oscillation whose amplitude grows with magnitude; rows are 10 simulated minutes.
 */
export function Helicorder({ volcanoId }: { volcanoId: string | null }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const events = useStore((s) => s.events);

  useEffect(() => {
    const draw = () => {
      const cv = ref.current;
      if (!cv) return;
      const dpr = window.devicePixelRatio || 1;
      const W = cv.clientWidth;
      const H = cv.clientHeight;
      if (cv.width !== Math.round(W * dpr)) cv.width = Math.round(W * dpr);
      if (cv.height !== Math.round(H * dpr)) cv.height = Math.round(H * dpr);
      const g = cv.getContext('2d')!;
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      g.fillStyle = '#f3efe4';
      g.fillRect(0, 0, W, H);
      const now = simNow();
      const end = Math.ceil(now / ROW_SECONDS) * ROW_SECONDS;
      const start = end - ROWS * ROW_SECONDS;
      const quakes = useStore
        .getState()
        .events.filter((e): e is Quake => e.kind === 'seismic' && (volcanoId === null || e.volcanoId === volcanoId) && e.time + e.durationSeconds + 60 > start && e.time <= now);
      const left = 54;
      const rowH = H / ROWS;
      const px = W - left - 6;
      const secPerPx = ROW_SECONDS / px;
      g.font = '10px ui-monospace, monospace';
      for (let r = 0; r < ROWS; r++) {
        const t0 = start + r * ROW_SECONDS;
        const yMid = rowH * (r + 0.5);
        g.fillStyle = '#6b6457';
        g.fillText(formatSimTime(t0).slice(-8, -3), 4, yMid + 3);
        g.strokeStyle = r % 2 ? '#1d3557' : '#7a1f2b';
        g.lineWidth = 0.8;
        g.beginPath();
        for (let x = 0; x <= px; x++) {
          const t = t0 + x * secPerPx;
          if (t > now) break;
          let amp = 0.04 * Math.sin(t * 7.3) * Math.sin(t * 1.7 + r);
          for (const q of quakes) {
            const dt = t - q.time;
            if (dt < 0 || dt > q.durationSeconds * 4 + 30) continue;
            const env = Math.pow(10, q.magnitude * 0.5) * 0.15 * Math.exp(-dt / Math.max(2, q.durationSeconds));
            amp += env * Math.sin(2 * Math.PI * FREQ[q.type] * dt * 0.05 + q.time);
          }
          // compress large amplitudes (like clipping on a real drum)
          const y = yMid - (Math.tanh(amp) * rowH * 0.48);
          if (x === 0) g.moveTo(left + x, y);
          else g.lineTo(left + x, y);
        }
        g.stroke();
      }
    };
    draw();
    const id = window.setInterval(draw, 250);
    return () => window.clearInterval(id);
  }, [volcanoId, events.length > 0]);

  return <canvas ref={ref} className="helicorder" />;
}
