import { currentTier, powerSave } from '../util/device';
import { useThree } from '@react-three/fiber';
import { useEffect } from 'react';
import { useCamera } from '../camera/cameraStore';
import type { WorldInfo } from '../protocol/messages';
import { QUALITY, simNow, useStore } from '../store/store';
import { entitiesAnimating, nextDpr } from './frameMath';
import { perfStats } from './perf';

/** What keeps the scene animating without new data: eruptions, columns, bombs, new/fading markers. */
export function sceneAnimating(world: WorldInfo, now: number, wallNow: number): boolean {
  const st = useStore.getState();
  if (st.state) {
    for (const v of world.volcanoes) {
      const vs = st.state.volcanoes[v.id];
      if (!vs) continue;
      if (vs.chamber.eruptionRate > 0 || vs.plume) return true;
    }
  }
  // bombs fly for up to a minute or so after launch
  const ev = st.events;
  for (let k = ev.length - 1, seen = 0; k >= 0 && seen < 400; k--, seen++) {
    const e = ev[k];
    if (now - e.time > 120) break;
    if (e.kind === 'bombLaunched' || e.kind === 'lightning' || e.kind === 'massFlowFront') return true;
  }
  return entitiesAnimating(Object.values(st.entities), wallNow);
}

/**
 * On-demand rendering (`frameloop="demand"`): a frame is drawn when data or the camera change, and at
 * the quality preset's ambient rate while something animates (eruption columns, glowing lava,
 * pulsing markers). An idle scene draws nothing, which frees the GPU and the main thread.
 */
export function FrameScheduler({ world }: { world: WorldInfo }) {
  const invalidate = useThree((s) => s.invalidate);
  const setDpr = useThree((s) => s.setDpr);
  const getDpr = useThree((s) => s.viewport.dpr);

  useEffect(() => {
    const unsubStore = useStore.subscribe(() => invalidate());
    const unsubCam = useCamera.subscribe(() => invalidate());
    const onVisible = () => invalidate();
    document.addEventListener('visibilitychange', onVisible);

    let timer = 0;
    let animating = false;
    let lastCheck = 0;
    // adaptive resolution bookkeeping, per second
    let wanted = 0;
    let frames0 = perfStats.frames;
    let windowStart = performance.now();
    let slowFor = 0;
    let fastFor = 0;
    let dpr = getDpr;

    const tick = () => {
      const st = useStore.getState();
      const q = QUALITY[st.quality];
      const wall = performance.now();
      if (wall - lastCheck > 250) {
        lastCheck = wall;
        animating = sceneAnimating(world, simNow(), wall);
      }
      if (animating && !document.hidden) {
        invalidate();
        wanted++;
      }
      if (wall - windowStart >= 1000) {
        const delivered = perfStats.frames - frames0;
        const slow = wanted > 5 && delivered < wanted * 0.75;
        slowFor = slow ? slowFor + 1 : 0;
        fastFor = wanted > 5 && !slow ? fastFor + 1 : 0;
        if (st.autoQuality) {
          const next = nextDpr(dpr, Math.min(window.devicePixelRatio || 1, q.dpr[1], currentTier().dprCap, powerSave.on ? 1 : Infinity), delivered, wanted, slowFor, fastFor);
          if (next !== dpr) {
            dpr = next;
            setDpr(next);
            slowFor = 0;
            fastFor = 0;
          }
        }
        frames0 = perfStats.frames;
        wanted = 0;
        windowStart = wall;
      }
      // on battery saver the ambient animation runs at half rate
      timer = window.setTimeout(tick, animating ? 1000 / (q.ambientFps * (powerSave.on ? 0.5 : 1)) : 250);
    };
    tick();
    return () => {
      unsubStore();
      unsubCam();
      document.removeEventListener('visibilitychange', onVisible);
      window.clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- the initial dpr only seeds the controller
  }, [invalidate, setDpr, world]);

  return null;
}
