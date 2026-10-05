import { OrbitControls } from '@react-three/drei';
import { useFrame, useThree } from '@react-three/fiber';
import { useEffect, useRef, type ComponentRef } from 'react';
import * as THREE from 'three';
import type { WorldInfo } from '../protocol/messages';
import { useStore } from '../store/store';
import { loadBookmarks, loadLastPose, prefersReducedMotion, saveLastPose, useCamera } from './cameraStore';
import {
  anglesOf,
  angleDelta,
  applyClearance,
  clampPitch,
  clipPlanes,
  dampFactor,
  directionOf,
  ease,
  encodePose,
  flySpeed,
  stepAllowed,
  walkSpeed,
  wrapAngle,
  type CameraMode,
  type CameraPose,
} from './math';
import {
  followPoint,
  groundAt,
  groundKnown,
  overviewPose,
  plumePose,
  sceneSpan,
  sectionPose,
  summitPose,
  ventPoint,
  type SceneInfo,
} from './targets';

type OrbitControlsImpl = ComponentRef<typeof OrbitControls>;

/** Eye height for walking (m). */
const EYE_HEIGHT = 1.7;
const GRAVITY = 9.81;
const MAX_SLOPE = (42 * Math.PI) / 180;
const LOOK_SENSITIVITY = 0.0022; // rad per pixel
const KEY_TURN_RATE = 1.4; // rad/s with the arrow keys
const PAD_DEADZONE = 0.15;
const TRANSITION_SECONDS = 1.1;

/** Keys currently held (by `KeyboardEvent.code`). */
const held = new Set<string>();

function isTyping(e: Event): boolean {
  const t = e.target as HTMLElement | null;
  if (!t) return false;
  const tag = t.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || t.isContentEditable;
}

interface Transition {
  from: { pos: THREE.Vector3; heading: number; pitch: number; target: THREE.Vector3 | null };
  to: CameraPose;
  t: number;
  duration: number;
}

/**
 * Owns the camera: orbit (OrbitControls), free fly, walk, follow and tour modes, smooth
 * transitions between poses, terrain clearance, dynamic clip planes, HUD readout and the
 * shareable URL. All per-frame work reuses preallocated vectors.
 */
