// MOCK — a synthetic, cheap stand-in for the Java simulator so the visualizer can be developed
// before sim-server exists. Nothing here is physically validated; it only produces plausible,
// evolving fields and events in the shape of protocol v1.

import { Field, type FieldId } from '../src/protocol/fields';
import { SectionFlag } from '../src/protocol/frames';
import type {
  AlertLevel,
  EruptionStyle,
  EruptiveRegime,
  MaterialInfo,
  DepositTypeInfo,
  SectionMeta,
  SectionOverlay,
  SimEvent,
  StateMessage,
  UnitInfo,
  VolcanoInfo,
  VolcanoState,
  WorldInfo,
  XY,
} from '../src/protocol/messages';

export const TILE = 64;
export const TILES = 8;
export const N = TILE * TILES; // columns per side
export const CELL = 20; // m
export const ORIGIN: XY = [-(N * CELL) / 2, -(N * CELL) / 2];
const AMBIENT = 12;

export const MATERIALS: MaterialInfo[] = [
  { id: 0, name: 'air', color: '#00000000', kind: 'void' },
  { id: 1, name: 'granite basement', color: '#8a7f78', kind: 'rock' },
  { id: 2, name: 'old lava', color: '#5a5550', kind: 'rock' },
  { id: 3, name: 'old tephra', color: '#9c8f7a', kind: 'tephra' },
  { id: 4, name: 'basalt', color: '#3d3a3a', kind: 'rock' },
  { id: 5, name: 'fall deposit', color: '#b8b0a2', kind: 'tephra' },
  { id: 6, name: 'magma', color: '#ff5a1f', kind: 'magma' },
  { id: 7, name: 'water', color: '#2c6fb3', kind: 'water' },
  { id: 8, name: 'soil', color: '#6e5a3c', kind: 'soil' },
  { id: 9, name: 'void', color: '#000000', kind: 'void' },
];

export const DEPOSIT_TYPES: DepositTypeInfo[] = [
  { id: 0, name: 'BASEMENT', color: '#8a7f78' },
  { id: 1, name: 'LAVA', color: '#3d3a3a' },
  { id: 2, name: 'FALL', color: '#c9c1b2' },
  { id: 3, name: 'PDC', color: '#a58a6a' },
  { id: 4, name: 'LAHAR', color: '#7b6448' },
  { id: 5, name: 'TUBE_ROOF', color: '#2a2626' },
  { id: 6, name: 'INTRUSION', color: '#7a3b2e' },
  { id: 7, name: 'EDIFICE', color: '#5a5550' },
];

