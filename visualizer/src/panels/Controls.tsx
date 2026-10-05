import { useState } from 'react';
import { send } from '../net/connection';
import { useStore } from '../store/store';
import { formatSimTime } from '../util/world';

/** Replay timeline (shown while replaying): scrub, keyframes, eruptions, back to live. */
export function ReplayBar() {
  const info = useStore((s) => s.replayInfo);
  const clock = useStore((s) => s.clock);
  const events = useStore((s) => s.events);
  const [scrub, setScrub] = useState<number | null>(null);
  const replay = clock?.replay ?? false;
  if (!info) return null;
  const start = info.start;
  const end = Math.max(info.end, start + 1);
  const pos = scrub ?? clock?.time ?? end;
  const eruptions = events.filter((e) => e.kind === 'eruptionStarted');
  return (
    <div className="replay">
      <button className={replay ? 'on' : ''} onClick={() => send({ type: 'replay', action: replay ? 'exit' : 'enter' })}>
        {replay ? '● Back to live' : '⟲ Replay'}
      </button>
      <div className="timeline">
        <input
          type="range"
          aria-label="Replay position"
          min={start}
          max={end}
          step={1}
          value={Math.min(end, Math.max(start, pos))}
          disabled={!replay}
          onChange={(e) => setScrub(Number(e.target.value))}
          onPointerUp={() => {
            if (scrub !== null) send({ type: 'seek', time: scrub });
            setScrub(null);
          }}
        />
        <div className="ticks">
          {info.keyframes.map((k) => (
            <i key={k} className="kf" style={{ left: `${((k - start) / (end - start)) * 100}%` }} />
          ))}
          {eruptions.map((e, n) => (
            <i key={n} className="er" title={`eruption at ${formatSimTime(e.time)}`} style={{ left: `${((e.time - start) / (end - start)) * 100}%` }} />
          ))}
        </div>
      </div>
      <span className="muted small" title="Recorded range; drag the slider to move through it, red marks are eruptions">
        {formatSimTime(pos)} of {formatSimTime(start)} – {formatSimTime(end)}
      </span>
    </div>
  );
}
