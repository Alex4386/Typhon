import { useEffect, useState } from 'react';
import { Activity, ChevronDown, Globe, Hammer, History, List, Pause, Play, Plus, ScrollText, Settings2, SkipForward, SlidersHorizontal, SquareSplitVertical, type LucideIcon } from 'lucide-react';
import { SimpleSelect } from '@/components/fields';
import { Tip } from '@/components/tip';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { cn } from '@/lib/utils';
import { attachSession, send } from '../net/connection';
import type { SessionInfo } from '../protocol/messages';
import { simNow, useStore, type DrawerTab } from '../store/store';
import { formatDuration, formatFactor, formatSimTime } from '../util/world';

/** Playback speeds offered in the menu (simulated seconds per real second). */
const SPEEDS = [0.5, 1, 5, 20, 100, 1000];

/** Side panel pages reachable from the header, in order. */
export const DRAWER_TABS: { tab: DrawerTab; label: string; title: string; icon: LucideIcon; key?: string }[] = [
  { tab: 'sims', label: 'Worlds', title: 'Start, open, switch, pause and close simulated worlds', icon: Globe },
  { tab: 'build', label: 'Build', title: 'Place and edit magma chambers and the pathways between them; undo and redo', icon: Hammer, key: 'B' },
  { tab: 'entities', label: 'Entities', title: 'Everything on and under the volcano: vents, dikes, hot springs, flows, stations; click one to fly there', icon: List, key: 'E' },
  { tab: 'monitor', label: 'Monitor', title: 'Instruments: earthquakes, magma, ground motion, status history', icon: Activity },
  { tab: 'events', label: 'Events', title: 'What happened, newest first; show it on the map or replay from it', icon: ScrollText },
  { tab: 'section', label: 'Section', title: 'Cut the ground along a line to see layers, heat and water', icon: SquareSplitVertical },
  { tab: 'tune', label: 'Settings', title: 'Weather, magma supply, time scale and every other setting of this world', icon: SlidersHorizontal },
  { tab: 'view', label: 'View', title: 'What to show on the map, graphics quality, camera tools', icon: Settings2 },
];

/** What to call a session: its world folder (what the user named it), else its preset title. */
export function sessionLabel(s: SessionInfo): string {
  return s.world ?? s.name;
}

