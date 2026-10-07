import { useEffect, useRef, useState } from 'react';
import { ArrowDown, ArrowUp } from 'lucide-react';
import { COARSE_POINTER, useMediaQuery } from '../util/useMediaQuery';
import { useCamera } from './cameraStore';
import { stickAxis, virtualPad } from './virtualPad';

const R = 52; // stick radius (px)

/**
 * Fly and walk on touch screens: a move stick (bottom left) and a look stick (bottom right), plus
 * climb/sink buttons while flying. They feed `virtualPad`, which the camera rig reads like a gamepad.
 */
export function TouchSticks() {
  const mode = useCamera((c) => c.mode);
  const touch = useMediaQuery(COARSE_POINTER);
  const free = mode === 'fly' || mode === 'walk';
  useEffect(() => {
    if (free && touch) return;
    virtualPad.moveX = virtualPad.moveY = virtualPad.lookX = virtualPad.lookY = virtualPad.up = 0;
    virtualPad.active = false;
  }, [free, touch]);
  if (!free || !touch) return null;
  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-24 z-20 flex items-end justify-between px-6 pb-[env(safe-area-inset-bottom)]" data-touch-sticks>
      <Stick label="Move" onAxis={(x, y) => ((virtualPad.moveX = x), (virtualPad.moveY = y))} />
      {mode === 'fly' && (
        <div className="pointer-events-auto flex flex-col gap-3">
          <HoldButton label="Climb" onHold={(on) => (virtualPad.up = on ? 1 : 0)}>
            <ArrowUp />
          </HoldButton>
          <HoldButton label="Sink" onHold={(on) => (virtualPad.up = on ? -1 : 0)}>
            <ArrowDown />
          </HoldButton>
        </div>
      )}
      <Stick label="Look" onAxis={(x, y) => ((virtualPad.lookX = x), (virtualPad.lookY = y))} />
    </div>
  );
}

function Stick({ label, onAxis }: { label: string; onAxis: (x: number, y: number) => void }) {
  const [knob, setKnob] = useState<[number, number]>([0, 0]);
  const origin = useRef<[number, number] | null>(null);
  const id = useRef<number | null>(null);
  const move = (e: React.PointerEvent) => {
    if (id.current !== e.pointerId || !origin.current) return;
    let dx = e.clientX - origin.current[0];
    let dy = e.clientY - origin.current[1];
    const len = Math.hypot(dx, dy);
    if (len > R) {
      dx = (dx / len) * R;
      dy = (dy / len) * R;
    }
    setKnob([dx, dy]);
    const [x, y] = stickAxis(dx, dy, R);
    onAxis(x, y);
    virtualPad.active = true;
  };
  const end = (e: React.PointerEvent) => {
    if (id.current !== e.pointerId) return;
    id.current = null;
    origin.current = null;
    setKnob([0, 0]);
    onAxis(0, 0);
  };
  return (
    <div
      role="slider"
      aria-label={`${label} stick`}
      aria-valuenow={0}
      className="pointer-events-auto relative touch-none rounded-full border border-white/25 bg-black/30 backdrop-blur-sm"
      style={{ width: R * 2 + 16, height: R * 2 + 16 }}
      onPointerDown={(e) => {
        (e.target as HTMLElement).setPointerCapture?.(e.pointerId);
        id.current = e.pointerId;
        const rect = e.currentTarget.getBoundingClientRect();
        origin.current = [rect.left + rect.width / 2, rect.top + rect.height / 2];
        move(e);
      }}
      onPointerMove={move}
      onPointerUp={end}
      onPointerCancel={end}
    >
      <span
        className="absolute top-1/2 left-1/2 size-12 rounded-full border border-white/40 bg-white/25"
        style={{ transform: `translate(calc(-50% + ${knob[0]}px), calc(-50% + ${knob[1]}px))` }}
        aria-hidden
      />
      <span className="absolute inset-x-0 -top-5 text-center text-[11px] text-white/70">{label}</span>
    </div>
  );
}

function HoldButton({ label, onHold, children }: { label: string; onHold: (on: boolean) => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-label={label}
      className="flex size-12 touch-none items-center justify-center rounded-full border border-white/30 bg-black/35 text-white active:bg-white/30"
      onPointerDown={(e) => {
        (e.target as HTMLElement).setPointerCapture?.(e.pointerId);
        onHold(true);
      }}
      onPointerUp={() => onHold(false)}
      onPointerCancel={() => onHold(false)}
    >
      {children}
    </button>
  );
}
