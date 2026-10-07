import { currentTier } from '../util/device';
import * as THREE from 'three';
import { abs, attribute, cameraPosition, clamp, cos, dot, exp, float, fwidth, length, max, mix, normalize, positionWorld, pow, smoothstep, uniform, vec3 } from 'three/tsl';

import { MeshBasicNodeMaterial } from 'three/webgpu';

/**
 * Shared water shading for ponded/flowing water and the open sea.
 *
 * Seeing through: the water column transmits T = exp(−d_path/λ) of the light from the bed, with
 * d_path = depth / max(cos θ_view, 0.2) (looking obliquely crosses more water) and λ ≈ 10 m (clear
 * the display's water clarity, default 25 m: clear open ocean, attenuation coefficient ~0.04 m⁻¹). The bed
 * shows clearly through a few metres and fades out over a few attenuation lengths. The surface's opacity is α = 1 − T·(1 − F): what is not transmitted
 * is either reflected (Fresnel F, Schlick with water F0 ≈ 0.02) or scattered back in the water's own
 * colour. The bed itself is drawn by the terrain under the transparent surface.
 *
 * Surface: summed deep-water waves (ω² = g·k) tilt the normal. Each wave is band-limited: its
 * amplitude fades out as its phase changes by π/4 to π/2 per pixel (fwidth), and all waves fade with
 * distance, so far water is smooth (Fresnel and sky only) instead of aliasing into moiré.
 *
 * The default renderer is WebGPURenderer, which takes node (TSL) materials; `?renderer=webgl` uses
 * the classic WebGLRenderer, which takes GLSL. Both share these uniforms.
 */
export const waterUniforms = {
  uTime: uniform(0),
  uSunDir: uniform(new THREE.Vector3(0.4, 0.8, 0.3).normalize()),
  uSunColor: uniform(new THREE.Color('#fff1dc')),
  /** Sky colour seen in reflection (kept muted: a bright sky turns the horizon band white). */
  uSky: uniform(new THREE.Color('#6f8296')),
  /** Light attenuation length of the water (m): open ocean ≈ 20–30, coastal ≈ 5–10 (View → Graphics). */
  uClarity: uniform(25),
  /** See-through, 0 (physical) … 1 (glass): raises the transmittance T' = T + (1 − T)·s (View → Map). */
  uSeeThrough: uniform(0.5),
};

const CLASSIC_WEBGL = typeof window !== 'undefined' && new URLSearchParams(window.location.search).get('renderer') === 'webgl';

/** Waves: direction, wavelength (m), amplitude (m). */
const WAVES: [number, number, number, number][] = [
  [0.94, 0.34, 37, 0.16],
  [-0.45, 0.89, 21, 0.1],
  [0.71, -0.7, 11, 0.055],
  [-0.97, -0.24, 5.3, 0.03],
  // incommensurate directions and lengths, so crests (and the sun's glints on them) do not line up
  // into a regular stripe pattern
  [0.28, -0.96, 13.7, 0.05],
  [-0.81, 0.59, 7.9, 0.035],
];
/** Phones and tablets (device tier): the three longest wave trains, no shoreline foam. */
const LITE = currentTier().liteWater;
const ACTIVE_WAVES = LITE ? WAVES.slice(0, 3) : WAVES;
/** Water's own (back-scattered) colour, linear RGB. */
const BODY = new THREE.Vector3(0.012, 0.075, 0.11);
/** Waves fade out between these camera distances (m). */
const WAVE_FADE_NEAR = 150;
const WAVE_FADE_FAR = 1500;
/** Share of the sky reflection kept (the Fresnel term alone over-brightens grazing views). */
const REFLECT = 0.7;
/**
 * Shoreline foam: where the water is shallower than FOAM_DEPTH (m) a subtle broken white band, drifting
 * slowly, up to FOAM_MAX coverage; faded out beyond FOAM_FAR (m) where it would only shimmer.
 */
const FOAM_DEPTH = 1.2;
const FOAM_MAX = 0.5;
const FOAM_FAR = 3000;

/**
 * A water material. With `perVertexDepth` the geometry carries a `depth` attribute (metres of
 * water); otherwise `depthM` is used everywhere (the open sea beyond the simulated area).
 */
export function waterMaterial(opts: { perVertexDepth: boolean; depthM?: number; opacity?: number }): THREE.Material {
  return CLASSIC_WEBGL ? glslWater(opts) : nodeWater(opts);
}

// ── WebGPU / node material ──

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type N = any; // TSL node objects (their typings vary across three releases)