/** The current world's name with a menu of the other loaded worlds. */
export function WorldSwitcher() {
  const sessions = useStore((s) => s.sessions);
  const sessionId = useStore((s) => s.sessionId);
  const world = useStore((s) => s.world);
  const set = useStore((s) => s.set);
  const current = sessions.find((x) => x.id === sessionId);
  return (
    <DropdownMenu>
      <Tip content="Switch between the worlds this server is running">
        <DropdownMenuTrigger render={<Button variant="ghost" className="max-w-48 font-semibold" />}>
          <span className="truncate">{current ? sessionLabel(current) : world?.name ?? 'No world open'}</span>
          <ChevronDown data-icon="inline-end" className="text-muted-foreground" />
        </DropdownMenuTrigger>
      </Tip>
      <DropdownMenuContent className="w-auto min-w-72">
        <DropdownMenuGroup>
          <DropdownMenuLabel>Running worlds</DropdownMenuLabel>
          {sessions.map((x) => {
            const erupting = x.volcanoes?.some((v) => v.erupting);
            return (
              <DropdownMenuItem key={x.id} onClick={() => attachSession(x.id)} className={cn(x.id === sessionId && 'bg-accent/50')}>
                <span className={cn('size-2 rounded-full', erupting ? 'bg-red-500' : x.mode === 'PAUSED' ? 'bg-muted-foreground' : 'bg-emerald-500')} aria-hidden />
                <span className="flex-1 truncate" title={x.name}>
                  {sessionLabel(x)}
                </span>
                <span className="text-xs text-muted-foreground">
                  {x.mode === 'PAUSED' ? 'paused' : erupting ? 'erupting' : 'running'} · {formatSimTime(x.time)}
                </span>
              </DropdownMenuItem>
            );
          })}
          {sessions.length === 0 && <div className="px-2 py-1.5 text-sm text-muted-foreground">No worlds are running.</div>}
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuItem onClick={() => set({ drawer: 'sims' })}>
          <Plus /> Start or open a world…
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** Simulation clock with the volcano time it stands for. */
export function Clock() {
  const clock = useStore((s) => s.clock);
  const [now, setNow] = useState(0);
  useEffect(() => {
    const id = window.setInterval(() => setNow(simNow()), 250);
    return () => window.clearInterval(id);
  }, []);
  const c = clock?.compression;
  const physical = clock?.physicalTime;
  const title =
    'Simulated time: how long the simulation has run.\n' +
    (c ? `Volcano time runs ${formatFactor(c)} faster than simulated time right now (time compression), so slow volcanic processes fit into a session.` : '') +
    '\nPlayback speed (next to the play button) only changes how fast you watch it.';
  return (
    <Tip content={title}>
      <div className="flex flex-col items-end leading-none whitespace-nowrap" data-testid="clock">
        <span className="font-mono text-sm tabular-nums">
          {formatSimTime(now)}
          {clock?.replay && (
            <Badge variant="destructive" className="ml-2 align-middle">
              REPLAY
            </Badge>
          )}
        </span>
        {c !== undefined && (
          <span className="mt-0.5 text-[11px] text-muted-foreground max-lg:hidden">
            volcano time {formatFactor(c)}
            {physical !== undefined ? ` · ≈ ${formatDuration(physical)}` : ''}
          </span>
        )}
      </div>
    </Tip>
  );
}

/** Play/pause, playback speed and stepping. */
export function Playback() {
  const clock = useStore((s) => s.clock);
  const mode = clock?.mode ?? 'PAUSED';
  const replay = clock?.replay ?? false;
  const speed = clock?.speed ?? 20;
  const playing = mode !== 'PAUSED' && !replay;
  const canReplay = useStore((s) => (s.replayInfo?.keyframes.length ?? 0) > 0);
  const speedValue = mode === 'UNBOUNDED' ? 'max' : String(SPEEDS.reduce((a, b) => (Math.abs(b - speed) < Math.abs(a - speed) ? b : a)));
  return (
    <div className="flex items-center gap-1.5">
      <Tip content={playing ? 'Pause [K]' : 'Play [K]'}>
        <Button
          size="sm"
          variant={playing ? 'secondary' : 'default'}
          disabled={replay}
          aria-label={playing ? 'Pause' : 'Play'}
          onClick={() => send(playing ? { type: 'transport', mode: 'PAUSED' } : { type: 'transport', mode: 'REALTIME', speed })}
          className="w-20"
        >
          {playing ? <Pause /> : <Play />}
          {playing ? 'Pause' : 'Play'}
        </Button>
      </Tip>
      <Tip content="Playback speed: simulated seconds per real second (does not change the physics)">
        <span>
          <SimpleSelect
            label="Playback speed"
            disabled={replay}
            value={speedValue}
            onChange={(v) => {
              if (v === 'max') send({ type: 'transport', mode: 'UNBOUNDED', speed });
              else send({ type: 'transport', mode: mode === 'PAUSED' ? 'PAUSED' : 'REALTIME', speed: Number(v) });
            }}
            options={[...SPEEDS.map((s) => [String(s), `${s}× speed`] as const), ['max', 'max speed'] as const]}
          />
        </span>
      </Tip>
      <DropdownMenu>
        <Tip content="Step forward by a fixed time, or rewind to watch again">
          <DropdownMenuTrigger render={<Button size="sm" variant="outline" disabled={replay} />}>
            <SkipForward /> Step <ChevronDown data-icon="inline-end" />
          </DropdownMenuTrigger>
        </Tip>
        <DropdownMenuContent className="w-auto min-w-52">
          {(
            [
              ['One step', { steps: 1 }, '.'],
              ['10 seconds', { seconds: 10 }, ''],
              ['10 minutes', { seconds: 600 }, ''],
              ['1 hour', { seconds: 3600 }, ''],
            ] as const
          ).map(([label, arg, key]) => (
            <DropdownMenuItem key={label} onClick={() => send({ type: 'step', ...arg })}>
              <Plus /> {label}
              {key && <span className="ml-auto text-xs text-muted-foreground">{key}</span>}
            </DropdownMenuItem>
          ))}
          <DropdownMenuSeparator />
          <DropdownMenuItem disabled={!canReplay} onClick={() => send({ type: 'replay', action: 'enter' })}>
            <History /> Watch again (replay)
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      {clock && mode !== 'PAUSED' && !replay && clock.rate > 0 && Math.abs(clock.rate - speed) / speed > 0.3 && (
        <Tip content="The computer cannot keep up with the requested speed">
          <span className="text-xs whitespace-nowrap text-muted-foreground max-xl:hidden">(running {clock.rate >= 10 ? clock.rate.toFixed(0) : clock.rate.toFixed(1)}×)</span>
        </Tip>
      )}
    </div>
  );
}

/** Buttons that open side panel pages (labels hide on narrow windows). */
export function DrawerButtons() {
  const drawer = useStore((s) => s.drawer);
  const openDrawer = useStore((s) => s.openDrawer);
  const newCount = useStore((s) => Object.values(s.entities).filter((e) => e.fresh && !e.removedAt && performance.now() - e.seenAt < 60_000).length);
  return (
    <nav className="flex items-center gap-0.5" aria-label="Panels">
      {DRAWER_TABS.map((d) => (
        <Tip key={d.tab} content={`${d.title}${d.key ? ` [${d.key}]` : ''}`}>
          <Button variant={drawer === d.tab ? 'secondary' : 'ghost'} size="sm" aria-pressed={drawer === d.tab} onClick={() => openDrawer(d.tab)} className="relative">
            <d.icon />
            <span className="hidden 2xl:inline">{d.label}</span>
            {d.tab === 'entities' && newCount > 0 && <span className="absolute top-0.5 right-0.5 size-2 rounded-full bg-primary" aria-label={`${newCount} new`} />}
          </Button>
        </Tip>
      ))}
    </nav>
  );
}
