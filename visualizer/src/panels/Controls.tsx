import { useState } from 'react';
import { History, Radio } from 'lucide-react';
import { Tip } from '@/components/tip';
import { Button } from '@/components/ui/button';
import { Slider } from '@/components/ui/slider';
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
  const pct = (t: number) => `${((t - start) / (end - start)) * 100}%`;
  return (
    <div className="flex items-center gap-3 border-t bg-card px-3 py-2">
      <Button size="sm" variant={replay ? 'default' : 'secondary'} onClick={() => send({ type: 'replay', action: replay ? 'exit' : 'enter' })}>
        {replay ? <Radio /> : <History />}
        {replay ? 'Back to live' : 'Replay'}
      </Button>
      <div className="relative flex-1 py-2">
        <Slider
          aria-label="Replay position"
          min={start}
          max={end}
          step={1}
          value={[Math.min(end, Math.max(start, pos))]}
          disabled={!replay}
          onValueChange={(v) => setScrub(Array.isArray(v) ? (v as number[])[0] : (v as number))}
          onValueCommitted={(v) => {
            send({ type: 'seek', time: Array.isArray(v) ? (v as number[])[0] : (v as number) });
            setScrub(null);
          }}
        />
        <div className="pointer-events-none absolute inset-x-0 top-full -mt-1 h-2">
          {info.keyframes.map((k) => (
            <i key={k} className="absolute h-1.5 w-px bg-muted-foreground/50" style={{ left: pct(k) }} />
          ))}
          {eruptions.map((e, n) => (
            <i key={n} className="absolute h-2 w-0.5 bg-red-500" style={{ left: pct(e.time) }} />
          ))}
        </div>
      </div>
      <Tip content="Recorded range; drag the slider to move through it, red marks are eruptions" side="top">
        <span className="text-xs text-muted-foreground tabular-nums">
          {formatSimTime(pos)} of {formatSimTime(start)} – {formatSimTime(end)}
        </span>
      </Tip>
    </div>
  );
}
