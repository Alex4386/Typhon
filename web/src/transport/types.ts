export interface ApiResponse<T = unknown> {
  status: number;
  data: T;
  headers: Record<string, string>;
}

export interface VersionData {
  plugin: string;
  version: string;
}

export interface HealthData {
  status: string;
}

export interface AuthData {
  authenticated: boolean;
  authConfigured: boolean;
}

export interface TpsData {
  tps1m: number;
  tps5m: number;
  tps15m: number;
  mspt: number;
}

export interface LocationData {
  world: string | null;
  x: number;
  y: number;
  z: number;
}

export interface VentSummary {
  name: string;
  type: string;
  status: string;
  style: string;
  enabled: boolean;
}

export interface VentDetail extends VentSummary {
  location: LocationData;
  craterRadius: number;
  silicateLevel: number;
  fissureAngle: number;
  calderaRadius: number;
  isCaldera: boolean;
  // Geometry for visualization
  summitY: number;
  summitBlock?: LocationData;
  baseY: number;
  seaLevel: number;
  averageVentHeight: number;
  bombMaxDistance: number;
  statusScaleFactor: number;
  // Runtime eruption state (also in VentMetrics, included here for initial load)
  isErupting: boolean;
  isFlowingLava: boolean;
  isExploding: boolean;
  longestNormalLavaFlowLength: number;
  currentNormalLavaFlowLength: number;
  currentFlowLength: number;
  longestFlowLength: number;
  longestAshFlowLength: number;
  currentAshFlowLength: number;
  currentEjecta: number;
  totalEjecta: number;
  ejectaPerSecond: number;
  plumbedBlocksPerSecond: number;
  successfulPlumbsPerSecond: number;
  normalFlowEndBlocks: number;
  pillowFlowEndBlocks: number;
  underfillTargets: number;
}

/** Lightweight metrics returned by GET /vents/:vent/metrics — poll-friendly */
export interface VentMetrics {
  status: string;
  statusScaleFactor: number;
  isErupting: boolean;
  isFlowingLava: boolean;
  isExploding: boolean;
  currentNormalLavaFlowLength: number;
  longestNormalLavaFlowLength: number;
  currentFlowLength: number;
  longestFlowLength: number;
  currentAshFlowLength: number;
  longestAshFlowLength: number;
  currentEjecta: number;
  totalEjecta: number;
  ejectaPerSecond: number;
  lavaFlowsPerSecond: number;
  activeLavaBlocks: number;
  terminalLavaBlocks: number;
  plumbedBlocksPerSecond: number;
  successfulPlumbsPerSecond: number;
  normalFlowEndBlocks: number;
  pillowFlowEndBlocks: number;
  underfillTargets: number;
  bombsPerSecond: number;
  activeBombs: number;
  maxActiveBombs: number;
  bombMaxDistance: number;
}

export interface VolcanoSummary {
  name: string;
  location: LocationData;
  status: string;
  style: string;
  ventCount: number;
}

export interface VolcanoDetail extends VolcanoSummary {
  silicateLevel: number;
  vents: VentSummary[];
}

export interface SettingsData {
  blueMap: {
    publicUrl: string | null;
  };
}

export interface EjectaRecordMetadata {
  summit: { x: number; y: number; z: number };
  baseY: number;
  silicateLevel: number;
  gasContent: number;
  eruptionStyle: string;
  ventType: string;
  craterRadius: number;
  longestFlowLength: number;
  longestNormalLavaFlowLength: number;
  currentFlowLength: number;
  currentNormalLavaFlowLength: number;
}

export interface EjectaRecord {
  startTime: number;
  endTime: number;
  ejectaVolume: number;
  metadata?: EjectaRecordMetadata;
}

export interface VentRecordData {
  currentEjecta: number;
  totalEjecta: number;
  startEjectaTracking: number;
  records: EjectaRecord[];
}

export interface BuilderData {
  enabled: boolean;
  type: string | null;
  args: Record<string, string> | null;
}

export interface ConfigNode {
  key: string;
  type: 'int' | 'float' | 'double' | 'boolean' | 'enum' | 'string';
  value: number | boolean | string;
  min?: number;
  max?: number;
  options?: string[];
}

export interface ApiClient {
  get<T = unknown>(url: string, config?: RequestConfig): Promise<ApiResponse<T>>;
  post<T = unknown>(url: string, data?: unknown, config?: RequestConfig): Promise<ApiResponse<T>>;
  put<T = unknown>(url: string, data?: unknown, config?: RequestConfig): Promise<ApiResponse<T>>;
  patch<T = unknown>(url: string, data?: unknown, config?: RequestConfig): Promise<ApiResponse<T>>;
  delete<T = unknown>(url: string, config?: RequestConfig): Promise<ApiResponse<T>>;
}

export interface RequestConfig {
  params?: Record<string, string>;
}
