import { useEffect, useState } from 'react';
import { Activity, ChevronDown, CircleHelp, Gauge, Globe, Hammer, History, List, Menu, Pause, Play, Plus, ScrollText, Search, Settings2, SkipForward, SlidersHorizontal, SquareSplitVertical, type LucideIcon } from 'lucide-react';
import { CheckboxRow, SwitchRow } from '@/components/fields';
import { Tip } from '@/components/tip';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { Separator } from '@/components/ui/separator';
import { DropdownMenu, DropdownMenuContent, DropdownMenuGroup, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu';
import { cn } from '@/lib/utils';
import { attachSession, send } from '../net/connection';
import type { SessionInfo } from '../protocol/messages';
import { simNow, useStore, type DrawerTab } from '../store/store';
import { formatDuration, formatFactor, formatSimTime } from '../util/world';
import { currentSpeedForEruptionsMessage, SLOW_EVENTS, slowOnEruptionMessage, slowOnEventMessage, speedLabel, speedMessage, SPEEDS, speedValue } from './playback';

/** Side panel pages reachable from the header, in order. */
export const DRAWER_TABS: { tab: DrawerTab; label: string; title: string; icon: LucideIcon; key?: string }[] = [
  { tab: 'sims', label: 'Worlds', title: 'Start, open, switch, pause and close worlds', icon: Globe },
  { tab: 'build', label: 'Build', title: 'Place and edit magma chambers and the pathways between them; undo and redo', icon: Hammer, key: 'B' },
  { tab: 'entities', label: 'Entities', title: 'Everything on and under the volcano: vents, dikes, hot springs, flows, stations; click one to fly there', icon: List, key: 'E' },
  { tab: 'monitor', label: 'Monitor', title: 'Instruments: earthquakes, magma, ground motion, status history', icon: Activity },
  { tab: 'events', label: 'Events', title: 'What happened, newest first; show it on the map or replay from it', icon: ScrollText },
  { tab: 'section', label: 'Section', title: 'Cut the ground along a line to see layers, heat and water', icon: SquareSplitVertical },
  { tab: 'tune', label: 'Settings', title: 'Weather, magma supply and every other setting of this world', icon: SlidersHorizontal },
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

/** The world's clock: one time, played back at the speed next to it. */
export function Clock() {
  const clock = useStore((s) => s.clock);
  const [now, setNow] = useState(0);
  useEffect(() => {
    const id = window.setInterval(() => setNow(simNow()), 250);
    return () => window.clearInterval(id);
  }, []);
  const fine = clock !== null && clock.mode !== 'UNBOUNDED' && clock.speed < 60;
  const title = `Time since this world began (${formatDuration(now)}). The playback speed next to the play button only changes how fast you watch it, never the physics.`;
  return (
    <Tip content={title}>
      <div className="flex flex-col items-end leading-none whitespace-nowrap" data-testid="clock">
        <span className="font-mono text-sm tabular-nums phone:text-xs">
          {formatSimTime(now, fine)}
          {clock?.replay && (
            <Badge variant="destructive" className="ml-2 align-middle">
              REPLAY
            </Badge>
          )}
        </span>
      </div>
    </Tip>
  );
}

/** The speed menu: speeds, Max, and the policy that slows playback down while something happens. */
export function SpeedMenu() {
  const clock = useStore((s) => s.clock);
  const replay = clock?.replay ?? false;
  const policy = clock?.playback;
  const value = speedValue(clock);
  const useCurrent = currentSpeedForEruptionsMessage(clock);
  const slowed = policy?.slowed ?? false;
  return (
    <Popover>
      <Tip content={slowed ? `Slowed down for the ${policy?.slowedBy === 'eruption' ? 'eruption' : 'event'}; back to ${policy?.resumeMode === 'UNBOUNDED' ? 'Max' : formatFactor(policy?.resumeSpeed ?? 0)} when it is over` : 'Playback speed: seconds of the world per real second (never changes the physics)'}>
        <PopoverTrigger render={<Button size="sm" variant={slowed ? 'secondary' : 'outline'} disabled={replay} data-testid="speed" />}>
          <Gauge /> {speedLabel(clock)} <ChevronDown data-icon="inline-end" />
        </PopoverTrigger>
      </Tip>
      <PopoverContent className="w-80" align="start">
        <div className="flex flex-col gap-3 text-sm">
          <div>
            <div className="mb-1 text-xs font-medium text-muted-foreground">Playback speed</div>
            <div className="grid grid-cols-3 gap-1">
              {SPEEDS.map(([s, hint]) => (
                <Tip key={s} content={hint || undefined}>
                  <Button size="xs" variant={value === String(s) ? 'secondary' : 'ghost'} aria-pressed={value === String(s)} onClick={() => send(speedMessage(String(s)))}>
                    {formatFactor(s)}
                  </Button>
                </Tip>
              ))}
              <Tip content="As fast as the computer allows">
                <Button size="xs" variant={value === 'max' ? 'secondary' : 'ghost'} aria-pressed={value === 'max'} onClick={() => send(speedMessage('max'))}>
                  Max
                </Button>
              </Tip>
            </div>
          </div>
          <Separator />
          <SwitchRow
            id="slow-on-eruption"
            label="Slow down when an eruption starts"
            help="Switches to the eruption speed when an eruption starts and back to the speed you had when it ends"
            checked={policy?.slowOnEruption ?? true}
            onChange={(on) => send(slowOnEruptionMessage(on))}
          />
          <div className="flex items-center justify-between gap-2">
            <span className="text-muted-foreground">
              Eruption speed <span className="font-medium text-foreground tabular-nums">{formatFactor(policy?.eruptionSpeed ?? 20)}</span>
            </span>
            <Button size="xs" variant="outline" disabled={useCurrent === null} onClick={() => useCurrent && send(useCurrent)}>
              Use current speed for eruptions
            </Button>
          </div>
          <div>
            <div className="mb-1 text-xs font-medium text-muted-foreground">Also slow down for</div>
            {SLOW_EVENTS.map(([kind, label]) => (
              <CheckboxRow
                key={kind}
                id={`slow-on-${kind}`}
                label={label}
                checked={policy?.slowOnEvents.includes(kind) ?? false}
                onChange={(on) => send(slowOnEventMessage(policy, kind, on))}
              />
            ))}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
}

/** Play/pause, playback speed and stepping. */
export function Playback() {
  const clock = useStore((s) => s.clock);
  const mode = clock?.mode ?? 'PAUSED';
  const replay = clock?.replay ?? false;
  const speed = clock?.speed ?? 3600;
  const playing = mode !== 'PAUSED' && !replay;
  const canReplay = useStore((s) => (s.replayInfo?.keyframes.length ?? 0) > 0);
  return (
    <div className="flex items-center gap-1.5">
      <Tip content={playing ? 'Pause [K]' : 'Play [K]'}>
        <Button
          size="sm"
          variant={playing ? 'secondary' : 'default'}
          disabled={replay}
          aria-label={playing ? 'Pause' : 'Play'}
          onClick={() => send(playing ? { type: 'transport', mode: 'PAUSED' } : { type: 'transport', mode: 'REALTIME', speed })}
          className="w-20 phone:w-auto"
        >
          {playing ? <Pause /> : <Play />}
          <span className="phone:hidden">{playing ? 'Pause' : 'Play'}</span>
        </Button>
      </Tip>
      <SpeedMenu />
      <DropdownMenu>
        <Tip content="Step forward by a fixed time, or rewind to watch again">
          <DropdownMenuTrigger render={<Button size="sm" variant="outline" disabled={replay} aria-label="Step" />}>
            <SkipForward /> <span className="phone:hidden">Step</span> <ChevronDown data-icon="inline-end" className="phone:hidden" />
          </DropdownMenuTrigger>
        </Tip>
        <DropdownMenuContent className="w-auto min-w-52">
          {(
            [
              ['One step', { steps: 1 }, '.'],
              ['10 seconds', { seconds: 10 }, ''],
              ['10 minutes', { seconds: 600 }, ''],
              ['1 hour', { seconds: 3600 }, ''],
              ['1 day', { seconds: 86_400 }, ''],
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
      {clock && mode === 'REALTIME' && !replay && clock.rate > 0 && clock.rate < 0.7 * speed && (
        <Tip content="The computer cannot keep up with the requested speed">
          <span className="text-xs whitespace-nowrap text-muted-foreground max-xl:hidden">(running {formatFactor(clock.rate)})</span>
        </Tip>
      )}
    </div>
  );
}

/**
 * Phones: one menu for everything the header has no room for (panels, worlds, search, help); the
 * header keeps play/pause, the speed and the clock.
 */
export function MobileMenu() {
  const sessions = useStore((s) => s.sessions);
  const sessionId = useStore((s) => s.sessionId);
  const drawer = useStore((s) => s.drawer);
  const set = useStore((s) => s.set);
  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={<Button variant="ghost" size="icon" aria-label="Menu" />}>
        <Menu />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="max-h-[75dvh] w-64 overflow-y-auto">
        <DropdownMenuGroup>
          <DropdownMenuLabel>Panels</DropdownMenuLabel>
          {DRAWER_TABS.map((d) => (
            <DropdownMenuItem key={d.tab} onClick={() => set({ drawer: drawer === d.tab ? null : d.tab })} className={cn(drawer === d.tab && 'bg-accent/50')}>
              <d.icon /> {d.label}
            </DropdownMenuItem>
          ))}
        </DropdownMenuGroup>
        {sessions.length > 0 && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuGroup>
              <DropdownMenuLabel>Worlds</DropdownMenuLabel>
              {sessions.map((x) => (
                <DropdownMenuItem key={x.id} onClick={() => attachSession(x.id)} className={cn(x.id === sessionId && 'bg-accent/50')}>
                  <Globe /> <span className="flex-1 truncate">{sessionLabel(x)}</span>
                </DropdownMenuItem>
              ))}
            </DropdownMenuGroup>
          </>
        )}
        <DropdownMenuSeparator />
        <DropdownMenuItem onClick={() => set({ paletteOpen: true })}>
          <Search /> Find…
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => set({ guideOpen: true })}>
          <CircleHelp /> Quick guide
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
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
