import * as THREE from 'three';
import { abs, attribute, cameraPosition, clamp, cos, dot, exp, float, fwidth, length, max, normalize, positionWorld, pow, smoothstep, uniform, vec3 } from 'three/tsl';
import { MeshBasicNodeMaterial } from 'three/webgpu';

/**
 * Shared water shading for ponded/flowing water and the open sea.
 *
 * Seeing through: the water column transmits T = exp(−d_path/λ) of the light from the bed, with
 * d_path = depth / max(cos θ_view, 0.2) (looking obliquely crosses more water) and λ ≈ 10 m (clear
 * coastal water, attenuation coefficient ~0.1 m⁻¹). A few metres of water show the bed clearly; 30 m
 * and more are nearly opaque. The surface's opacity is α = 1 − T·(1 − F): what is not transmitted
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
  /** Light attenuation length of the water (m): clear coastal water ≈ 10. */
  uClarity: uniform(10),
};

const CLASSIC_WEBGL = typeof window !== 'undefined' && new URLSearchParams(window.location.search).get('renderer') === 'webgl';

/** Waves: direction, wavelength (m), amplitude (m). */
const WAVES: [number, number, number, number][] = [
  [0.94, 0.34, 37, 0.16],
  [-0.45, 0.89, 21, 0.1],
  [0.71, -0.7, 11, 0.055],
  [-0.97, -0.24, 5.3, 0.03],
];
/** Water's own (back-scattered) colour, linear RGB. */
const BODY = new THREE.Vector3(0.012, 0.075, 0.11);
/** Waves fade out between these camera distances (m). */
const WAVE_FADE_NEAR = 250;
const WAVE_FADE_FAR = 2500;
/** Share of the sky reflection kept (the Fresnel term alone over-brightens grazing views). */
const REFLECT = 0.7;

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
  for (const [dx, dy, len, amp] of WAVES) {
    const k = (2 * Math.PI) / len;
    const w = Math.sqrt(9.81 * k);
    const ph: N = p.x.mul(dx * k).add(p.y.mul(dy * k)).sub(u.uTime.mul(w));
    // band limit: drop the wave once it changes by more than ~π across a pixel
    const aa: N = float(1).sub(smoothstep(0.25, 0.5, fwidth(ph).div(Math.PI)));
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
  const transmit: N = exp(path.negate().div(u.uClarity));
  const sun: N = normalize(u.uSunDir);
  const h: N = normalize(sun.add(v));
  const spec: N = pow(max(dot(n, h), 0), 220).mul(2.0).mul(farFade.mul(0.7).add(0.3));
  const body: N = vec3(BODY.x, BODY.y, BODY.z).mul(float(0.55).add(clamp(sun.y, 0, 1).mul(0.45)));
  // premultiplied: reflected sky + scattered body + glint, over the transmitted bed
  const alpha: N = clamp(float(1).sub(transmit.mul(float(1).sub(fresnel))).mul(opts.opacity ?? 1), 0.0, 1);
  const lit: N = u.uSky.mul(fresnel.mul(REFLECT)).add(body.mul(float(1).sub(fresnel)).mul(float(1).sub(transmit))).add(u.uSunColor.mul(spec));
  const m = new MeshBasicNodeMaterial();
  m.colorNode = lit.div(max(alpha, 0.001));
  m.opacityNode = alpha;
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

const waveGlsl = WAVES.map(([dx, dy, len, amp]) => {
  const k = (2 * Math.PI) / len;
  return `    { float ph = ${(dx * k).toFixed(6)} * p.x + ${(dy * k).toFixed(6)} * p.y - ${Math.sqrt(9.81 * k).toFixed(6)} * uTime;
      float aa = 1.0 - smoothstep(0.25, 0.5, fwidth(ph) / 3.14159265);
      g += vec2(${dx.toFixed(4)}, ${dy.toFixed(4)}) * (${(amp * k).toFixed(6)} * cos(ph) * aa * farFade); }`;
}).join('\n');

const fragment = /* glsl */ `
  uniform float uTime;
  uniform vec3 uSunDir;
  uniform vec3 uSunColor;
  uniform vec3 uSky;
  uniform float uClarity;
  uniform float uOpacity;
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
    float transmit = exp(-(d / max(abs(v.y), 0.2)) / uClarity);
    vec3 sun = normalize(uSunDir);
    float spec = pow(max(dot(n, normalize(sun + v)), 0.0), 220.0) * 2.0 * (0.3 + 0.7 * farFade);
    vec3 body = vec3(${BODY.x}, ${BODY.y}, ${BODY.z}) * (0.55 + 0.45 * clamp(sun.y, 0.0, 1.0));
    float alpha = clamp((1.0 - transmit * (1.0 - fresnel)) * uOpacity, 0.0, 1.0);
    vec3 lit = uSky * fresnel * ${REFLECT.toFixed(2)} + body * (1.0 - fresnel) * (1.0 - transmit) + uSunColor * spec;
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
