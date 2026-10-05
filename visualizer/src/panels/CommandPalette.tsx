import { useMemo } from 'react';
import { ArrowUpFromDot, Crosshair, Pause, Play, Square, Triangle } from 'lucide-react';
import { Command, CommandDialog, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList, CommandShortcut } from '@/components/ui/command';
import { useCamera } from '../camera/cameraStore';
import { command, send, showEntity } from '../net/connection';
import { KIND_LABEL, entityColor, formatPlace } from '../store/entities';
import { useStore } from '../store/store';
import { FEATURE_COLORS } from '../util/color';
import { DRAWER_TABS } from './Header';

/** Entities offered in the palette at most (newest first). */
const MAX_ENTITIES = 300;

/** Ctrl+K: open any panel, run the common actions, or find and fly to any entity by name. */
export function CommandPalette() {
  const open = useStore((s) => s.paletteOpen);
  const set = useStore((s) => s.set);
  const entities = useStore((s) => s.entities);
  const world = useStore((s) => s.world);
  const clock = useStore((s) => s.clock);
  const volcanoId = useStore((s) => s.selectedVolcano ?? s.world?.volcanoes[0]?.id ?? null);
  const erupting = useStore((s) => (volcanoId ? (s.state?.volcanoes[volcanoId]?.chamber.eruptionRate ?? 0) > 0 : false));
  const list = useMemo(
    () =>
      Object.values(entities)
        .filter((e) => !e.hidden && e.removedAt === undefined)
        .sort((a, b) => b.createdAt - a.createdAt)
        .slice(0, MAX_ENTITIES),
    [entities],
  );
  const close = () => set({ paletteOpen: false });
  const run = (f: () => void) => () => {
    close();
    f();
  };
  const playing = clock && clock.mode !== 'PAUSED' && !clock.replay;
  return (
    <CommandDialog open={open} onOpenChange={(o) => set({ paletteOpen: o })} title="Find anything" description="Panels, actions and everything on the map">
      <Command>
        <CommandInput placeholder="Type a panel, an action or a name (e.g. hot spring, dike, fissure)…" />
        <CommandList>
          <CommandEmpty>Nothing found.</CommandEmpty>
          <CommandGroup heading="Panels">
            {DRAWER_TABS.map((d) => (
              <CommandItem key={d.tab} value={`panel ${d.label} ${d.title}`} onSelect={run(() => set({ drawer: d.tab }))}>
                <d.icon /> {d.label}
                <span className="truncate text-xs text-muted-foreground">{d.title}</span>
              </CommandItem>
            ))}
          </CommandGroup>
          {world && volcanoId && (
            <CommandGroup heading="Actions">
              {clock && !clock.replay && (
                <CommandItem value="play pause" onSelect={run(() => send(playing ? { type: 'transport', mode: 'PAUSED' } : { type: 'transport', mode: 'REALTIME', speed: clock.speed }))}>
                  {playing ? <Pause /> : <Play />} {playing ? 'Pause' : 'Play'}
                  <CommandShortcut>K</CommandShortcut>
                </CommandItem>
              )}
              {erupting ? (
                <CommandItem value="stop eruption" onSelect={run(() => command({ kind: 'stopEruption', volcanoId }))}>
                  <Square /> Stop the eruption
                </CommandItem>
              ) : (
                <CommandItem value="start eruption" onSelect={run(() => command({ kind: 'startEruption', volcanoId }))}>
                  <Triangle /> Start an eruption
                </CommandItem>
              )}
              <CommandItem value="push magma up dike force" onSelect={run(() => command({ kind: 'forceDike', volcanoId }))}>
                <ArrowUpFromDot /> Push magma up (dike)
              </CommandItem>
              <CommandItem value="frame look at volcano summit" onSelect={run(() => useCamera.getState().requestCamera({ kind: 'frame', what: 'volcano' }))}>
                <Crosshair /> Look at the volcano
              </CommandItem>
            </CommandGroup>
          )}
          {list.length > 0 && (
            <CommandGroup heading="On the map">
              {list.map((e) => (
                <CommandItem key={e.id} value={`${e.label} ${KIND_LABEL[e.kind] ?? e.kind} ${String(e.props.feature ?? '')} ${e.id}`} onSelect={run(() => showEntity(e.id))}>
                  <span className="size-2.5 shrink-0 rounded-full" style={{ background: entityColor(e, FEATURE_COLORS) }} aria-hidden />
                  {e.label}
                  <span className="truncate text-xs text-muted-foreground">
                    {KIND_LABEL[e.kind] ?? e.kind} · {formatPlace(e.at)}
                  </span>
                </CommandItem>
              ))}
            </CommandGroup>
          )}
        </CommandList>
      </Command>
    </CommandDialog>
  );
}