function nodeWater(opts: { perVertexDepth: boolean; depthM?: number; opacity?: number }): THREE.Material {
  const u = waterUniforms;
  const depth: N = opts.perVertexDepth ? attribute('depth', 'float') : float(opts.depthM ?? 200);
  const p: N = positionWorld.xz;
  const toCam: N = cameraPosition.sub(positionWorld);
  const dist: N = length(toCam);
  const farFade: N = float(1).sub(smoothstep(WAVE_FADE_NEAR, WAVE_FADE_FAR, dist));
  let sx: N = float(0);
  let sy: N = float(0);
  for (const [dx, dy, len, amp] of ACTIVE_WAVES) {
    const k = (2 * Math.PI) / len;
    const w = Math.sqrt(9.81 * k);
    const ph: N = p.x.mul(dx * k).add(p.y.mul(dy * k)).sub(u.uTime.mul(w));
    // band limit: drop the wave once it changes by more than ~π across a pixel
    // band limit: a wave is drawn only while it spans ≥ ~10 pixels (beyond that it aliases into stripes)
    const aa: N = float(1).sub(smoothstep(0.08, 0.2, fwidth(ph).div(Math.PI)));
    const a: N = cos(ph).mul(amp * k).mul(aa).mul(farFade);
    sx = sx.add(a.mul(dx));
    sy = sy.add(a.mul(dy));
  }
  const n: N = normalize(vec3(sx.negate(), 1, sy.negate()));
  const v: N = normalize(toCam);
  const ndv: N = clamp(dot(n, v), 0, 1);
  const fresnel: N = float(0.02).add(pow(float(1).sub(ndv), 5).mul(0.98));
  const d: N = max(depth, 0);
  const path: N = d.div(max(abs(v.y), 0.2));
  const physical: N = exp(path.negate().div(u.uClarity));
  const transmit: N = physical.add(float(1).sub(physical).mul(u.uSeeThrough));
  const sun: N = normalize(u.uSunDir);
  const h: N = normalize(sun.add(v));
  // the glint uses a normal that flattens with distance and a highlight that broadens with it: far
  // away the sun's path is one smooth bright band instead of a grid of aliased sparkles
  const near: N = float(1).sub(smoothstep(80, 600, dist));
  const ns: N = normalize(mix(vec3(0, 1, 0), n, near));
  const shine: N = mix(float(24), float(140), near);
  const spec: N = pow(max(dot(ns, h), 0), shine).mul(mix(float(0.35), float(1.1), near));
  const body: N = vec3(BODY.x, BODY.y, BODY.z).mul(float(0.55).add(clamp(sun.y, 0, 1).mul(0.45)));
  // premultiplied: reflected sky + scattered body + glint, over the transmitted bed
  const alpha: N = clamp(float(1).sub(transmit.mul(float(1).sub(fresnel))).mul(opts.opacity ?? 1), 0.0, 1);
  let lit: N = u.uSky.mul(fresnel.mul(REFLECT)).add(body.mul(float(1).sub(fresnel)).mul(float(1).sub(transmit))).add(u.uSunColor.mul(spec));
  let a: N = alpha;
  if (opts.perVertexDepth && !LITE) {
    const ripple: N = cos(p.x.mul(0.33).add(u.uTime.mul(0.9))).mul(cos(p.y.mul(0.29).sub(u.uTime.mul(0.7)))).mul(0.5).add(0.5);
    const foam: N = float(1)
      .sub(smoothstep(0, FOAM_DEPTH, d))
      .mul(ripple.mul(0.65).add(0.35))
      .mul(FOAM_MAX)
      .mul(float(1).sub(smoothstep(FOAM_FAR * 0.5, FOAM_FAR, dist)));
    lit = lit.add(vec3(0.8, 0.82, 0.84).mul(foam));
    a = clamp(alpha.add(foam.mul(float(1).sub(alpha))), 0, 1);
  }
  const m = new MeshBasicNodeMaterial();
  m.colorNode = lit.div(max(a, 0.001));
  m.opacityNode = a;
  m.transparent = true;
  m.depthWrite = false;
  return m;
}

// ── classic WebGL / GLSL ──

const vertex = /* glsl */ `
  attribute float depth;
  uniform float uDepth;
  uniform float uUseDepthAttr;
  varying float vDepth;
  varying vec3 vWorld;
  #include <common>
  #include <fog_pars_vertex>
  #include <logdepthbuf_pars_vertex>
  void main() {
    vDepth = mix(uDepth, depth, uUseDepthAttr);
    vec4 world = modelMatrix * vec4(position, 1.0);
    vWorld = world.xyz;
    vec4 mvPosition = viewMatrix * world;
    gl_Position = projectionMatrix * mvPosition;
    // the renderers use a logarithmic depth buffer: depth tests against the terrain need it here too
    #include <logdepthbuf_vertex>
    #include <fog_vertex>
  }
`;

