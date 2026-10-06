import * as THREE from 'three';
import { attribute, cameraPosition, clamp, cos, dot, exp, float, max, mix, normalize, positionWorld, pow, uniform, vec3 } from 'three/tsl';
import { MeshBasicNodeMaterial } from 'three/webgpu';

/**
 * Shared water shading for ponded/flowing water and the open sea.
 *
 * Body colour: water depth (the `depth` attribute in metres, or a constant for the open sea)
 * absorbed Beer–Lambert style, shallow turquoise to deep navy, with shallow water more transparent.
 * Surface: a few summed deep-water waves (ω² = g·k) tilt the normal; Fresnel (Schlick, water
 * F0 ≈ 0.02) blends towards the sky colour and the sun adds a sharp glint. Scene fog applies.
 * Cheap: no reflection render target.
 *
 * The default renderer is WebGPURenderer, which takes node (TSL) materials; `?renderer=webgl` uses
 * the classic WebGLRenderer, which takes GLSL. Both share these uniforms.
 */
export const waterUniforms = {
  uTime: uniform(0),
  uSunDir: uniform(new THREE.Vector3(0.4, 0.8, 0.3).normalize()),
  uSunColor: uniform(new THREE.Color('#fff1dc')),
  uSky: uniform(new THREE.Color('#9fb4c8')),
};

const CLASSIC_WEBGL = typeof window !== 'undefined' && new URLSearchParams(window.location.search).get('renderer') === 'webgl';

/** Waves: direction, wavelength (m), amplitude (m). */
const WAVES: [number, number, number, number][] = [
  [0.94, 0.34, 37, 0.16],
  [-0.45, 0.89, 21, 0.1],
  [0.71, -0.7, 11, 0.055],
  [-0.97, -0.24, 5.3, 0.03],
];
const SHALLOW = new THREE.Vector3(0.1, 0.42, 0.45);
const DEEP = new THREE.Vector3(0.012, 0.06, 0.13);

/**
 * A water material. With `perVertexDepth` the geometry carries a `depth` attribute (metres of
 * water); otherwise `depthM` is used everywhere (the open sea).
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
  let sx: N = float(0);
  let sy: N = float(0);
  for (const [dx, dy, len, amp] of WAVES) {
    const k = (2 * Math.PI) / len;
    const w = Math.sqrt(9.81 * k);
    const ph = p.x.mul(dx * k).add(p.y.mul(dy * k)).sub(u.uTime.mul(w));
    const a = cos(ph).mul(amp * k);
    sx = sx.add(a.mul(dx));
    sy = sy.add(a.mul(dy));
  }
  const n = normalize(vec3(sx.negate(), 1, sy.negate()));
  const v = normalize(cameraPosition.sub(positionWorld));
  const ndv = clamp(dot(n, v), 0, 1);
  const fresnel = float(0.02).add(pow(float(1).sub(ndv), 5).mul(0.98));
  const d = max(depth, 0);
  const sun = normalize(u.uSunDir);
  const body = mix(vec3(SHALLOW.x, SHALLOW.y, SHALLOW.z), vec3(DEEP.x, DEEP.y, DEEP.z), float(1).sub(exp(d.div(-18)))).mul(
    float(0.55).add(clamp(sun.y, 0, 1).mul(0.45)),
  );
  const h = normalize(sun.add(v));
  const spec = pow(max(dot(n, h), 0), 220).mul(2.4);
  const col = mix(body, u.uSky, fresnel).add(u.uSunColor.mul(spec));
  const alpha = clamp(max(mix(float(0.45), float(0.94), float(1).sub(exp(d.div(-4)))), fresnel).mul(opts.opacity ?? 1), 0, 1);
  const m = new MeshBasicNodeMaterial();
  m.colorNode = col;
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
  #include <fog_pars_vertex>
  void main() {
    vDepth = mix(uDepth, depth, uUseDepthAttr);
    vec4 world = modelMatrix * vec4(position, 1.0);
    vWorld = world.xyz;
    vec4 mvPosition = viewMatrix * world;
    gl_Position = projectionMatrix * mvPosition;
    #include <fog_vertex>
  }
`;

const waveGlsl = WAVES.map(
  ([dx, dy, len, amp]) => {
    const k = (2 * Math.PI) / len;
    return `    { float ph = ${(dx * k).toFixed(6)} * p.x + ${(dy * k).toFixed(6)} * p.y - ${Math.sqrt(9.81 * k).toFixed(6)} * uTime;
      g += vec2(${dx.toFixed(4)}, ${dy.toFixed(4)}) * (${(amp * k).toFixed(6)} * cos(ph)); }`;
  },
).join('\n');

const fragment = /* glsl */ `
  uniform float uTime;
  uniform vec3 uSunDir;
  uniform vec3 uSunColor;
  uniform vec3 uSky;
  uniform float uOpacity;
  varying float vDepth;
  varying vec3 vWorld;
  #include <common>
  #include <fog_pars_fragment>
  void main() {
    vec2 p = vWorld.xz;
    vec2 g = vec2(0.0);
${waveGlsl}
    vec3 n = normalize(vec3(-g.x, 1.0, -g.y));
    vec3 v = normalize(cameraPosition - vWorld);
    float fresnel = 0.02 + 0.98 * pow(1.0 - clamp(dot(n, v), 0.0, 1.0), 5.0);
    float d = max(vDepth, 0.0);
    vec3 sun = normalize(uSunDir);
    vec3 body = mix(vec3(${SHALLOW.x}, ${SHALLOW.y}, ${SHALLOW.z}), vec3(${DEEP.x}, ${DEEP.y}, ${DEEP.z}), 1.0 - exp(-d / 18.0));
    body *= 0.55 + 0.45 * clamp(sun.y, 0.0, 1.0);
    float spec = pow(max(dot(n, normalize(sun + v)), 0.0), 220.0) * 2.4;
    vec3 col = mix(body, uSky, fresnel) + uSunColor * spec;
    float alpha = clamp(max(mix(0.45, 0.94, 1.0 - exp(-d / 4.0)), fresnel) * uOpacity, 0.0, 1.0);
    gl_FragColor = vec4(col, alpha);
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
