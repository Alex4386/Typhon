import { useThree } from '@react-three/fiber';
import { useEffect } from 'react';
import * as THREE from 'three';
import { useStore } from '../store/store';
import { useCamera } from './cameraStore';
import { gesture, isDoubleTap, LONG_PRESS_MS, tapSlop, TAP_MAX_MS, twoFinger, twoFingerDelta, type Pt } from './gestures';

/**
 * Touch gestures OrbitControls does not have: a two-finger twist turns the heading, a double tap
 * focuses on the ground point, a long press selects the point under the finger (its Inspector lists
 * what can be done there). Also marks pinches and long presses so their release does not select.
 * Mouse and pen are left to the existing handlers.
 */
export function TouchGestures() {
  const { gl, camera, scene, controls, invalidate } = useThree();

  useEffect(() => {
    const el = gl.domElement;
    const touches = new Map<number, Pt>();
    let start: Pt = { x: 0, y: 0 };
    let moved = 0;
    let prevTwo: ReturnType<typeof twoFinger> | null = null;
    let longTimer = 0;
    const ray = new THREE.Raycaster();
    const ndc = new THREE.Vector2();

    const groundAt = (x: number, y: number): THREE.Vector3 | null => {
      const r = el.getBoundingClientRect();
      ndc.set(((x - r.left) / r.width) * 2 - 1, -((y - r.top) / r.height) * 2 + 1);
      ray.setFromCamera(ndc, camera);
      for (const hit of ray.intersectObjects(scene.children, true)) {
        const m = hit.object as THREE.Mesh;
        if (!(m as unknown as { isMesh?: boolean }).isMesh) continue;
        return hit.point;
      }
      return null;
    };

    const clearLong = () => {
      window.clearTimeout(longTimer);
      longTimer = 0;
    };

    const onDown = (e: PointerEvent) => {
      if (e.pointerType !== 'touch') return;
      touches.set(e.pointerId, { x: e.clientX, y: e.clientY });
      if (touches.size === 1) {
        gesture.multi = false;
        gesture.longPressed = false;
        gesture.downAt = performance.now();
        start = { x: e.clientX, y: e.clientY };
        moved = 0;
        clearLong();
        longTimer = window.setTimeout(() => {
          if (gesture.multi || moved > tapSlop('touch') || touches.size !== 1) return;
          gesture.longPressed = true;
          navigator.vibrate?.(15);
          const p = groundAt(start.x, start.y);
          if (p && useStore.getState().tool === 'orbit') useStore.getState().select({ type: 'point', at: [p.x, -p.z] });
        }, LONG_PRESS_MS);
      } else {
        gesture.multi = true;
        clearLong();
        const [a, b] = [...touches.values()];
        prevTwo = twoFinger(a, b);
      }
    };

    const onMove = (e: PointerEvent) => {
      if (e.pointerType !== 'touch' || !touches.has(e.pointerId)) return;
      touches.set(e.pointerId, { x: e.clientX, y: e.clientY });
      if (touches.size === 1) {
        moved = Math.max(moved, Math.hypot(e.clientX - start.x, e.clientY - start.y));
        if (moved > tapSlop('touch')) clearLong();
        return;
      }
      if (touches.size !== 2 || !prevTwo) return;
      const [a, b] = [...touches.values()];
      const next = twoFinger(a, b);
      const { twist } = twoFingerDelta(prevTwo, next);
      prevTwo = next;
      if (twist === 0 || useCamera.getState().mode !== 'orbit') return;
      // turn the camera around the orbit pivot: a clockwise twist turns the view clockwise
      const ctl = controls as unknown as { target?: THREE.Vector3; update?: () => void } | null;
      const target = ctl?.target;
      if (!target) return;
      const off = camera.position.clone().sub(target);
      off.applyAxisAngle(new THREE.Vector3(0, 1, 0), twist);
      camera.position.copy(target).add(off);
      camera.lookAt(target);
      ctl.update?.();
      invalidate();
    };

    const onUp = (e: PointerEvent) => {
      if (e.pointerType !== 'touch' || !touches.has(e.pointerId)) return;
      touches.delete(e.pointerId);
      if (touches.size === 1) {
        prevTwo = null;
        return;
      }
      if (touches.size > 0) return;
      clearLong();
      prevTwo = null;
      const t = performance.now();
      const quick = t - gesture.downAt <= TAP_MAX_MS;
      if (gesture.multi || gesture.longPressed || !quick || moved > tapSlop('touch')) return;
      if (isDoubleTap(gesture.lastTap, t, e.clientX, e.clientY)) {
        gesture.lastTap = null;
        // the second tap focuses instead of selecting (its click is suppressed like a long press)
        gesture.longPressed = true;
        const p = groundAt(e.clientX, e.clientY);
        if (p) useCamera.getState().requestCamera({ kind: 'focus', point: [p.x, p.y, p.z] });
        return;
      }
      gesture.lastTap = { t, x: e.clientX, y: e.clientY };
    };

    el.addEventListener('pointerdown', onDown);
    window.addEventListener('pointermove', onMove);
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointercancel', onUp);
    return () => {
      clearLong();
      el.removeEventListener('pointerdown', onDown);
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('pointercancel', onUp);
    };
  }, [gl, camera, scene, controls, invalidate]);

  return null;
}