function mulberry32(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

interface MockVolcano {
  info: VolcanoInfo;
  explosive: boolean;
  pressure: number;
  strength: number;
  pressureRate: number;
  temperature: number;
  silica: number;
  water: number;
  crystals: number;
  erupting: boolean;
  eruptionRate: number;
  eruption: number;
  eruptionStart: number;
  regime: EruptiveRegime;
  alert: AlertLevel;
  rsam: number;
  vt: number;
  lp: number;
  tremor: boolean;
  lavaUnit: number;
  ashUnit: number;
  nextBomb: number;
  dikes: { id: number; path: [number, number, number][]; active: boolean }[];
}

export interface FieldGrids {
  [Field.SurfaceElevation]: Float32Array;
  [Field.LavaDepth]: Float32Array;
  [Field.LavaTemperature]: Float32Array;
  [Field.WaterDepth]: Float32Array;
  [Field.PdcDepth]: Float32Array;
  [Field.LaharDepth]: Float32Array;
  [Field.AshDepth]: Float32Array;
  [Field.SurfaceTemperature]: Float32Array;
  [Field.WaterTableDepth]: Float32Array;
  [Field.TopUnit]: Float32Array;
  [Field.Uplift]: Float32Array;
  [Field.SteamFraction]: Float32Array;
}

export interface Snapshot {
  time: number;
  grids: Record<number, Float32Array>;
  base: Float32Array;
  lavaThick: Float32Array;
  volcanoes: string;
}

const STATIONS: { id: string; at: XY }[] = [
  { id: 'SUMM', at: [300, 200] },
  { id: 'NFLK', at: [0, 2200] },
  { id: 'EFLK', at: [2400, -300] },
  { id: 'CONE', at: [2700, -1800] },
];

export class MockWorld {
  time = 0;
  rain = 0;
  wind = { speed: 8, bearingDeg: 250 };
  readonly grids: FieldGrids;
  /** Pre-eruption ground (old edifice top). */
  readonly base: Float32Array;
  /** Solidified recent lava thickness. */
  readonly lavaThick: Float32Array;
  readonly holes: { at: XY; radius: number; depth: number }[] = [];
  readonly volcanoes: MockVolcano[];
  readonly units: UnitInfo[] = [];
  private readonly rand: () => number;
  private pendingEvents: SimEvent[] = [];
  private bombId = 0;
  private dikeId = 0;
  private waterSources: { at: XY; rate: number; until: number }[] = [];

  constructor(seed = 1) {
    this.rand = mulberry32(seed);
    const g = (): Float32Array => new Float32Array(N * N);
    this.grids = {
      [Field.SurfaceElevation]: g(),
      [Field.LavaDepth]: g(),
      [Field.LavaTemperature]: g(),
      [Field.WaterDepth]: g(),
      [Field.PdcDepth]: g(),
      [Field.LaharDepth]: g(),
      [Field.AshDepth]: g(),
      [Field.SurfaceTemperature]: g(),
      [Field.WaterTableDepth]: g(),
      [Field.TopUnit]: g(),
      [Field.Uplift]: g(),
      [Field.SteamFraction]: g(),
    };
    this.base = g();
    this.lavaThick = g();
    this.units.push({ id: 0, volcanoId: null, depositType: 0, eruption: null, time: null, label: 'basement' });
    this.units.push({ id: 1, volcanoId: null, depositType: 7, eruption: null, time: null, label: 'old edifice' });

    this.volcanoes = [
      this.makeVolcano('mock-fuji', 'Mock Stratovolcano (MOCK)', [0, 0], false, 14.0),
      this.makeVolcano('mock-cone', 'Mock Satellite Cone (MOCK)', [2800, -2200], true, 9.0),
    ];
    this.buildTerrain();
  }

  private makeVolcano(id: string, name: string, at: XY, explosive: boolean, pressure: number): MockVolcano {
    return {
      info: {
        id,
        name,
        vents: [{ id: `${id}/summit`, kind: 'crater', at, z: 0, radius: explosive ? 60 : 140 }],
        chamber: { center: [at[0], at[1], explosive ? -2500 : -4000], radius: explosive ? 600 : 1200 },
      },
      explosive,
      pressure,
      strength: 15,
      pressureRate: explosive ? 0.004 : 0.003,
      temperature: explosive ? 900 : 1150,
      silica: explosive ? 63 : 50,
      water: explosive ? 4.5 : 0.6,
      crystals: explosive ? 0.3 : 0.08,
      erupting: false,
      eruptionRate: 0,
      eruption: 0,
      eruptionStart: 0,
      regime: 'NONE',
      alert: 'DORMANT',
      rsam: 0,
      vt: 0,
      lp: 0,
      tremor: false,
      lavaUnit: 1,
      ashUnit: 1,
      nextBomb: 0,
      dikes: [],
    };
  }

  // ───────────── geometry helpers ─────────────

  colOf(x: number, y: number): [number, number] {
    return [Math.floor((x - ORIGIN[0]) / CELL), Math.floor((y - ORIGIN[1]) / CELL)];
  }

  xyOf(i: number, j: number): XY {
    return [ORIGIN[0] + (i + 0.5) * CELL, ORIGIN[1] + (j + 0.5) * CELL];
  }

  idx(i: number, j: number): number {
    return j * N + i;
  }

  ground(i: number, j: number): number {
    const k = this.idx(i, j);
    return this.grids[Field.SurfaceElevation][k];
  }

  private noise(x: number, y: number): number {
    return (
      Math.sin(x * 0.0011 + 1.3) * Math.cos(y * 0.0009 - 0.4) * 40 +
      Math.sin(x * 0.0043 + y * 0.0031) * 12 +
      Math.cos(x * 0.0091 - y * 0.0077) * 5
    );
  }

  private buildTerrain(): void {
    const elev = this.grids[Field.SurfaceElevation];
    for (let j = 0; j < N; j++) {
      for (let i = 0; i < N; i++) {
        const [x, y] = this.xyOf(i, j);
        const r = Math.hypot(x, y);
        let z = 1900 * Math.exp(-((r / 3300) ** 1.6));
        // summit crater
        z -= 90 * Math.exp(-((r / 160) ** 2));
        // satellite cone
        const r2 = Math.hypot(x - 2800, y + 2200);
        z += 380 * Math.exp(-((r2 / 650) ** 1.8)) - 45 * Math.exp(-((r2 / 70) ** 2));
        // radial gullies
        const ang = Math.atan2(y, x);
        z -= Math.max(0, Math.sin(ang * 9)) * 25 * Math.min(1, r / 1500) * Math.exp(-r / 4500);
        z += this.noise(x, y);
        // coast to the south-east
        z += 60 - Math.max(0, (x - y) * 0.035 - 120);
        elev[this.idx(i, j)] = z;
        this.base[this.idx(i, j)] = z;
      }
    }
    for (const v of this.volcanoes) {
      for (const vent of v.info.vents) {
        const [i, j] = this.colOf(vent.at[0], vent.at[1]);
        vent.z = this.ground(i, j);
      }
    }
    const wd = this.grids[Field.WaterDepth];
    const wt = this.grids[Field.WaterTableDepth];
    const st = this.grids[Field.SurfaceTemperature];
    for (let k = 0; k < N * N; k++) {
      wd[k] = Math.max(0, -elev[k]);
      wt[k] = Math.max(-0.5, Math.min(120, 4 + elev[k] * 0.06));
      st[k] = AMBIENT - Math.max(0, elev[k]) * 0.0065;
    }
  }

  worldInfo(): WorldInfo {
    let lo = Infinity;
    let hi = -Infinity;
    for (const z of this.grids[Field.SurfaceElevation]) {
      lo = Math.min(lo, z);
      hi = Math.max(hi, z);
    }
    return {
      name: 'mock-island (MOCK)',
      origin: ORIGIN,
      cellSize: CELL,
      tileSize: TILE,
      tiles: { minTx: 0, minTy: 0, maxTx: TILES - 1, maxTy: TILES - 1 },
      seaLevel: 0,
      elevationRange: [Math.floor(lo), Math.ceil(hi)],
      volcanoes: this.volcanoes.map((v) => v.info),
      materials: MATERIALS,
      depositTypes: DEPOSIT_TYPES,
    };
  }

  // ───────────── simulation ─────────────

  /**
   * Advances up to `seconds` of simulated time in 10 s steps, stopping early once `budgetMs` of
   * wall time is used. Returns the simulated seconds actually advanced.
   */
  advance(seconds: number, budgetMs = Infinity): number {
    const dt = 10;
    const start = performance.now();
    let done = 0;
    while (seconds - done > 1e-9) {
      const h = Math.min(dt, seconds - done);
      this.stepOnce(h);
      done += h;
      if (performance.now() - start > budgetMs) break;
    }
    return done;
  }

  private emit(e: SimEvent): void {
    this.pendingEvents.push(e);
  }

  drainEvents(): SimEvent[] {
    const out = this.pendingEvents;
    this.pendingEvents = [];
    return out;
  }

  private stepOnce(dt: number): void {
    this.time += dt;
    for (const v of this.volcanoes) this.stepVolcano(v, dt);
    this.stepLava(dt);
    this.stepThermalAndWater(dt);
    this.stepMassFlows(dt);
    if (this.time - this.lastDeformation >= 60) {
      this.stepDeformation();
      this.lastDeformation = this.time;
    }
  }

  private lastDeformation = -Infinity;

  private poisson(mean: number): number {
    if (mean <= 0) return 0;
    if (mean > 30) return Math.max(0, Math.round(mean + Math.sqrt(mean) * (this.rand() * 2 - 1)));
    const l = Math.exp(-mean);
    let k = 0;
    let p = this.rand();
    while (p > l) {
      k++;
      p *= this.rand();
    }
    return k;
  }

  private gr(b: number, mmin: number, mmax: number): number {
    const u = this.rand();
    const span = 1 - Math.pow(10, -b * (mmax - mmin));
    return mmin - Math.log10(1 - u * span) / b;
  }

  private stepVolcano(v: MockVolcano, dt: number): void {
    const vent = v.info.vents[0];
    const c = v.info.chamber.center;
    if (!v.erupting) {
      v.pressure += v.pressureRate * dt * (1 + 0.3 * Math.sin(this.time / 900));
      if (v.pressure > v.strength * 0.82 && v.dikes.length === 0 && this.rand() < 0.002 * dt) this.startDike(v);
      if (v.pressure >= v.strength) this.beginEruption(v, 'AUTOMATIC');
    } else {
      v.eruptionRate = Math.max(0, v.eruptionRate * Math.exp(-dt / (v.explosive ? 900 : 5400)));
      v.pressure = Math.max(1, v.pressure - (v.explosive ? 0.01 : 0.002) * dt);
      if (v.pressure <= 2.5 || v.eruptionRate < 0.5) this.endEruption(v);
    }
    for (const d of v.dikes) {
      if (!d.active) continue;
      const tip = d.path[d.path.length - 1];
      const ground = vent.z;
      const step: [number, number, number] = [tip[0] + (this.rand() - 0.3) * 60, tip[1] + (this.rand() - 0.5) * 60, tip[2] + 80 * (dt / 10)];
      d.path.push(step);
      this.emit({ kind: 'seismic', time: this.time, volcanoId: v.info.id, type: 'VT', magnitude: this.gr(1.5, 0.5, 3), hypocenter: step, durationSeconds: 2, swarm: true });
      if (step[2] >= ground - 50) {
        d.active = false;
        if (!v.erupting) this.beginEruption(v, 'DIKE');
      }
      this.emit({ kind: 'dikeAdvanced', time: this.time, volcanoId: v.info.id, dikeId: d.id, path: d.path.slice() });
    }
    const ratio = v.pressure / v.strength;
    const vtRate = 0.002 + 0.06 * Math.max(0, ratio - 0.5) ** 2 + (v.erupting ? 0.01 : 0);
    const nvt = this.poisson(vtRate * dt);
    for (let k = 0; k < nvt; k++) {
      const hyp: [number, number, number] = [c[0] + (this.rand() - 0.5) * 1200, c[1] + (this.rand() - 0.5) * 1200, c[2] + this.rand() * (vent.z - c[2]) * 0.8];
      const m = this.gr(1.0, 0.2, 4);
      this.emit({ kind: 'seismic', time: this.time, volcanoId: v.info.id, type: 'VT', magnitude: m, hypocenter: hyp, durationSeconds: 1 + m, swarm: false });
      v.rsam += Math.pow(10, m) * 0.2;
    }
    const lpRate = v.erupting ? 0.02 + 0.002 * v.eruptionRate : 0.001;
    const nlp = this.poisson(lpRate * dt);
    for (let k = 0; k < nlp; k++) {
      const m = this.gr(1.6, 0.2, 2.5);
      this.emit({ kind: 'seismic', time: this.time, volcanoId: v.info.id, type: 'LP', magnitude: m, hypocenter: [vent.at[0], vent.at[1], vent.z - 300 - this.rand() * 900], durationSeconds: 8 + m * 4, swarm: false });
      v.rsam += Math.pow(10, m) * 0.3;
    }
    v.tremor = v.erupting && v.eruptionRate > 5;
    if (v.tremor && this.rand() < dt / 120) {
      this.emit({ kind: 'seismic', time: this.time, volcanoId: v.info.id, type: 'TREMOR', magnitude: 1.5 + Math.log10(1 + v.eruptionRate) * 0.5, hypocenter: [vent.at[0], vent.at[1], vent.z - 500], durationSeconds: 60 + this.rand() * 120, swarm: false });
    }
    v.rsam = v.rsam * Math.exp(-dt / 600) + (v.tremor ? 3 * dt / 600 : 0);
    v.vt = v.vt * Math.exp(-dt / 600) + nvt * 6;
    v.lp = v.lp * Math.exp(-dt / 600) + nlp * 6;

    const prevAlert = v.alert;
    v.alert = v.erupting ? 'ERUPTING' : ratio > 0.9 ? 'ERUPTION_IMMINENT' : ratio > 0.7 ? 'MAJOR_ACTIVITY' : ratio > 0.45 ? 'MINOR_ACTIVITY' : 'DORMANT';
    if (v.alert !== prevAlert) this.emit({ kind: 'alertChanged', time: this.time, volcanoId: v.info.id, previous: prevAlert, current: v.alert });

    if (v.erupting && v.explosive) this.stepExplosive(v, dt);
    if (v.erupting && !v.explosive && v.regime === 'OPEN_VENT' && this.rand() < dt / 240) {
      // occasional Strombolian burst on the effusive volcano
      this.launchBombs(v, 6, 60);
      this.emit({ kind: 'seismic', time: this.time, volcanoId: v.info.id, type: 'EXPLOSION', magnitude: 1.2 + this.rand(), hypocenter: [vent.at[0], vent.at[1], vent.z - 50], durationSeconds: 6, swarm: false });
    }
    if (this.rand() < dt / 1800) {
      const ang = this.rand() * Math.PI * 2;
      const r = 200 + this.rand() * 900;
      const x = vent.at[0] + Math.cos(ang) * r;
      const y = vent.at[1] + Math.sin(ang) * r;
      const [i, j] = this.colOf(x, y);
      const feature = ['FUMAROLE', 'HOT_SPRING', 'MUD_POT', 'GEYSER', 'SULFUR_DEPOSIT'][Math.floor(this.rand() * 5)];
      this.emit({ kind: 'geothermalFeature', time: this.time, volcanoId: v.info.id, feature, at: [x, y, this.ground(i, j)] });
    }
  }

  private startDike(v: MockVolcano): void {
    const c = v.info.chamber.center;
    const d = { id: ++this.dikeId, path: [[c[0], c[1], c[2] + v.info.chamber.radius * 0.5] as [number, number, number]], active: true };
    v.dikes.push(d);
  }

  private beginEruption(v: MockVolcano, cause: string): void {
    v.erupting = true;
    v.eruption++;
    v.eruptionStart = this.time;
    v.eruptionRate = v.explosive ? 900 + this.rand() * 600 : 60 + this.rand() * 60;
    v.regime = v.explosive ? 'EXPLOSIVE' : this.rand() < 0.5 ? 'FOUNTAINING' : 'OPEN_VENT';
    const lavaUnit = this.units.length;
    this.units.push({ id: lavaUnit, volcanoId: v.info.id, depositType: v.explosive ? 3 : 1, eruption: v.eruption, time: this.time, label: `${v.info.id} eruption ${v.eruption} ${v.explosive ? 'PDC' : 'lava'}` });
    v.lavaUnit = lavaUnit;
    const ashUnit = this.units.length;
    this.units.push({ id: ashUnit, volcanoId: v.info.id, depositType: 2, eruption: v.eruption, time: this.time, label: `${v.info.id} eruption ${v.eruption} fall` });
    v.ashUnit = ashUnit;
    this.emit({ kind: 'eruptionStarted', time: this.time, volcanoId: v.info.id, cause, ventIds: v.info.vents.map((x) => x.id) });
    this.emit({ kind: 'regimeChanged', time: this.time, volcanoId: v.info.id, regime: v.regime });
  }

  private endEruption(v: MockVolcano): void {
    const vol = (this.time - v.eruptionStart) * v.eruptionRate * 0.5;
    v.erupting = false;
    v.eruptionRate = 0;
    v.regime = 'NONE';
    v.pressure = 3 + this.rand() * 2;
    v.dikes = v.dikes.filter((d) => d.active);
    this.emit({ kind: 'eruptionEnded', time: this.time, volcanoId: v.info.id, eruptedVolumeM3: vol });
    this.emit({ kind: 'regimeChanged', time: this.time, volcanoId: v.info.id, regime: 'NONE' });
  }

  private launchBombs(v: MockVolcano, n: number, speed: number): void {
    const vent = v.info.vents[0];
    for (let k = 0; k < n; k++) {
      const az = this.rand() * Math.PI * 2;
      const el = Math.PI / 2 - Math.abs(this.rand() - 0.5) * 0.9;
      const s = speed * (0.6 + this.rand() * 0.6);
      const vel: [number, number, number] = [Math.cos(az) * Math.cos(el) * s, Math.sin(az) * Math.cos(el) * s, Math.sin(el) * s];
      const start: [number, number, number] = [vent.at[0], vent.at[1], vent.z + 10];
      const flight = (2 * vel[2]) / 9.81;
      const landing: [number, number, number] = [start[0] + vel[0] * flight, start[1] + vel[1] * flight, start[2]];
      const [i, j] = this.colOf(landing[0], landing[1]);
      if (i >= 0 && j >= 0 && i < N && j < N) landing[2] = this.ground(i, j);
      this.emit({ kind: 'bombLaunched', time: this.time, volcanoId: v.info.id, id: ++this.bombId, start, velocity: vel, dragK: 0, flightSeconds: flight, landing });
    }
  }

  private stepExplosive(v: MockVolcano, dt: number): void {
    const vent = v.info.vents[0];
    const mer = v.eruptionRate * 2500;
    const top = vent.z + 2000 * Math.pow(v.eruptionRate, 0.241) * 0.5;
    if (this.rand() < dt / 20) this.emit({ kind: 'plume', time: this.time, volcanoId: v.info.id, base: [vent.at[0], vent.at[1], vent.z], topZ: top, radius: 300 + Math.sqrt(v.eruptionRate) * 20, massRateKgS: mer });
    if (this.rand() < dt / 40) this.emit({ kind: 'lightning', time: this.time, volcanoId: v.info.id, at: [vent.at[0] + (this.rand() - 0.5) * 800, vent.at[1] + (this.rand() - 0.5) * 800, vent.z + (top - vent.z) * (0.3 + 0.6 * this.rand())] });
    if (this.rand() < dt / 30) {
      this.launchBombs(v, 10, 140);
      this.emit({ kind: 'seismic', time: this.time, volcanoId: v.info.id, type: 'EXPLOSION', magnitude: 2 + this.rand(), hypocenter: [vent.at[0], vent.at[1], vent.z - 100], durationSeconds: 10, swarm: false });
    }
    // ash fall: downwind gaussian plume
    const rad = (this.wind.bearingDeg * Math.PI) / 180;
    const wx = Math.sin(rad);
    const wy = Math.cos(rad);
    const ash = this.grids[Field.AshDepth];
    const unit = this.grids[Field.TopUnit];
    const rate = v.eruptionRate * 5e-8 * dt;
    for (let j = 0; j < N; j += 1) {
      for (let i = 0; i < N; i += 1) {
        const [x, y] = this.xyOf(i, j);
        const dx = x - vent.at[0];
        const dy = y - vent.at[1];
        const along = dx * wx + dy * wy;
        if (along < -300) continue;
        const cross = -dx * wy + dy * wx;
        const sig = 250 + Math.max(0, along) * 0.25;
        const amt = rate * Math.exp(-(cross * cross) / (2 * sig * sig)) * Math.exp(-Math.max(0, along) / 4000) * (600 / sig);
        if (amt > 1e-6) {
          const k = this.idx(i, j);
          ash[k] += amt;
          if (ash[k] > 0.02) unit[k] = v.ashUnit;
        }
      }
    }
    // occasional column collapse → PDC down the steepest flank
    if (v.eruptionRate > 600 && this.rand() < dt / 300) {
      const [i, j] = this.colOf(vent.at[0], vent.at[1]);
      this.grids[Field.PdcDepth][this.idx(i, j)] += 25;
    }
  }

  private stepLava(dt: number): void {
    const depth = this.grids[Field.LavaDepth];
    const temp = this.grids[Field.LavaTemperature];
    const elev = this.grids[Field.SurfaceElevation];
    const unit = this.grids[Field.TopUnit];
    for (const v of this.volcanoes) {
      if (!v.erupting || v.explosive) continue;
      const vent = v.info.vents[0];
      const [ci, cj] = this.colOf(vent.at[0], vent.at[1]);
      const r = Math.max(1, Math.round(vent.radius / CELL / 2));
      const cells: number[] = [];
      for (let dj = -r; dj <= r; dj++) for (let di = -r; di <= r; di++) if (di * di + dj * dj <= r * r) cells.push(this.idx(ci + di, cj + dj));
      const add = (v.eruptionRate * dt) / (CELL * CELL) / cells.length;
      for (const k of cells) {
        depth[k] += add;
        temp[k] = v.temperature;
        unit[k] = v.lavaUnit;
      }
    }
    // flow: move a share of the head difference to lower neighbours (yield height 0.6 m)
    const flux = new Float32Array(N * N);
    const tsum = new Float32Array(N * N);
    const yieldH = 0.6;
    const nb = [
      [1, 0, 1],
      [-1, 0, 1],
      [0, 1, 1],
      [0, -1, 1],
      [1, 1, Math.SQRT1_2],
      [-1, 1, Math.SQRT1_2],
      [1, -1, Math.SQRT1_2],
      [-1, -1, Math.SQRT1_2],
    ];
    for (let j = 1; j < N - 1; j++) {
      for (let i = 1; i < N - 1; i++) {
        const k = j * N + i;
        const h = depth[k];
        if (h < 0.05) continue;
        const head = elev[k] + h;
        let total = 0;
        const out: [number, number][] = [];
        for (const [di, dj, w] of nb) {
          const kk = (j + dj) * N + (i + di);
          const dh = head - (elev[kk] + depth[kk]);
          if (dh > yieldH * 0.2) {
            const q = (dh - yieldH * 0.2) * w;
            out.push([kk, q]);
            total += q;
          }
        }
        if (total <= 0 || h <= yieldH) continue;
        const movable = Math.min(h - yieldH, total * 0.3) * Math.min(1, dt / 10);
        flux[k] -= movable;
        for (const [kk, q] of out) {
          const m = (movable * q) / total;
          flux[kk] += m;
          tsum[kk] += m * temp[k];
        }
      }
    }
    const lavaThick = this.lavaThick;
    for (let k = 0; k < N * N; k++) {
      if (flux[k] > 0) {
        const nd = depth[k] + flux[k];
        temp[k] = (temp[k] * depth[k] + tsum[k]) / nd;
        depth[k] = nd;
        if (unit[k] === 0 || unit[k] === 1 || this.isAshUnit(unit[k])) unit[k] = this.lavaUnitAt(k);
      } else if (flux[k] < 0) {
        depth[k] += flux[k];
      }
      if (depth[k] > 0) {
        // cooling: thin flows cool faster
        temp[k] -= (dt * (temp[k] - AMBIENT) * 0.0006) / Math.max(0.3, depth[k]);
        if (this.grids[Field.WaterDepth][k] > 0.2) temp[k] -= dt * 4;
        if (temp[k] < 800 && depth[k] > 0) {
          // solidify
          elev[k] += depth[k];
          lavaThick[k] += depth[k];
          depth[k] = 0;
          temp[k] = 0;
          if (this.grids[Field.WaterDepth][k] > 0.2) this.emit({ kind: 'oceanEntry', time: this.time, at: this.xyOf(k % N, Math.floor(k / N)), powerMW: 50 + this.rand() * 200, littoralExplosion: this.rand() < 0.1 });
        }
      }
    }
  }

  private isAshUnit(u: number): boolean {
    return this.units[u]?.depositType === 2;
  }

  private lavaUnitAt(_k: number): number {
    for (const v of this.volcanoes) if (v.erupting && !v.explosive) return v.lavaUnit;
    return 1;
  }

  private stepThermalAndWater(dt: number): void {
    const st = this.grids[Field.SurfaceTemperature];
    const elev = this.grids[Field.SurfaceElevation];
    const lava = this.grids[Field.LavaDepth];
    const lt = this.grids[Field.LavaTemperature];
    const wt = this.grids[Field.WaterTableDepth];
    const wd = this.grids[Field.WaterDepth];
    const steam = this.grids[Field.SteamFraction];
    const anomaly = this.volcanoes.map((v) => ({ v, at: v.info.vents[0].at, power: 60 + (v.pressure / v.strength) * 120 + (v.erupting ? 150 : 0) }));
    const relax = Math.min(1, dt / 1800);
    // poured water sources
    for (const s of this.waterSources) {
      if (this.time > s.until) continue;
      const [i, j] = this.colOf(s.at[0], s.at[1]);
      for (let dj = -2; dj <= 2; dj++) for (let di = -2; di <= 2; di++) {
        const ii = i + di;
        const jj = j + dj;
        if (ii < 0 || jj < 0 || ii >= N || jj >= N) continue;
        wd[this.idx(ii, jj)] += (s.rate * dt) / (25 * CELL * CELL);
      }
    }
    this.waterSources = this.waterSources.filter((s) => this.time <= s.until);
    for (let j = 0; j < N; j++) {
      for (let i = 0; i < N; i++) {
        const k = j * N + i;
        const [x, y] = this.xyOf(i, j);
        let target = AMBIENT - Math.max(0, elev[k]) * 0.0065;
        for (const a of anomaly) {
          const r2 = (x - a.at[0]) ** 2 + (y - a.at[1]) ** 2;
          target += a.power * Math.exp(-r2 / (2 * 450 * 450));
        }
        if (lava[k] > 0) target = Math.max(target, lt[k] * 0.9);
        else if (this.lavaThick[k] > 0) target += 40 * Math.min(1, this.lavaThick[k] / 5);
        st[k] += (target - st[k]) * relax;
        // water table: recharge from rain and surface water, deeper under hot ground
        const baseDepth = Math.max(-0.5, Math.min(120, 4 + Math.max(0, elev[k]) * 0.06));
        let wtTarget = baseDepth - this.rain * 0.4 + (st[k] > 95 ? (st[k] - 95) * 0.3 : 0);
        if (elev[k] <= 0) wtTarget = -wd[k];
        if (wd[k] > 0 && elev[k] > 0) {
          const inf = Math.min(wd[k], 2e-4 * dt);
          wd[k] -= inf;
          wtTarget -= inf * 400;
        }
        wt[k] += (wtTarget - wt[k]) * Math.min(1, dt / 3600);
        if (wt[k] < -0.05 && elev[k] > 0) wd[k] += Math.min(-wt[k], 0.5) * 1e-3 * dt; // spring
        if (elev[k] > 0 && this.rain > 0) wd[k] += (this.rain / 3.6e6) * dt * 0.2;
        if (elev[k] > 0 && wd[k] > 0) wd[k] = Math.max(0, wd[k] - 1e-6 * dt * (1 + Math.max(0, st[k] - 20) * 0.05));
        steam[k] = Math.min(1, Math.max(0, (st[k] - 90) / 120) * (wt[k] < 30 ? 1 : 0.4));
      }
    }
    // surface water flows downhill a little (cheap relaxation on land)
    for (let j = 1; j < N - 1; j++) {
      for (let i = 1; i < N - 1; i++) {
        const k = j * N + i;
        if (wd[k] < 0.01 || elev[k] <= 0) continue;
        let best = k;
        let bestH = elev[k] + wd[k];
        for (const kk of [k + 1, k - 1, k + N, k - N]) {
          const h = elev[kk] + wd[kk];
          if (h < bestH) {
            best = kk;
            bestH = h;
          }
        }
        if (best !== k) {
          const m = Math.min(wd[k], ((elev[k] + wd[k] - bestH) / 2) * Math.min(1, dt / 20));
          wd[k] -= m;
          wd[best] += m;
        }
      }
    }
    for (const hole of this.holes) {
      const [ci, cj] = this.colOf(hole.at[0], hole.at[1]);
      const k = this.idx(ci, cj);
      const holeFloorDepth = hole.depth;
      if (wt[k] < holeFloorDepth) wd[k] = Math.max(wd[k], (holeFloorDepth - wt[k]) * 0.5);
    }
  }

  private stepMassFlows(dt: number): void {
    const pdc = this.grids[Field.PdcDepth];
    const elev = this.grids[Field.SurfaceElevation];
    const ash = this.grids[Field.AshDepth];
    const unit = this.grids[Field.TopUnit];
    let any = false;
    const front: XY[] = [];
    const next = new Float32Array(N * N);
    for (let j = 1; j < N - 1; j++) {
      for (let i = 1; i < N - 1; i++) {
        const k = j * N + i;
        const h = pdc[k];
        if (h < 0.05) continue;
        any = true;
        // move to lowest neighbour, deposit some
        let best = k;
        let bestH = elev[k];
        for (const kk of [k + 1, k - 1, k + N, k - N, k + N + 1, k - N - 1, k + N - 1, k - N + 1]) {
          if (elev[kk] < bestH) {
            best = kk;
            bestH = elev[kk];
          }
        }
        const dep = h * Math.min(1, dt / 400);
        ash[k] += dep * 0.5;
        if (dep > 0.05) unit[k] = this.lastExplosiveUnit();
        const remain = h - dep;
        if (best === k) next[k] += remain * 0.5;
        else {
          next[best] += remain * 0.85;
          next[k] += remain * 0.15;
          if (this.rand() < 0.02) front.push(this.xyOf(best % N, Math.floor(best / N)));
        }
      }
    }
    pdc.set(next);
    if (any && front.length > 0) {
      const v = this.volcanoes.find((x) => x.explosive)!;
      this.emit({ kind: 'massFlowFront', time: this.time, volcanoId: v.info.id, flow: 'PDC', cells: front.slice(0, 64), speed: 25, temperatureC: 450 });
    }
  }

  private lastExplosiveUnit(): number {
    const v = this.volcanoes.find((x) => x.explosive)!;
    return v.lavaUnit;
  }

  private stepDeformation(): void {
    const up = this.grids[Field.Uplift];
    up.fill(0);
    for (const v of this.volcanoes) {
      const c = v.info.chamber.center;
      const d = -c[2] + v.info.vents[0].z;
      const dv = (v.pressure - 5) * 4e5; // m³, fake
      for (let j = 0; j < N; j += 1) {
        for (let i = 0; i < N; i += 1) {
          const [x, y] = this.xyOf(i, j);
          const r2 = (x - c[0]) ** 2 + (y - c[1]) ** 2;
          up[j * N + i] += (0.75 * dv * d) / (Math.PI * Math.pow(d * d + r2, 1.5));
        }
      }
    }
  }

  // ───────────── commands ─────────────

  forceEruption(volcanoId: string): boolean {
    const v = this.volcanoes.find((x) => x.info.id === volcanoId);
    if (!v) return false;
    if (!v.erupting) {
      v.pressure = v.strength;
      this.beginEruption(v, 'FORCED');
    }
    return true;
  }

  stopEruption(volcanoId: string): boolean {
    const v = this.volcanoes.find((x) => x.info.id === volcanoId);
    if (!v) return false;
    if (v.erupting) this.endEruption(v);
    return true;
  }

  forceDike(volcanoId: string): boolean {
    const v = this.volcanoes.find((x) => x.info.id === volcanoId);
    if (!v) return false;
    this.startDike(v);
    return true;
  }

  injectMagma(volcanoId: string, volume: number): boolean {
    const v = this.volcanoes.find((x) => x.info.id === volcanoId);
    if (!v) return false;
    v.pressure += volume / 2e6;
    return true;
  }

  addWater(at: XY, volume: number, seconds = 600): void {
    this.waterSources.push({ at, rate: volume / seconds, until: this.time + seconds });
  }

  dig(at: XY, radius: number, depth: number): void {
    const [ci, cj] = this.colOf(at[0], at[1]);
    const r = Math.max(1, Math.ceil(radius / CELL));
    const elev = this.grids[Field.SurfaceElevation];
    for (let dj = -r; dj <= r; dj++) {
      for (let di = -r; di <= r; di++) {
        if ((di * di + dj * dj) * CELL * CELL > radius * radius + CELL * CELL) continue;
        const i = ci + di;
        const j = cj + dj;
        if (i < 0 || j < 0 || i >= N || j >= N) continue;
        elev[this.idx(i, j)] -= depth;
      }
    }
    this.holes.push({ at, radius, depth });
    this.emit({ kind: 'message', time: this.time, text: `Dug a ${depth.toFixed(0)} m pit (r=${radius.toFixed(0)} m) at ${at.map((n) => n.toFixed(0)).join(', ')}` });
  }

  // ───────────── state, sections, snapshots ─────────────

  state(): StateMessage {
    const volcanoes: Record<string, VolcanoState> = {};
    const up = this.grids[Field.Uplift];
    for (const v of this.volcanoes) {
      const stations = STATIONS.filter((s) => (v.explosive ? s.id === 'CONE' : s.id !== 'CONE')).map((s) => {
        const [i, j] = this.colOf(s.at[0], s.at[1]);
        const u = up[this.idx(i, j)];
        const ue = up[this.idx(Math.min(N - 1, i + 1), j)] - up[this.idx(Math.max(0, i - 1), j)];
        const un = up[this.idx(i, Math.min(N - 1, j + 1))] - up[this.idx(i, Math.max(0, j - 1))];
        const c = v.info.chamber.center;
        const dx = s.at[0] - c[0];
        const dy = s.at[1] - c[1];
        const r = Math.hypot(dx, dy) || 1;
        return { id: s.id, at: s.at, east: (u * 0.4 * dx) / r, north: (u * 0.4 * dy) / r, up: u, tiltX: (ue / (2 * CELL)) * 1e6, tiltY: (un / (2 * CELL)) * 1e6 };
      });
      let maxUp = 0;
      for (const s of stations) maxUp = Math.max(maxUp, s.up);
      const style: EruptionStyle = v.explosive ? (v.eruptionRate > 800 ? 'PLINIAN' : 'VULCANIAN') : v.regime === 'OPEN_VENT' ? 'STROMBOLIAN' : 'HAWAIIAN';
      const vent = v.info.vents[0];
      volcanoes[v.info.id] = {
        chamber: {
          overpressureMPa: v.pressure,
          tensileStrengthMPa: v.strength,
          temperatureC: v.temperature,
          silicaWt: v.silica,
          waterWt: v.water,
          crystalFraction: v.crystals,
          eruptionRate: v.eruptionRate,
          regime: v.regime,
        },
        seismic: { rsam: v.rsam, vtPerMinute: v.vt, lpPerMinute: v.lp, tremor: v.tremor, swarm: v.dikes.some((d) => d.active) },
        alert: { level: v.alert, style },
        deformation: { maxUpliftM: maxUp, stations },
        plume: v.erupting && v.explosive ? { topZ: vent.z + 2000 * Math.pow(v.eruptionRate, 0.241) * 0.5, massRateKgS: v.eruptionRate * 2500 } : undefined,
      };
    }
    return { type: 'state', time: this.time, volcanoes, world: { rainMmPerHour: this.rain, wind: this.wind } };
  }

  /** Samples a vertical cross-section along a polyline. */
  section(requestId: number, polyline: XY[], zMin: number, zMax: number, nu: number, nz: number) {
    const segLen: number[] = [];
    let length = 0;
    for (let s = 1; s < polyline.length; s++) {
      const l = Math.hypot(polyline[s][0] - polyline[s - 1][0], polyline[s][1] - polyline[s - 1][1]);
      segLen.push(l);
      length += l;
    }
    const pointAt = (d: number): XY => {
      let acc = 0;
      for (let s = 0; s < segLen.length; s++) {
        if (d <= acc + segLen[s] || s === segLen.length - 1) {
          const t = segLen[s] > 0 ? (d - acc) / segLen[s] : 0;
          const a = polyline[s];
          const b = polyline[s + 1];
          return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
        }
        acc += segLen[s];
      }
      return polyline[0];
    };
    const px = nu * nz;
    const material = new Uint8Array(px);
    const unit = new Uint16Array(px);
    const temperatureC = new Float32Array(px);
    const saturation = new Float32Array(px);
    const steam = new Float32Array(px);
    const flags = new Uint8Array(px);
    const surfaceZ = new Float32Array(nu);
    const waterTableZ = new Float32Array(nu);
    const overlays: SectionOverlay[] = [];
    const g = this.grids;
    const at: XY[] = [];
    for (let i = 0; i < nu; i++) {
      const p = pointAt((i + 0.5) * (length / nu));
      at.push(p);
      const [ci, cj] = this.colOf(p[0], p[1]);
      const ii = Math.min(N - 1, Math.max(0, ci));
      const jj = Math.min(N - 1, Math.max(0, cj));
      const k = this.idx(ii, jj);
      const ground = g[Field.SurfaceElevation][k];
      surfaceZ[i] = ground + g[Field.LavaDepth][k];
      const wtd = g[Field.WaterTableDepth][k];
      waterTableZ[i] = ground - wtd;
      const base = this.base[k];
      const lavaTop = base + this.lavaThick[k];
      const water = g[Field.WaterDepth][k];
      for (let kz = 0; kz < nz; kz++) {
        const z = zMin + ((kz + 0.5) / nz) * (zMax - zMin);
        const q = kz * nu + i;
        // temperature: geotherm + chamber halo + surface temperature near top
        let T = g[Field.SurfaceTemperature][k] + Math.max(0, ground - z) * 0.03;
        for (const v of this.volcanoes) {
          const c = v.info.chamber.center;
          const r = Math.hypot(p[0] - c[0], p[1] - c[1], (z - c[2]) * 1.8);
          const R = v.info.chamber.radius;
          if (r < R) {
            T = Math.max(T, v.temperature);
          } else T += (v.temperature - 300) * (R / r) ** 2 * 0.5;
        }
        if (z > surfaceZ[i]) {
          if (z <= ground + water && water > 0) {
            material[q] = 7;
            flags[q] = SectionFlag.WaterBody;
            saturation[q] = 1;
            T = 15;
          } else {
            material[q] = 0;
            flags[q] = SectionFlag.Air;
            T = AMBIENT;
          }
        } else if (z > ground) {
          material[q] = 6;
          flags[q] = SectionFlag.Magma;
          unit[q] = g[Field.TopUnit][k];
          T = g[Field.LavaTemperature][k];
        } else {
          if (z > lavaTop) {
            material[q] = 5;
            unit[q] = g[Field.TopUnit][k];
          } else if (z > base) {
            material[q] = 4;
            unit[q] = g[Field.TopUnit][k] || 1;
          } else if (z > -300) {
            const band = Math.floor((base - z) / 70);
            material[q] = band % 3 === 2 ? 3 : 2;
            unit[q] = 1;
          } else {
            material[q] = 1;
            unit[q] = 0;
          }
          const below = z < waterTableZ[i];
          saturation[q] = below ? 1 : 0.15;
          if (below && T > 100 + (waterTableZ[i] - z) * 0.08) steam[q] = Math.min(1, (T - 100) / 150);
        }
        temperatureC[q] = T;
      }
    }
    // a lava tube under the main volcano's east flank, for demonstration
    for (let i = 0; i < nu; i++) {
      const p = at[i];
      const d = Math.abs(p[1] - 0) < 300 && p[0] > 600 && p[0] < 1800;
      if (!d) continue;
      for (let kz = 0; kz < nz; kz++) {
        const z = zMin + ((kz + 0.5) / nz) * (zMax - zMin);
        const q = kz * nu + i;
        if (z < surfaceZ[i] - 8 && z > surfaceZ[i] - 18) {
          flags[q] |= SectionFlag.Void;
          material[q] = 9;
        }
      }
    }
    // overlays: chambers and conduits projected onto the section
    for (const v of this.volcanoes) {
      const c = v.info.chamber.center;
      let bestU = -1;
      let bestD = Infinity;
      for (let i = 0; i < nu; i++) {
        const d = Math.hypot(at[i][0] - c[0], at[i][1] - c[1]);
        if (d < bestD) {
          bestD = d;
          bestU = ((i + 0.5) / nu) * length;
        }
      }
      if (bestD < v.info.chamber.radius * 2) {
        overlays.push({ kind: 'chamber', u: bestU, z: c[2], rx: v.info.chamber.radius, rz: v.info.chamber.radius / 1.8, temperatureC: v.temperature });
        overlays.push({ kind: 'conduit', u: bestU, zTop: v.info.vents[0].z, zBottom: c[2] + v.info.chamber.radius / 1.8, width: 30, active: v.erupting });
      }
      for (const dk of v.dikes) {
        const pts: [number, number][] = [];
        for (const pp of dk.path) {
          let bu = 0;
          let bd = Infinity;
          for (let i = 0; i < nu; i++) {
            const d = Math.hypot(at[i][0] - pp[0], at[i][1] - pp[1]);
            if (d < bd) {
              bd = d;
              bu = ((i + 0.5) / nu) * length;
            }
          }
          if (bd < 1500) pts.push([bu, pp[2]]);
        }
        if (pts.length > 1) overlays.push({ kind: 'dike', points: pts, active: dk.active });
      }
    }
    const usedUnits = new Set<number>(unit);
    const meta: SectionMeta = { requestId, length, zMin, zMax, units: this.units.filter((u) => usedUnits.has(u.id)), overlays };
    return { meta, nu, nz, time: this.time, surfaceZ, waterTableZ, material, unit, temperatureC, saturation, steam, flags };
  }

  snapshot(): Snapshot {
    const grids: Record<number, Float32Array> = {};
    for (const [f, a] of Object.entries(this.grids)) grids[Number(f)] = a.slice();
    return { time: this.time, grids, base: this.base.slice(), lavaThick: this.lavaThick.slice(), volcanoes: JSON.stringify(this.volcanoes) };
  }

  restore(s: Snapshot): void {
    this.time = s.time;
    for (const [f, a] of Object.entries(s.grids)) (this.grids as unknown as Record<number, Float32Array>)[Number(f)].set(a);
    this.base.set(s.base);
    this.lavaThick.set(s.lavaThick);
    const vs = JSON.parse(s.volcanoes) as MockVolcano[];
    vs.forEach((v, i) => Object.assign(this.volcanoes[i], v));
    this.pendingEvents = [];
  }

  tileValues(field: FieldId, tx: number, ty: number): Float32Array {
    const src = (this.grids as unknown as Record<number, Float32Array>)[field];
    const out = new Float32Array(TILE * TILE);
    for (let j = 0; j < TILE; j++) out.set(src.subarray((ty * TILE + j) * N + tx * TILE, (ty * TILE + j) * N + tx * TILE + TILE), j * TILE);
    return out;
  }
}