const waveGlsl = ACTIVE_WAVES.map(([dx, dy, len, amp]) => {
  const k = (2 * Math.PI) / len;
  return `    { float ph = ${(dx * k).toFixed(6)} * p.x + ${(dy * k).toFixed(6)} * p.y - ${Math.sqrt(9.81 * k).toFixed(6)} * uTime;
      float aa = 1.0 - smoothstep(0.08, 0.2, fwidth(ph) / 3.14159265);
      g += vec2(${dx.toFixed(4)}, ${dy.toFixed(4)}) * (${(amp * k).toFixed(6)} * cos(ph) * aa * farFade); }`;
}).join('\n');

const fragment = /* glsl */ `
  uniform float uTime;
  uniform vec3 uSunDir;
  uniform vec3 uSunColor;
  uniform vec3 uSky;
  uniform float uClarity;
  uniform float uSeeThrough;
  uniform float uOpacity;
  uniform float uUseDepthAttr;
  varying float vDepth;
  varying vec3 vWorld;
  #include <common>
  #include <fog_pars_fragment>
  #include <logdepthbuf_pars_fragment>
  void main() {
    #include <logdepthbuf_fragment>
    vec2 p = vWorld.xz;
    vec3 toCam = cameraPosition - vWorld;
    float farFade = 1.0 - smoothstep(${WAVE_FADE_NEAR.toFixed(1)}, ${WAVE_FADE_FAR.toFixed(1)}, length(toCam));
    vec2 g = vec2(0.0);
${waveGlsl}
    vec3 n = normalize(vec3(-g.x, 1.0, -g.y));
    vec3 v = normalize(toCam);
    float fresnel = 0.02 + 0.98 * pow(1.0 - clamp(dot(n, v), 0.0, 1.0), 5.0);
    float d = max(vDepth, 0.0);
    float physical = exp(-(d / max(abs(v.y), 0.2)) / uClarity);
    float transmit = physical + (1.0 - physical) * uSeeThrough;
    vec3 sun = normalize(uSunDir);
    float near = 1.0 - smoothstep(80.0, 600.0, length(toCam));
    vec3 ns = normalize(mix(vec3(0.0, 1.0, 0.0), n, near));
    float spec = pow(max(dot(ns, normalize(sun + v)), 0.0), mix(24.0, 140.0, near)) * mix(0.35, 1.1, near);
    vec3 body = vec3(${BODY.x}, ${BODY.y}, ${BODY.z}) * (0.55 + 0.45 * clamp(sun.y, 0.0, 1.0));
    float alpha = clamp((1.0 - transmit * (1.0 - fresnel)) * uOpacity, 0.0, 1.0);
    vec3 lit = uSky * fresnel * ${REFLECT.toFixed(2)} + body * (1.0 - fresnel) * (1.0 - transmit) + uSunColor * spec;
    if (uUseDepthAttr > 0.5 && ${LITE ? "false" : "true"}) {
      float ripple = cos(p.x * 0.33 + uTime * 0.9) * cos(p.y * 0.29 - uTime * 0.7) * 0.5 + 0.5;
      float foam = (1.0 - smoothstep(0.0, ${FOAM_DEPTH.toFixed(2)}, d)) * (0.35 + 0.65 * ripple) * ${FOAM_MAX.toFixed(2)}
        * (1.0 - smoothstep(${(FOAM_FAR * 0.5).toFixed(1)}, ${FOAM_FAR.toFixed(1)}, length(toCam)));
      lit += vec3(0.8, 0.82, 0.84) * foam;
      alpha = clamp(alpha + foam * (1.0 - alpha), 0.0, 1.0);
    }
    gl_FragColor = vec4(lit / max(alpha, 0.001), alpha);
    #include <tonemapping_fragment>
    #include <colorspace_fragment>
    #include <fog_fragment>
  }
`;

function glslWater(opts: { perVertexDepth: boolean; depthM?: number; opacity?: number }): THREE.Material {
  const u = waterUniforms;
  return new THREE.ShaderMaterial({
    uniforms: {
      ...THREE.UniformsUtils.clone(THREE.UniformsLib.fog),
      // the shared uniform nodes expose .value, as ShaderMaterial expects
      uTime: u.uTime as unknown as THREE.IUniform,
      uSunDir: u.uSunDir as unknown as THREE.IUniform,
      uSunColor: u.uSunColor as unknown as THREE.IUniform,
      uSky: u.uSky as unknown as THREE.IUniform,
      uClarity: u.uClarity as unknown as THREE.IUniform,
      uSeeThrough: u.uSeeThrough as unknown as THREE.IUniform,
      uDepth: { value: opts.depthM ?? 200 },
      uUseDepthAttr: { value: opts.perVertexDepth ? 1 : 0 },
      uOpacity: { value: opts.opacity ?? 1 },
    },
    vertexShader: vertex,
    fragmentShader: fragment,
    transparent: true,
    depthWrite: false,
    fog: true,
  });
}