export function CameraRig({ world }: { world: WorldInfo }) {
  const controls = useRef<OrbitControlsImpl>(null);
  const { camera, gl, size, scene } = useThree();
  const cam = camera as THREE.PerspectiveCamera;

  // ── persistent per-frame state (no allocations in useFrame) ──
  const st = useRef({
    heading: 0,
    pitch: 0,
    lookDX: 0,
    lookDY: 0,
    dragging: false,
    vy: 0, // walk: vertical speed (scene units/s)
    tourAngle: 0,
    tourDistance: 1000,
    tourElevation: (25 * Math.PI) / 180,
    tourTarget: new THREE.Vector3(),
    followTarget: new THREE.Vector3(),
    followValid: false,
    followNext: 0,
    lastPos: new THREE.Vector3(),
    speed: 0,
    readoutNext: 0,
    urlNext: 0,
    lastUrl: '',
    clipNext: 0,
    transition: null as Transition | null,
    lastSeq: 0,
    initialised: false,
    /** Default summit view placed before the vent's tile arrived: redo it once the ground is known. */
    awaitingGround: false,
    touched: false,
    mode: 'orbit' as CameraMode,
  });
  const tmp = useRef({
    v: new THREE.Vector3(),
    w: new THREE.Vector3(),
    dir: [0, 0, 0] as number[],
    ang: [0, 0] as number[],
    clip: [0, 0] as number[],
  });

  const info = (): SceneInfo => {
    const s = useStore.getState();
    return {
      world,
      state: s.state,
      volcanoId: s.selectedVolcano,
      vExag: s.verticalExaggeration,
      dExag: s.deformationExaggeration,
      sectionPolyline: s.sectionPolyline,
      fov: cam.fov,
      aspect: size.width / Math.max(1, size.height),
    };
  };

  // Bookmarks are per world.
  useEffect(() => {
    useCamera.getState().set({ bookmarks: loadBookmarks(world.name) });
  }, [world.name]);

  // ── input listeners ──
  useEffect(() => {
    const el = gl.domElement;
    const freeLook = () => {
      const m = useCamera.getState().mode;
      return m === 'fly' || m === 'walk';
    };
    const stopTour = () => {
      if (useCamera.getState().mode === 'tour') useCamera.getState().requestCamera({ kind: 'mode', mode: 'orbit' });
    };
    const onDown = (e: PointerEvent) => {
      st.current.touched = true;
      stopTour();
      if (!freeLook() || useStore.getState().tool !== 'orbit') return;
      st.current.dragging = true;
      if (e.pointerType === 'mouse' && document.pointerLockElement !== el) {
        // pointer lock (mouse look) where the browser allows it; dragging works regardless
        try {
          const p = el.requestPointerLock?.() as unknown as Promise<void> | undefined;
          p?.catch?.(() => undefined);
        } catch {
          // ignore
        }
      }
    };
    const onUp = () => {
      st.current.dragging = false;
    };
    const onMove = (e: PointerEvent) => {
      if (!freeLook()) return;
      if (document.pointerLockElement === el || st.current.dragging) {
        st.current.lookDX += e.movementX;
        st.current.lookDY += e.movementY;
      }
    };
    const onWheel = (e: WheelEvent) => {
      st.current.touched = true;
      stopTour();
      if (!freeLook()) return;
      e.preventDefault();
      const c = useCamera.getState();
      const f = e.deltaY > 0 ? 1 / 1.15 : 1.15;
      c.set({ speedMultiplier: Math.max(0.02, Math.min(50, c.speedMultiplier * f)) });
    };
    const onLock = () => useCamera.getState().set({ pointerLocked: document.pointerLockElement === el });
    const onKeyDown = (e: KeyboardEvent) => {
      if (isTyping(e)) return;
      held.add(e.code);
      st.current.touched = true;
      const c = useCamera.getState();
      if (e.ctrlKey || e.metaKey) return;
      if (c.mode === 'tour' && !e.shiftKey) stopTour();
      const req = c.requestCamera;
      switch (e.key) {
        case '1':
          req({ kind: 'mode', mode: 'orbit' });
          break;
        case '2':
          req({ kind: 'mode', mode: 'fly' });
          break;
        case '3':
          req({ kind: 'mode', mode: 'walk' });
          break;
        case '4':
          req({ kind: 'mode', mode: 'follow' });
          break;
        case '5':
          req({ kind: 'mode', mode: 'tour' });
          break;
        case 'f':
        case 'F':
          if (!freeLook() || e.shiftKey) req({ kind: 'frame', what: 'volcano' });
          break;
        case 'p':
        case 'P':
          req({ kind: 'frame', what: 'plume' });
          break;
        case 'o':
        case 'O':
          req({ kind: 'frame', what: 'overview' });
          break;
        case 'g':
        case 'G':
          c.set({ allowUnderground: !c.allowUnderground });
          break;
        case '[':
          c.set({ speedMultiplier: Math.max(0.02, c.speedMultiplier / 1.5) });
          break;
        case ']':
          c.set({ speedMultiplier: Math.min(50, c.speedMultiplier * 1.5) });
          break;
        case '?':
          c.set({ helpOpen: !c.helpOpen });
          break;
        case 'Escape':
          if (c.helpOpen) c.set({ helpOpen: false });
          break;
        default:
          return;
      }
    };
    const onKeyUp = (e: KeyboardEvent) => held.delete(e.code);
    const onBlur = () => held.clear();
    el.addEventListener('pointerdown', onDown);
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointermove', onMove);
    el.addEventListener('wheel', onWheel, { passive: false });
    document.addEventListener('pointerlockchange', onLock);
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('keyup', onKeyUp);
    window.addEventListener('blur', onBlur);
    return () => {
      el.removeEventListener('pointerdown', onDown);
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('pointermove', onMove);
      el.removeEventListener('wheel', onWheel);
      document.removeEventListener('pointerlockchange', onLock);
      window.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('keyup', onKeyUp);
      window.removeEventListener('blur', onBlur);
    };
  }, [gl]);

  // ── helpers ──

  /** Current view heading/pitch from the camera orientation. */
  const readAngles = () => {
    const d = tmp.current.v;
    cam.getWorldDirection(d);
    anglesOf(d.x, d.y, d.z, tmp.current.ang);
    st.current.heading = tmp.current.ang[0];
    st.current.pitch = tmp.current.ang[1];
  };

  const applyAngles = () => {
    cam.rotation.order = 'YXZ';
    cam.rotation.set(st.current.pitch, -st.current.heading, 0);
  };

  /** Orbit pivot in front of the camera at a distance suited to the height above ground. */
  const pivotAhead = (out: THREE.Vector3) => {
    const s = info();
    const g = groundAt(s, cam.position.x, -cam.position.z);
    const agl = Math.max(20, cam.position.y - g);
    const dist = Math.min(sceneSpan(world), Math.max(150, agl * (st.current.pitch < -0.2 ? 1 / Math.sin(-st.current.pitch) : 3)));
    directionOf(st.current.heading, st.current.pitch, tmp.current.dir);
    out.set(cam.position.x + tmp.current.dir[0] * dist, cam.position.y + tmp.current.dir[1] * dist, cam.position.z + tmp.current.dir[2] * dist);
    const tg = groundAt(s, out.x, -out.z);
    if (out.y < tg) out.y = tg;
    return out;
  };

  const startTransition = (to: CameraPose, instant: boolean) => {
    readAngles();
    const ctl = controls.current;
    st.current.transition = {
      from: {
        pos: cam.position.clone(),
        heading: st.current.heading,
        pitch: st.current.pitch,
        target: ctl ? ctl.target.clone() : null,
      },
      to,
      t: 0,
      duration: instant || prefersReducedMotion() ? 0 : TRANSITION_SECONDS,
    };
  };

  const enterMode = (mode: CameraMode) => {
    const s = st.current;
    const prev = s.mode;
    s.mode = mode;
    useCamera.getState().set({ mode });
    readAngles();
    const ctl = controls.current;
    if (mode === 'orbit' && ctl && (prev === 'fly' || prev === 'walk')) {
      pivotAhead(ctl.target);
      ctl.update();
    }
    if (mode === 'walk') {
      s.vy = 0;
    }
    if (mode === 'tour') {
      const si = info();
      const v = ventPoint(si);
      s.tourTarget.set(v[0], v[1], v[2]);
      const dx = cam.position.x - v[0];
      const dz = cam.position.z - v[2];
      const horiz = Math.max(300, Math.hypot(dx, dz));
      s.tourDistance = Math.min(sceneSpan(world) * 0.6, Math.max(horiz, sceneSpan(world) * 0.18));
      // heading from the camera towards the vent
      s.tourAngle = wrapAngle(Math.atan2(-dx, dz));
    }
    if (mode === 'follow') {
      s.followNext = 0;
      s.followValid = false;
    }
    if (document.pointerLockElement === gl.domElement && mode !== 'fly' && mode !== 'walk') document.exitPointerLock?.();
  };

  // ── per frame ──
  useFrame((state, rawDt) => {
    const dt = Math.min(0.1, rawDt);
    const s = st.current;
    const c = useCamera.getState();
    const ctl = controls.current;
    const si = info();
    const vExag = si.vExag;
    const now = state.clock.elapsedTime;

    // first frame: initial pose from the URL, the last session, or the summit view
    if (!s.initialised && ctl) {
      s.initialised = true;
      const saved = c.initialPose ?? loadLastPose(world.name);
      applyPoseNow(saved ?? summitPose(si));
      s.awaitingGround = !saved;
      s.lastPos.copy(cam.position);
    }
    if (s.awaitingGround) {
      if (s.touched || s.transition || now > 60) {
        s.awaitingGround = false;
      } else {
        const v = world.volcanoes.find((x) => x.id === si.volcanoId) ?? world.volcanoes[0];
        const at = v?.vents[0]?.at;
        if (!at || groundKnown(si, at[0], at[1])) {
          s.awaitingGround = false;
          applyPoseNow(summitPose(si));
          s.lastPos.copy(cam.position);
        }
      }
    }

    // requests
    if (c.request && c.requestSeq !== s.lastSeq) {
      s.lastSeq = c.requestSeq;
      s.touched = true;
      const r = c.request;
      switch (r.kind) {
        case 'mode':
          if (r.mode !== s.mode) enterMode(r.mode);
          break;
        case 'pose':
          startTransition(r.pose, !!r.instant);
          break;
        case 'frame': {
          const pose = r.what === 'plume' ? plumePose(si) : r.what === 'overview' ? overviewPose(si) : r.what === 'section' ? sectionPose(si) : summitPose(si);
          if (pose) startTransition({ ...pose, mode: s.mode === 'tour' || s.mode === 'follow' ? s.mode : 'orbit' }, false);
          break;
        }
        case 'flyTo': {
          const g = groundAt(si, r.at[0], r.at[1]);
          if (s.mode === 'fly' || s.mode === 'walk') {
            readAngles();
            const agl = Math.max(s.mode === 'walk' ? EYE_HEIGHT * vExag : 200, cam.position.y - groundAt(si, cam.position.x, -cam.position.z));
            startTransition({ mode: s.mode, position: [r.at[0], g + agl, -r.at[1]], heading: s.heading, pitch: s.pitch }, false);
          } else if (ctl) {
            const off = tmp.current.w.copy(cam.position).sub(ctl.target);
            const tgt: [number, number, number] = [r.at[0], g, -r.at[1]];
            const pos: [number, number, number] = [tgt[0] + off.x, tgt[1] + off.y, tgt[2] + off.z];
            anglesOf(-off.x, -off.y, -off.z, tmp.current.ang);
            startTransition({ mode: s.mode === 'tour' ? 'orbit' : s.mode, position: pos, heading: tmp.current.ang[0], pitch: tmp.current.ang[1], target: tgt }, false);
          }
          break;
        }
        case 'focus': {
          const p = r.point;
          if (ctl && (s.mode === 'orbit' || s.mode === 'follow')) {
            const off = tmp.current.w.copy(cam.position).sub(ctl.target);
            const len = off.length();
            const want = Math.max(80, Math.min(len, 4000));
            off.multiplyScalar(want / Math.max(1e-6, len));
            anglesOf(-off.x, -off.y, -off.z, tmp.current.ang);
            startTransition(
              { mode: 'orbit', position: [p[0] + off.x, p[1] + off.y, p[2] + off.z], heading: tmp.current.ang[0], pitch: tmp.current.ang[1], target: [p[0], p[1], p[2]] },
              false,
            );
          } else {
            // fly towards the point, stopping short of it
            const dx = p[0] - cam.position.x;
            const dy = p[1] - cam.position.y;
            const dz = p[2] - cam.position.z;
            const d = Math.hypot(dx, dy, dz);
            const k = Math.max(0, (d - Math.max(30, d * 0.15)) / Math.max(1e-6, d));
            anglesOf(dx, dy, dz, tmp.current.ang);
            startTransition(
              { mode: s.mode === 'walk' ? 'fly' : s.mode, position: [cam.position.x + dx * k, cam.position.y + dy * k, cam.position.z + dz * k], heading: tmp.current.ang[0], pitch: tmp.current.ang[1] },
              false,
            );
          }
          break;
        }
      }
    }

    if (ctl) ctl.enabled = !s.transition && (s.mode === 'orbit' || s.mode === 'follow');

    // transition between poses
    if (s.transition) {
      const tr = s.transition;
      tr.t += dt;
      const k = tr.duration <= 0 ? 1 : ease(tr.t / tr.duration);
      const to = tr.to;
      cam.position.set(
        tr.from.pos.x + (to.position[0] - tr.from.pos.x) * k,
        tr.from.pos.y + (to.position[1] - tr.from.pos.y) * k,
        tr.from.pos.z + (to.position[2] - tr.from.pos.z) * k,
      );
      s.heading = wrapAngle(tr.from.heading + angleDelta(tr.from.heading, to.heading) * k);
      s.pitch = tr.from.pitch + (to.pitch - tr.from.pitch) * k;
      if (ctl && to.target) {
        const ft = tr.from.target ?? ctl.target;
        ctl.target.set(ft.x + (to.target[0] - ft.x) * k, ft.y + (to.target[1] - ft.y) * k, ft.z + (to.target[2] - ft.z) * k);
      }
      if (k >= 1) {
        s.transition = null;
        if (to.mode !== s.mode) enterMode(to.mode);
        if (ctl && to.target && (s.mode === 'orbit' || s.mode === 'follow')) {
          cam.lookAt(ctl.target);
          ctl.update();
        } else {
          applyAngles();
        }
      } else if (ctl && to.target && (to.mode === 'orbit' || to.mode === 'follow')) {
        cam.lookAt(ctl.target);
      } else {
        applyAngles();
      }
    } else {
      switch (s.mode) {
        case 'fly':
          stepFly(dt, si);
          break;
        case 'walk':
          stepWalk(dt, si);
          break;
        case 'tour':
          stepTour(dt, c.tourSpeed);
          break;
        case 'follow':
          stepFollow(dt, now, si, c.followTarget);
          break;
        case 'orbit':
          if (ctl) ctl.update();
          break;
      }
      // keep orbit/follow cameras above the ground
      if ((s.mode === 'orbit' || s.mode === 'follow') && !c.allowUnderground) {
        const g = groundAt(si, cam.position.x, -cam.position.z);
        const y = applyClearance(cam.position.y, g, Math.max(c.clearance, 2) * vExag, false);
        if (y !== cam.position.y) {
          cam.position.y = y;
          if (ctl) cam.lookAt(ctl.target);
        }
      }
    }

    // measured speed (m/s, horizontal + vertical with exaggeration removed)
    const moved = Math.hypot(cam.position.x - s.lastPos.x, (cam.position.y - s.lastPos.y) / vExag, cam.position.z - s.lastPos.z);
    s.speed = s.speed + (moved / Math.max(1e-4, dt) - s.speed) * dampFactor(0.25, dt);
    s.lastPos.copy(cam.position);

    // clip planes for the height above ground
    if (now >= s.clipNext) {
      s.clipNext = now + 0.25;
      const g = groundAt(si, cam.position.x, -cam.position.z);
      const dist = ctl ? cam.position.distanceTo(ctl.target) : 0;
      clipPlanes(cam.position.y - g, dist, sceneSpan(world), tmp.current.clip);
      const [near, far] = tmp.current.clip;
      if (Math.abs(near - cam.near) > cam.near * 0.1 || Math.abs(far - cam.far) > cam.far * 0.1) {
        cam.near = near;
        cam.far = far;
        cam.updateProjectionMatrix();
      }
      // fog distances follow the view distance, so a far-away framing (a 20 km column seen from
      // 40 km) still shows the volcano, while close views keep their aerial perspective
      const fog = scene.fog as THREE.Fog | null;
      if (fog && 'near' in fog) {
        const span = sceneSpan(world);
        const view = Math.max(dist, cam.position.y - g);
        fog.near = Math.max(span * 0.9, view * 0.9);
        fog.far = Math.max(span * 3.2, view * 3);
      }
    }

    // HUD readout (a few times a second)
    if (now >= s.readoutNext) {
      s.readoutNext = now + 0.2;
      readAngles();
      const g = groundAt(si, cam.position.x, -cam.position.z);
      useCamera.getState().set({
        readout: {
          x: cam.position.x,
          y: -cam.position.z,
          altitude: cam.position.y / vExag,
          aboveGround: (cam.position.y - g) / vExag,
          heading: s.heading,
          pitch: s.pitch,
          speed: s.speed < 0.05 ? 0 : s.speed,
          fov: cam.fov,
          aspect: size.width / Math.max(1, size.height),
        },
      });
    }

    // shareable URL + last pose (about once a second, only when changed)
    if (now >= s.urlNext && !s.transition) {
      s.urlNext = now + 1;
      readAngles();
      const pose: CameraPose = {
        mode: s.mode === 'tour' ? 'orbit' : s.mode,
        position: [cam.position.x, cam.position.y, cam.position.z],
        heading: s.heading,
        pitch: s.pitch,
      };
      if (ctl && (s.mode === 'orbit' || s.mode === 'follow' || s.mode === 'tour')) pose.target = [ctl.target.x, ctl.target.y, ctl.target.z];
      const text = encodePose(pose);
      if (text !== s.lastUrl) {
        s.lastUrl = text;
        try {
          const url = new URL(window.location.href);
          url.searchParams.set('cam', text);
          window.history.replaceState(window.history.state, '', url);
        } catch {
          // ignore (sandboxed history)
        }
        saveLastPose(world.name, text);
      }
    }
  });

  // ── mode steppers ──

  function applyPoseNow(pose: CameraPose) {
    const s = st.current;
    const ctl = controls.current;
    cam.position.set(pose.position[0], pose.position[1], pose.position[2]);
    s.heading = pose.heading;
    s.pitch = clampPitch(pose.pitch);
    if (ctl) {
      if (pose.target) ctl.target.set(pose.target[0], pose.target[1], pose.target[2]);
      else pivotAhead(ctl.target);
    }
    s.mode = pose.mode === 'tour' ? 'orbit' : pose.mode;
    useCamera.getState().set({ mode: s.mode });
    if (ctl && (s.mode === 'orbit' || s.mode === 'follow')) {
      cam.lookAt(ctl.target);
      ctl.update();
    } else {
      applyAngles();
    }
  }

  function look(dt: number, pad: Gamepad | null) {
    const s = st.current;
    let dh = s.lookDX * LOOK_SENSITIVITY;
    let dp = -s.lookDY * LOOK_SENSITIVITY;
    s.lookDX = 0;
    s.lookDY = 0;
    if (held.has('ArrowLeft')) dh -= KEY_TURN_RATE * dt;
    if (held.has('ArrowRight')) dh += KEY_TURN_RATE * dt;
    if (held.has('ArrowUp')) dp += KEY_TURN_RATE * 0.7 * dt;
    if (held.has('ArrowDown')) dp -= KEY_TURN_RATE * 0.7 * dt;
    if (pad) {
      dh += deadzone(pad.axes[2] ?? 0) * 2.2 * dt;
      dp -= deadzone(pad.axes[3] ?? 0) * 1.6 * dt;
    }
    s.heading = wrapAngle(s.heading + dh);
    s.pitch = clampPitch(s.pitch + dp);
  }

  function stepFly(dt: number, si: SceneInfo) {
    const s = st.current;
    const c = useCamera.getState();
    const pad = gamepad();
    look(dt, pad);
    const g = groundAt(si, cam.position.x, -cam.position.z);
    const agl = (cam.position.y - g) / si.vExag;
    const boost = held.has('ShiftLeft') || held.has('ShiftRight') || !!pad?.buttons[0]?.pressed;
    const slow = held.has('ControlLeft') || held.has('ControlRight') || held.has('AltLeft') || held.has('AltRight');
    const v = flySpeed(agl, c.speedMultiplier, boost, slow) * dt;
    let fwd = (held.has('KeyW') ? 1 : 0) - (held.has('KeyS') ? 1 : 0);
    let right = (held.has('KeyD') ? 1 : 0) - (held.has('KeyA') ? 1 : 0);
    let up = (held.has('KeyE') || held.has('Space') ? 1 : 0) - (held.has('KeyQ') ? 1 : 0);
    if (pad) {
      fwd -= deadzone(pad.axes[1] ?? 0);
      right += deadzone(pad.axes[0] ?? 0);
      up += (pad.buttons[7]?.value ?? 0) - (pad.buttons[6]?.value ?? 0);
    }
    const d = directionOf(s.heading, s.pitch, tmp.current.dir);
    // forward follows the view (including pitch); strafe is horizontal; up is world up
    const sx = Math.cos(s.heading);
    const sz = Math.sin(s.heading);
    cam.position.x += (d[0] * fwd + sx * right) * v;
    cam.position.y += (d[1] * fwd + up) * v;
    cam.position.z += (d[2] * fwd + sz * right) * v;
    const g2 = groundAt(si, cam.position.x, -cam.position.z);
    cam.position.y = applyClearance(cam.position.y, g2, c.clearance * si.vExag, c.allowUnderground);
    applyAngles();
  }

  function stepWalk(dt: number, si: SceneInfo) {
    const s = st.current;
    const pad = gamepad();
    look(dt, pad);
    const run = held.has('ShiftLeft') || held.has('ShiftRight') || !!pad?.buttons[0]?.pressed;
    const slow = held.has('ControlLeft') || held.has('ControlRight') || held.has('AltLeft') || held.has('AltRight');
    let fwd = (held.has('KeyW') ? 1 : 0) - (held.has('KeyS') ? 1 : 0);
    let right = (held.has('KeyD') ? 1 : 0) - (held.has('KeyA') ? 1 : 0);
    if (pad) {
      fwd -= deadzone(pad.axes[1] ?? 0);
      right += deadzone(pad.axes[0] ?? 0);
    }
    const len = Math.hypot(fwd, right);
    const speed = walkSpeed(run, slow);
    const eye = EYE_HEIGHT * si.vExag;
    const groundHere = groundAt(si, cam.position.x, -cam.position.z);
    if (len > 0) {
      const step = (speed * dt) / Math.max(1, len);
      // horizontal movement along the heading
      const fx = Math.sin(s.heading);
      const fz = -Math.cos(s.heading);
      const nx = cam.position.x + (fx * fwd + Math.cos(s.heading) * right) * step;
      const nz = cam.position.z + (fz * fwd + Math.sin(s.heading) * right) * step;
      const groundThere = groundAt(si, nx, -nz);
      const onGround = cam.position.y <= groundHere + eye + 0.01;
      // the slope limit compares real (un-exaggerated) rise over run
      if (!onGround || stepAllowed(groundHere / si.vExag, groundThere / si.vExag, Math.hypot(nx - cam.position.x, nz - cam.position.z), MAX_SLOPE)) {
        cam.position.x = nx;
        cam.position.z = nz;
      }
    }
    const ground = groundAt(si, cam.position.x, -cam.position.z);
    const onGround = cam.position.y <= ground + eye + 0.01;
    if (onGround && (held.has('Space') || pad?.buttons[1]?.pressed)) s.vy = 4.5 * si.vExag;
    s.vy -= GRAVITY * si.vExag * dt;
    cam.position.y += s.vy * dt;
    if (cam.position.y < ground + eye) {
      cam.position.y = ground + eye;
      s.vy = 0;
    }
    applyAngles();
  }

  function stepTour(dt: number, tourSpeed: number) {
    const s = st.current;
    const ctl = controls.current;
    s.tourAngle = wrapAngle(s.tourAngle + dt * 0.06 * tourSpeed * (prefersReducedMotion() ? 0.4 : 1));
    // gentle bob of the elevation angle
    const elev = s.tourElevation + Math.sin(s.tourAngle * 2) * 0.06;
    directionOf(s.tourAngle, -elev, tmp.current.dir);
    const d = s.tourDistance / Math.cos(elev);
    cam.position.set(s.tourTarget.x - tmp.current.dir[0] * d, s.tourTarget.y - tmp.current.dir[1] * d, s.tourTarget.z - tmp.current.dir[2] * d);
    if (ctl) ctl.target.copy(s.tourTarget);
    cam.lookAt(s.tourTarget);
  }

  function stepFollow(dt: number, now: number, si: SceneInfo, target: Parameters<typeof followPoint>[1]) {
    const s = st.current;
    const ctl = controls.current;
    if (!ctl) return;
    if (now >= s.followNext) {
      s.followNext = now + (target === 'lavaFront' ? 1 : 0.25);
      const p = followPoint(si, target);
      if (p) {
        s.followTarget.set(p[0], p[1], p[2]);
        s.followValid = true;
      }
    }
    if (s.followValid) {
      const k = dampFactor(0.6, dt);
      const dx = (s.followTarget.x - ctl.target.x) * k;
      const dy = (s.followTarget.y - ctl.target.y) * k;
      const dz = (s.followTarget.z - ctl.target.z) * k;
      // move camera and pivot together: the user keeps their orbit offset
      ctl.target.x += dx;
      ctl.target.y += dy;
      ctl.target.z += dz;
      cam.position.x += dx;
      cam.position.y += dy;
      cam.position.z += dz;
    }
    ctl.update();
  }

  const span = sceneSpan(world);
  return (
    <OrbitControls
      ref={controls}
      makeDefault
      maxPolarAngle={Math.PI * 0.495}
      minDistance={20}
      maxDistance={span * 4}
      enableDamping
      dampingFactor={0.12}
      zoomSpeed={1.2}
      zoomToCursor
      screenSpacePanning={false}
    />
  );
}

function deadzone(v: number): number {
  return Math.abs(v) < PAD_DEADZONE ? 0 : (v - Math.sign(v) * PAD_DEADZONE) / (1 - PAD_DEADZONE);
}

function gamepad(): Gamepad | null {
  try {
    const pads = navigator.getGamepads?.();
    if (!pads) return null;
    for (const p of pads) if (p && p.connected) return p;
  } catch {
    // ignore
  }
  return null;
}
