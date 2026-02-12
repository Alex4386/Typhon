import { useCallback, useLayoutEffect, useMemo, useRef, useState } from 'react';
import type { VentDetail } from '@/transport/types';

interface Props {
  vent: VentDetail;
}

// ── Color helpers ────────────────────────────────────────────────────────────

function getMountainColors(silicate: number) {
  if (silicate < 0.5) return { base: '#2e2418', mid: '#3d3229', top: '#4a3c30', stroke: '#5a4a3a' }; // basalt
  if (silicate <= 0.65) return { base: '#3a3a3a', mid: '#555555', top: '#6a6a6a', stroke: '#7a7a7a' }; // andesite
  return { base: '#5a5a5a', mid: '#757575', top: '#909090', stroke: '#a0a0a0' }; // rhyolite
}

function getLavaColor(silicate: number): string {
  if (silicate < 0.5) return '#ff4500';
  if (silicate <= 0.65) return '#d4691a';
  return '#8b3a3a';
}

function getLavaGlow(silicate: number): string {
  if (silicate < 0.5) return '#ff6a00';
  if (silicate <= 0.65) return '#e88830';
  return '#a04848';
}

// ── Constants ────────────────────────────────────────────────────────────────

const DEFAULT_SVG_W = 800;
const SVG_H = 420;
const GROUND_Y = 330;
const TOP_PAD = 30;
const MARGIN_X = 50;
const LABEL_PAD = 30;

// ── Geometry tuning ──────────────────────────────────────────────────────────
// Slope bezier control point — pulls curve toward base/ground for concave profile
const SLOPE_CP_X = 0.7;   // fraction of flowPxR from center (1 = at base edge)
const SLOPE_CP_Y = 0.75;   // fraction from rim to ground (1 = at ground level)

// Crater / caldera shape
const MAX_CRATER_DEPTH_FRAC = 0.4;
const CRATER_BOWL_CP_Y = 0.3;     // bezier control offset for crater bowl
const CALDERA_DEPTH_MULT = 1.5;   // caldera floor depth relative to crater depth
const CALDERA_WALL_CP_X = 0.15;   // inner wall bezier X offset
const CALDERA_WALL_CP_Y = 0.3;    // inner wall bezier Y offset
const CALDERA_FLOOR_W = 0.4;      // caldera floor half-width as fraction of caldera radius

// Flow & extent scaling
const MIN_FLOW_CRATER_MULT = 3;   // minimum flow length as multiple of crater radius
const MIN_FLOW_LENGTH = 20;       // absolute minimum flow length (world units)
const MIN_EXTENT_CRATER_MULT = 3; // minimum max extent as multiple of crater radius
const MAX_VSCALE_RATIO = 1.5;     // max vertical scale relative to horizontal

// ── Geometry computation ─────────────────────────────────────────────────────

interface Geo {
  svgW: number;
  centerX: number;
  summitY: number;
  baseY: number;
  seaLevel: number;
  height: number;
  scaleH: number;
  scaleV: number;
  summitPx: number;
  craterPxR: number;
  calderaPxR: number;
  flowPxR: number;
  bombPxR: number;
  currentFlowPxR: number;
  isCaldera: boolean;
  craterRadius: number;
  calderaRadius: number;
  flowLength: number;
  rawFlowLength: number;
  currentFlowLength: number;
  bombMax: number;
  seaLevelPxY: number | null;
  craterDepthPx: number;
  avgVentPx: number;
}

function computeGeo(vent: VentDetail, svgW: number): Geo {
  const centerX = svgW / 2;
  const summitY = vent.summitY ?? vent.baseY;
  const baseY = vent.baseY;
  const seaLevel = vent.seaLevel ?? 63;
  const height = Math.max(summitY - baseY, 1);
  const craterRadius = Math.max(vent.craterRadius || 3, 1);
  const calderaRadius = vent.calderaRadius || 0;
  const isCaldera = vent.isCaldera && calderaRadius > 0;
  // Flow distance is measured from crater rim; add craterRadius to get basin radius from center
  const rawFlowLength = vent.longestFlowLength || 0;
  const currentFlowLength = vent.currentFlowLength || 0;
  const flowLength = Math.max(rawFlowLength + craterRadius, craterRadius * MIN_FLOW_CRATER_MULT, MIN_FLOW_LENGTH);
  const bombMax = vent.bombMaxDistance || 0;
  const avgVentHeight = vent.averageVentHeight ?? summitY;

  // Everything is a radius from center. Find the largest radius we need to display.
  const maxExtent = Math.max(flowLength, bombMax, craterRadius * MIN_EXTENT_CRATER_MULT);
  // Always compute scale against the reference width so the cone keeps
  // its original aspect ratio regardless of how wide the viewBox gets.
  const drawableHalfW = (DEFAULT_SVG_W - MARGIN_X * 2) / 2;
  const scaleH = drawableHalfW / maxExtent;
  const scaleV = Math.min((GROUND_Y - TOP_PAD) / height, scaleH * MAX_VSCALE_RATIO);

  const summitPx = GROUND_Y - height * scaleV;
  const craterPxR = craterRadius * scaleH;
  const calderaPxR = calderaRadius * scaleH;
  const flowPxR = flowLength * scaleH;
  const bombPxR = bombMax * scaleH;
  const currentFlowPxR = currentFlowLength * scaleH;
  const avgVentPx = GROUND_Y - (avgVentHeight - baseY) * scaleV;

  // Crater depth — the bowl sinks below summit proportional to crater radius vs height
  const craterDepthFrac = Math.min(craterRadius / Math.max(height, 1), MAX_CRATER_DEPTH_FRAC);
  const craterDepthPx = (GROUND_Y - summitPx) * craterDepthFrac;

  const seaLevelDelta = seaLevel - baseY;
  const seaLevelRawPxY = GROUND_Y - seaLevelDelta * scaleV;
  const seaLevelPxY =
    seaLevelRawPxY > TOP_PAD && seaLevelRawPxY < GROUND_Y
      ? seaLevelRawPxY
      : null;

  return {
    svgW, centerX,
    summitY, baseY, seaLevel, height, scaleH, scaleV,
    summitPx, craterPxR, calderaPxR, flowPxR, bombPxR, currentFlowPxR,
    isCaldera, craterRadius, calderaRadius,
    flowLength, rawFlowLength, currentFlowLength, bombMax,
    seaLevelPxY, craterDepthPx, avgVentPx,
  };
}

// ── Slope point evaluation ──────────────────────────────────────────────────
// Evaluate right-side slope bezier at parameter t (0 = rim, 1 = base).
// Slope bezier: P0 = rim, P1 = control, P2 = base
function slopePointAt(geo: Geo, t: number): { x: number; y: number } {
  const { centerX, craterPxR, flowPxR, summitPx } = geo;
  const rimX = centerX + craterPxR;
  const rimY = summitPx;
  const baseX = centerX + flowPxR;
  const cpX = centerX + flowPxR * SLOPE_CP_X;
  const cpY = rimY + (GROUND_Y - rimY) * SLOPE_CP_Y;
  const u = 1 - t;
  return {
    x: u * u * rimX + 2 * u * t * cpX + t * t * baseX,
    y: u * u * rimY + 2 * u * t * cpY + t * t * GROUND_Y,
  };
}

// ── Mountain profile path (SVG path d) ──────────────────────────────────────

function buildProfilePath(geo: Geo): string {
  const {
    centerX, summitPx, craterPxR, calderaPxR, flowPxR, isCaldera, craterDepthPx,
  } = geo;

  // Rim Y = top of the mountain slope (summit line in pixel space)
  const rimY = summitPx;
  // Crater floor sits below the rim
  const craterFloorY = rimY + craterDepthPx;

  // We build left-to-right: left base -> left slope -> crater/caldera -> right slope -> right base -> close
  const parts: string[] = [];

  // Start at left base
  parts.push(`M ${centerX - flowPxR} ${GROUND_Y}`);

  // Left slope: concave curve rising from base to rim
  // Control point near ground & base edge → gentle slope at base, steep near summit
  const leftRimX = centerX - craterPxR;
  const slopeMidX_L = centerX - flowPxR * SLOPE_CP_X;
  const slopeMidY = rimY + (GROUND_Y - rimY) * SLOPE_CP_Y;
  parts.push(`Q ${slopeMidX_L} ${slopeMidY} ${leftRimX} ${rimY}`);

  if (isCaldera && calderaPxR > craterPxR) {
    // Caldera shape: rim -> outer rim at caldera edge -> deep floor -> mirror
    const outerRimL = centerX - calderaPxR;
    const outerRimR = centerX + calderaPxR;
    const calderaFloor = rimY + craterDepthPx * CALDERA_DEPTH_MULT;

    // Left inner wall: from left crater rim down to caldera floor
    parts.push(`L ${outerRimL} ${rimY}`);
    parts.push(`Q ${outerRimL + calderaPxR * CALDERA_WALL_CP_X} ${calderaFloor - craterDepthPx * CALDERA_WALL_CP_Y} ${centerX - calderaPxR * CALDERA_FLOOR_W} ${calderaFloor}`);
    // Caldera floor
    parts.push(`L ${centerX + calderaPxR * CALDERA_FLOOR_W} ${calderaFloor}`);
    // Right inner wall back up
    parts.push(`Q ${outerRimR - calderaPxR * CALDERA_WALL_CP_X} ${calderaFloor - craterDepthPx * CALDERA_WALL_CP_Y} ${outerRimR} ${rimY}`);
    // Continue to right crater rim
    parts.push(`L ${centerX + craterPxR} ${rimY}`);
  } else {
    // Normal crater bowl: left rim -> dip down into crater -> right rim
    // Smooth parabolic bowl using a quadratic bezier
    parts.push(`Q ${centerX} ${craterFloorY + craterDepthPx * CRATER_BOWL_CP_Y} ${centerX + craterPxR} ${rimY}`);
  }

  // Right slope: mirror of left (concave curve from rim down to base)
  const rightRimX = centerX + craterPxR;
  const slopeMidX_R = centerX + flowPxR * SLOPE_CP_X;
  parts.push(`Q ${slopeMidX_R} ${slopeMidY} ${centerX + flowPxR} ${GROUND_Y}`);

  // Close path along ground
  parts.push('Z');

  return parts.join(' ');
}

// ── Raw SVG component (no card wrapper) ─────────────────────────────────────

export function VolcanoCrossSectionRaw({ vent }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [svgW, setSvgW] = useState(DEFAULT_SVG_W);

  const updateWidth = useCallback(() => {
    if (containerRef.current) {
      const w = containerRef.current.clientWidth;
      if (w > 0) {
        // Convert pixel width to viewBox units: keep height fixed at SVG_H,
        // scale width so 1 viewBox unit = 1 pixel at rendered height
        const renderedH = Math.min(w * SVG_H / DEFAULT_SVG_W, SVG_H);
        const viewBoxW = w * SVG_H / renderedH;
        setSvgW(viewBoxW);
      }
    }
  }, []);

  useLayoutEffect(() => {
    updateWidth();
    const el = containerRef.current;
    if (!el) return;
    const ro = new ResizeObserver(updateWidth);
    ro.observe(el);
    return () => ro.disconnect();
  }, [updateWidth]);

  const geo = useMemo(() => computeGeo(vent, svgW), [vent, svgW]);
  const { centerX } = geo;

  const silicate = vent.silicateLevel ?? 0.5;
  const mtColors = getMountainColors(silicate);
  const lavaColor = getLavaColor(silicate);
  const lavaGlow = getLavaGlow(silicate);

  const scaleFactor = vent.statusScaleFactor ?? 0;
  // Only ERUPTION_IMMINENT (0.9) and ERUPTING (1.0) show eruption effects
  const isErupting = scaleFactor >= 0.9;
  // MINOR_ACTIVITY (0.3), MAJOR_ACTIVITY (0.7) show fumarole/geothermal activity
  const hasActivity = scaleFactor >= 0.3;
  const intensity = scaleFactor;
  const style = vent.style || '';

  const profilePath = useMemo(() => buildProfilePath(geo), [geo]);

  const uid = vent.name.replace(/[^a-zA-Z0-9]/g, '_');

  return (
    <div ref={containerRef}>
      <svg
        viewBox={`0 0 ${svgW} ${SVG_H}`}
        className="w-full"
        style={{ maxHeight: SVG_H }}
      >
        <defs>
          {/* Mountain gradient */}
          <linearGradient id={`mt-${uid}`} x1="0" y1="1" x2="0" y2="0">
            <stop offset="0%" stopColor={mtColors.base} />
            <stop offset="40%" stopColor={mtColors.mid} />
            <stop offset="100%" stopColor={mtColors.top} />
          </linearGradient>

          {/* Lava flow radial: bright at center, fading outward */}
          <radialGradient id={`lava-r-${uid}`} cx="50%" cy="50%" r="50%">
            <stop offset="0%" stopColor={lavaColor} stopOpacity="0.6" />
            <stop offset="60%" stopColor={lavaColor} stopOpacity="0.35" />
            <stop offset="100%" stopColor={lavaColor} stopOpacity="0.05" />
          </radialGradient>

          {/* Ground texture gradient */}
          <linearGradient id={`ground-${uid}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#3a2a1a" />
            <stop offset="100%" stopColor="#1a1008" />
          </linearGradient>

          {/* Clip to crater opening (everything EXCEPT mountain body) */}
          <clipPath id={`crater-clip-${uid}`}>
            <path
              d={`M 0 0 H ${svgW} V ${SVG_H} H 0 Z ${profilePath}`}
              clipRule="evenodd"
            />
          </clipPath>

          {/* Clip to crater bowl interior only (for glow — stays below rim line) */}
          <clipPath id={`glow-clip-${uid}`}>
            <path d={(() => {
              const rimY = geo.summitPx;
              const craterFloorY = rimY + geo.craterDepthPx;
              if (geo.isCaldera && geo.calderaPxR > geo.craterPxR) {
                const oL = centerX - geo.calderaPxR;
                const oR = centerX + geo.calderaPxR;
                const cFloor = rimY + geo.craterDepthPx * CALDERA_DEPTH_MULT;
                return `M ${oL} ${rimY} Q ${oL + geo.calderaPxR * CALDERA_WALL_CP_X} ${cFloor - geo.craterDepthPx * CALDERA_WALL_CP_Y} ${centerX - geo.calderaPxR * CALDERA_FLOOR_W} ${cFloor} L ${centerX + geo.calderaPxR * CALDERA_FLOOR_W} ${cFloor} Q ${oR - geo.calderaPxR * CALDERA_WALL_CP_X} ${cFloor - geo.craterDepthPx * CALDERA_WALL_CP_Y} ${oR} ${rimY} Z`;
              }
              return `M ${centerX - geo.craterPxR} ${rimY} Q ${centerX} ${craterFloorY + geo.craterDepthPx * CRATER_BOWL_CP_Y} ${centerX + geo.craterPxR} ${rimY} Z`;
            })()} />
          </clipPath>

          {/* Clip TO mountain body (for lava flow overlay) */}
          <clipPath id={`mt-clip-${uid}`}>
            <path d={profilePath} />
          </clipPath>

          {/* Clip for underwater fill */}
          {geo.seaLevelPxY != null && (
            <clipPath id={`sea-clip-${uid}`}>
              <rect x="0" y={geo.seaLevelPxY} width={svgW} height={GROUND_Y - geo.seaLevelPxY} />
            </clipPath>
          )}
        </defs>

        {/* ── 1. Background ──────────────────────────────────────────── */}
        {/* Sky */}
        <rect x="0" y="0" width={svgW} height={GROUND_Y} fill="#0d1117" opacity="0.4" />

        {/* Ground */}
        <rect x="0" y={GROUND_Y} width={svgW} height={SVG_H - GROUND_Y} fill={`url(#ground-${uid})`} />
        {/* Ground surface line */}
        <line x1="0" y1={GROUND_Y} x2={svgW} y2={GROUND_Y} stroke="#4a3a28" strokeWidth="1" />

        {/* ── 2. Sea level water body (behind mountain) ─────────────── */}
        {geo.seaLevelPxY != null && (
          <rect
            x="0" y={geo.seaLevelPxY}
            width={svgW} height={GROUND_Y - geo.seaLevelPxY}
            fill="#1a4a6e" opacity="0.2"
          />
        )}

        {/* ── 3. Lava flow ground layer (behind mountain base) ─────── */}
        {geo.flowPxR > 0 && (
          <g>
            <ellipse
              cx={centerX} cy={GROUND_Y}
              rx={geo.flowPxR} ry={6}
              fill={`url(#lava-r-${uid})`}
            />
            <rect
              x={centerX - geo.flowPxR}
              y={GROUND_Y - 2}
              width={geo.flowPxR * 2}
              height={4}
              rx={2}
              fill={lavaColor}
              opacity="0.25"
            />
          </g>
        )}

        {/* ── 4. Mountain body ─────────────────────────────────────── */}
        <path
          d={profilePath}
          fill={`url(#mt-${uid})`}
          stroke={mtColors.stroke}
          strokeWidth="1.2"
          strokeLinejoin="round"
        />
        {/* Subtle inner slope shading */}
        <path
          d={profilePath}
          fill="none"
          stroke="rgba(255,255,255,0.06)"
          strokeWidth="3"
          strokeLinejoin="round"
        />

        {/* ── 6. Sea level line & label (on top of mountain) ────────── */}
        {geo.seaLevelPxY != null && (
          <>
            <line
              x1="0" y1={geo.seaLevelPxY}
              x2={svgW} y2={geo.seaLevelPxY}
              stroke="#4a9eda" strokeWidth="1" strokeDasharray="10 5" opacity="0.7"
            />
            <text
              x={svgW - 8} y={geo.seaLevelPxY - 5}
              fill="#4a9eda" fontSize="9" fontFamily="monospace" textAnchor="end" opacity="0.8"
            >
              Sea Level Y={geo.seaLevel}
            </text>
          </>
        )}

        {/* ── 6b. Lava flow on slope ──────────────────────────────── */}
        {geo.currentFlowPxR > 0 && geo.flowPxR > 0 && (() => {
          // Draw lava overlay following the slope from crater rim to currentFlowLength
          const slopeLen = geo.flowPxR - geo.craterPxR; // total slope px
          const flowFromRimPx = Math.max(geo.currentFlowPxR - geo.craterPxR, 0);
          const flowFrac = Math.min(slopeLen > 0 ? flowFromRimPx / slopeLen : 0, 1);
          const tEnd = flowFrac;
          if (tEnd <= 0) return null;
          // Build forward and return paths with offset to create a thick band
          const steps = 24;
          const fwd: { x: number; y: number }[] = [];
          const rev: { x: number; y: number }[] = [];
          for (let i = 0; i <= steps; i++) {
            const t = tEnd * (i / steps);
            const p = slopePointAt(geo, t);
            // Normal direction: perpendicular to slope tangent
            const dt = 0.01;
            const pn = slopePointAt(geo, Math.min(t + dt, 1));
            const dx = pn.x - p.x, dy = pn.y - p.y;
            const len = Math.sqrt(dx * dx + dy * dy) || 1;
            const nx = -dy / len, ny = dx / len;
            // Thickness tapers from 4px at rim to 2px at tip
            const thick = 4 - 2 * (i / steps);
            fwd.push({ x: p.x + nx * thick, y: p.y + ny * thick });
            rev.unshift({ x: p.x - nx * thick, y: p.y - ny * thick });
          }
          const pts = [...fwd, ...rev];
          const d = pts.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x} ${p.y}`).join(' ') + ' Z';
          // Mirror for left side
          const fwdL: { x: number; y: number }[] = [];
          const revL: { x: number; y: number }[] = [];
          for (let i = 0; i <= steps; i++) {
            const t = tEnd * (i / steps);
            const p = slopePointAt(geo, t);
            const mirrorX = 2 * centerX - p.x;
            const dt = 0.01;
            const pn = slopePointAt(geo, Math.min(t + dt, 1));
            const dx = pn.x - p.x, dy = pn.y - p.y;
            const len = Math.sqrt(dx * dx + dy * dy) || 1;
            const nx = dy / len, ny = dx / len; // flipped normal for left side
            const thick = 4 - 2 * (i / steps);
            fwdL.push({ x: mirrorX + nx * thick, y: p.y + ny * thick });
            revL.unshift({ x: mirrorX - nx * thick, y: p.y - ny * thick });
          }
          const ptsL = [...fwdL, ...revL];
          const dL = ptsL.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x} ${p.y}`).join(' ') + ' Z';
          // Gradient animation for flowing lava effect
          const rimPt = slopePointAt(geo, 0);
          const tipPt = slopePointAt(geo, tEnd);
          const gdx = tipPt.x - rimPt.x;
          const gdy = tipPt.y - rimPt.y;
          const slopePxLen = Math.sqrt(gdx * gdx + gdy * gdy) || 1;
          const tileLen = slopePxLen / 4;
          const ndx = gdx / slopePxLen;
          const ndy = gdy / slopePxLen;
          // Right gradient endpoints (one tile length)
          const gx1R = rimPt.x, gy1R = rimPt.y;
          const gx2R = rimPt.x + ndx * tileLen, gy2R = rimPt.y + ndy * tileLen;
          const shiftX = ndx * tileLen, shiftY = ndy * tileLen;
          // Left gradient endpoints (mirrored)
          const gx1L = 2 * centerX - rimPt.x, gy1L = rimPt.y;
          const gx2L = gx1L - ndx * tileLen, gy2L = rimPt.y + ndy * tileLen;

          return (
            <g>
              <defs>
                <linearGradient
                  id={`lava-slope-R-${uid}`}
                  gradientUnits="userSpaceOnUse"
                  spreadMethod="repeat"
                  x1={gx1R} y1={gy1R} x2={gx2R} y2={gy2R}
                >
                  <stop offset="0" stopColor="#ff6a00" />
                  <stop offset="0.35" stopColor="#ff4500" />
                  <stop offset="0.7" stopColor="#991a00" />
                  <stop offset="1" stopColor="#ff6a00" />
                  <animate attributeName="x1" values={`${gx1R};${gx1R + shiftX}`} dur="2s" repeatCount="indefinite" />
                  <animate attributeName="y1" values={`${gy1R};${gy1R + shiftY}`} dur="2s" repeatCount="indefinite" />
                  <animate attributeName="x2" values={`${gx2R};${gx2R + shiftX}`} dur="2s" repeatCount="indefinite" />
                  <animate attributeName="y2" values={`${gy2R};${gy2R + shiftY}`} dur="2s" repeatCount="indefinite" />
                </linearGradient>
                <linearGradient
                  id={`lava-slope-L-${uid}`}
                  gradientUnits="userSpaceOnUse"
                  spreadMethod="repeat"
                  x1={gx1L} y1={gy1L} x2={gx2L} y2={gy2L}
                >
                  <stop offset="0" stopColor="#ff6a00" />
                  <stop offset="0.35" stopColor="#ff4500" />
                  <stop offset="0.7" stopColor="#991a00" />
                  <stop offset="1" stopColor="#ff6a00" />
                  <animate attributeName="x1" values={`${gx1L};${gx1L - shiftX}`} dur="2s" repeatCount="indefinite" />
                  <animate attributeName="y1" values={`${gy1L};${gy1L + shiftY}`} dur="2s" repeatCount="indefinite" />
                  <animate attributeName="x2" values={`${gx2L};${gx2L - shiftX}`} dur="2s" repeatCount="indefinite" />
                  <animate attributeName="y2" values={`${gy2L};${gy2L + shiftY}`} dur="2s" repeatCount="indefinite" />
                </linearGradient>
              </defs>
              <g clipPath={`url(#mt-clip-${uid})`}>
                <path d={d} fill={`url(#lava-slope-R-${uid})`} />
                <path d={dL} fill={`url(#lava-slope-L-${uid})`} />
              </g>
            </g>
          );
        })()}

        {/* ── 7. Bomb range trajectories (parabolic from crater) ──── */}
        {geo.bombPxR > 0 && (() => {
          // Launch point: at the crater rim (summit level)
          const launchY = geo.summitPx;
          // Landing: bombMax is distance from vent center, slope starts at craterRadius from center
          const landDistFromRim = Math.max(geo.bombMax - geo.craterRadius, 0);
          const slopeLenWorld = geo.flowLength - geo.craterRadius;
          const landFrac = slopeLenWorld > 0 ? Math.min(landDistFromRim / slopeLenWorld, 1) : 0;
          const landPt = slopePointAt(geo, landFrac);
          const landPtL = { x: 2 * centerX - landPt.x, y: landPt.y };
          // Peak: bombs launch upward from crater — peak well above summit
          // For a quadratic bezier, the actual curve peak at t=0.5 is:
          //   y_mid = 0.25*P0y + 0.5*CPy + 0.25*P2y
          // We want y_mid to be above summit by a good margin.
          // Solve for CPy: CPy = (2*y_mid - 0.25*launchY - 0.25*landY) / 0.5
          const desiredPeakAboveSummit = 30 + landDistFromRim * geo.scaleH * 0.25;
          const desiredMidY = geo.summitPx - desiredPeakAboveSummit;
          const cpY = (2 * desiredMidY - 0.5 * launchY - 0.5 * landPt.y);
          // Control point X: midpoint horizontally
          const cpxR = (centerX + landPt.x) / 2;
          const cpxL = (centerX + landPtL.x) / 2;
          return (
            <g>
              {/* Right trajectory */}
              <path
                d={`M ${centerX} ${launchY} Q ${cpxR} ${cpY} ${landPt.x} ${landPt.y}`}
                fill="none" stroke="#ff6b35" strokeWidth="1" strokeDasharray="4 3" opacity="0.4"
              />
              {/* Landing marker */}
              <circle cx={landPt.x} cy={landPt.y} r="3" fill="none" stroke="#ff6b35" strokeWidth="1" opacity="0.5" />
              {/* Left trajectory (mirror) */}
              <path
                d={`M ${centerX} ${launchY} Q ${cpxL} ${cpY} ${landPtL.x} ${landPtL.y}`}
                fill="none" stroke="#ff6b35" strokeWidth="1" strokeDasharray="4 3" opacity="0.4"
              />
              <circle cx={landPtL.x} cy={landPtL.y} r="3" fill="none" stroke="#ff6b35" strokeWidth="1" opacity="0.5" />
              {/* Label */}
              <text
                x={landPt.x + 6} y={landPt.y - 6}
                fill="#ff6b35" fontSize="9" fontFamily="monospace" opacity="0.5"
              >
                Bomb {geo.bombMax.toFixed(0)}m
              </text>
            </g>
          );
        })()}

        {/* ── 8. Dimension lines: lava flow + basin (on top) ────────── */}
        {geo.flowPxR > 0 && (() => {
          const flowFromCraterPx = geo.rawFlowLength * geo.scaleH;
          const craterEdgePx = centerX + geo.craterPxR;

          return (
            <g>
              {/* ── Flow ruler (lava color): crater edge → flow tip ── */}
              {geo.rawFlowLength > 0 && (
                <g>
                  {/* Left tick at crater edge */}
                  <line
                    x1={craterEdgePx} y1={GROUND_Y + 10}
                    x2={craterEdgePx} y2={GROUND_Y + 18}
                    stroke={lavaColor} strokeWidth="0.7" opacity="0.6"
                  />
                  {/* Dimension line */}
                  <line
                    x1={craterEdgePx} y1={GROUND_Y + 14}
                    x2={craterEdgePx + flowFromCraterPx} y2={GROUND_Y + 14}
                    stroke={lavaColor} strokeWidth="0.7" opacity="0.5"
                  />
                  {/* Right tick at flow tip */}
                  <line
                    x1={craterEdgePx + flowFromCraterPx} y1={GROUND_Y + 10}
                    x2={craterEdgePx + flowFromCraterPx} y2={GROUND_Y + 18}
                    stroke={lavaColor} strokeWidth="0.7" opacity="0.6"
                  />
                  {/* Label */}
                  <text
                    x={craterEdgePx + flowFromCraterPx / 2} y={GROUND_Y + 26}
                    fill={lavaColor} fontSize="9" fontFamily="monospace" textAnchor="middle" opacity="0.7"
                  >
                    Flow {geo.rawFlowLength.toFixed(0)}m
                  </text>
                </g>
              )}

              {/* ── Basin ruler (gray): center → basin edge ── */}
              <g>
                {/* Basin extent ticks at ground level */}
                <line
                  x1={centerX - geo.flowPxR} y1={GROUND_Y - 5}
                  x2={centerX - geo.flowPxR} y2={GROUND_Y + 5}
                  stroke="#888" strokeWidth="0.7" opacity="0.4"
                />
                <line
                  x1={centerX + geo.flowPxR} y1={GROUND_Y - 5}
                  x2={centerX + geo.flowPxR} y2={GROUND_Y + 5}
                  stroke="#888" strokeWidth="0.7" opacity="0.4"
                />
                {/* Center tick */}
                <line
                  x1={centerX} y1={GROUND_Y + 30}
                  x2={centerX} y2={GROUND_Y + 38}
                  stroke="#888" strokeWidth="0.5" opacity="0.4"
                />
                {/* Dimension line: center → right basin edge */}
                <line
                  x1={centerX} y1={GROUND_Y + 34}
                  x2={centerX + geo.flowPxR} y2={GROUND_Y + 34}
                  stroke="#888" strokeWidth="0.5" opacity="0.35"
                />
                {/* Right tick */}
                <line
                  x1={centerX + geo.flowPxR} y1={GROUND_Y + 30}
                  x2={centerX + geo.flowPxR} y2={GROUND_Y + 38}
                  stroke="#888" strokeWidth="0.5" opacity="0.4"
                />
                {/* Label */}
                <text
                  x={centerX + geo.flowPxR / 2} y={GROUND_Y + 48}
                  fill="#888" fontSize="9" fontFamily="monospace" textAnchor="middle" opacity="0.5"
                >
                  Basin r={(geo.rawFlowLength + geo.craterRadius).toFixed(0)}m
                </text>
              </g>
            </g>
          );
        })()}

        {/* ── 9. Fumarole / geothermal activity (pre-eruption) ──────── */}
        {hasActivity && !isErupting && (
          <FumaroleEffects geo={geo} intensity={intensity} />
        )}

        {/* ── 10. Crater glow (ERUPTION_IMMINENT+), clipped to crater bowl */}
        {scaleFactor >= 0.7 && (
          <g clipPath={`url(#glow-clip-${uid})`}>
            {/* Outer diffuse glow — slow breathing */}
            <ellipse
              cx={centerX}
              cy={geo.summitPx + geo.craterDepthPx * 0.5}
              rx={geo.craterPxR * 0.8}
              ry={Math.max(geo.craterDepthPx * 0.4, 4)}
              fill={lavaColor}
            >
              <animate attributeName="opacity" values={`${0.05 + (intensity - 0.7) * 0.3};${0.15 + (intensity - 0.7) * 0.5};${0.08 + (intensity - 0.7) * 0.2};${0.12 + (intensity - 0.7) * 0.4};${0.05 + (intensity - 0.7) * 0.3}`} dur="4s" repeatCount="indefinite" />
              <animate attributeName="ry" values={`${Math.max(geo.craterDepthPx * 0.35, 3)};${Math.max(geo.craterDepthPx * 0.45, 5)};${Math.max(geo.craterDepthPx * 0.35, 3)}`} dur="3.5s" repeatCount="indefinite" />
            </ellipse>
            {/* Mid warm glow — irregular flicker */}
            <ellipse
              cx={centerX}
              cy={geo.summitPx + geo.craterDepthPx * 0.6}
              rx={geo.craterPxR * 0.5}
              ry={Math.max(geo.craterDepthPx * 0.25, 2)}
              fill={lavaGlow}
            >
              <animate attributeName="opacity" values={`${0.1 + (intensity - 0.7) * 0.5};${0.35 + (intensity - 0.7) * 1.2};${0.15 + (intensity - 0.7) * 0.4};${0.3 + (intensity - 0.7)};${0.1 + (intensity - 0.7) * 0.5}`} dur="2.8s" repeatCount="indefinite" />
              <animate attributeName="rx" values={`${geo.craterPxR * 0.45};${geo.craterPxR * 0.55};${geo.craterPxR * 0.45}`} dur="3.2s" repeatCount="indefinite" />
            </ellipse>
            {/* Inner bright core — fast flicker with size pulse */}
            <ellipse
              cx={centerX}
              cy={geo.summitPx + geo.craterDepthPx * 0.65}
              rx={geo.craterPxR * 0.25}
              ry={Math.max(geo.craterDepthPx * 0.1, 1.5)}
              fill="#ffcc44"
            >
              <animate attributeName="opacity" values={`${0.2 + (intensity - 0.7)};${0.6 + (intensity - 0.7) * 1.5};${0.15 + (intensity - 0.7) * 0.8};${0.5 + (intensity - 0.7) * 1.2};${0.2 + (intensity - 0.7)}`} dur="1.8s" repeatCount="indefinite" />
              <animate attributeName="rx" values={`${geo.craterPxR * 0.2};${geo.craterPxR * 0.3};${geo.craterPxR * 0.18};${geo.craterPxR * 0.28};${geo.craterPxR * 0.2}`} dur="2.2s" repeatCount="indefinite" />
              <animate attributeName="ry" values={`${Math.max(geo.craterDepthPx * 0.08, 1)};${Math.max(geo.craterDepthPx * 0.15, 2)};${Math.max(geo.craterDepthPx * 0.08, 1)}`} dur="2.2s" repeatCount="indefinite" />
            </ellipse>
          </g>
        )}

        {/* ── 11. Eruption effects (ERUPTION_IMMINENT / ERUPTING) ──── */}
        {isErupting && (
          <EruptionEffects
            style={style} intensity={intensity}
            vent={vent} geo={geo} lavaColor={lavaColor}
          />
        )}

        {/* ── 12. Labels & dimensions (topmost) ────────────────────── */}
        <Labels geo={geo} vent={vent} />
      </svg>
    </div>
  );
}

// ── Main component (with card wrapper) ──────────────────────────────────────

export default function VolcanoCrossSection({ vent }: Props) {
  return (
    <div className="rounded-lg border border-border bg-card p-4">
      <h3 className="text-sm font-semibold mb-2 text-muted-foreground uppercase tracking-wider">
        Cross Section — {vent.name}
      </h3>
      <VolcanoCrossSectionRaw vent={vent} />
    </div>
  );
}

// ── Labels ───────────────────────────────────────────────────────────────────

function Labels({ geo, vent }: { geo: Geo; vent: VentDetail }) {
  const { centerX } = geo;
  return (
    <g>
      {/* Summit Y */}
      <line
        x1={centerX + geo.craterPxR + 8} y1={geo.summitPx}
        x2={centerX + geo.craterPxR + 30} y2={geo.summitPx}
        stroke="#999" strokeWidth="0.5"
      />
      <text
        x={centerX + geo.craterPxR + 33} y={geo.summitPx + 3}
        fill="#bbb" fontSize="9" fontFamily="monospace"
      >
        Y={vent.summitY}
      </text>

      {/* Base Y */}
      <text
        x={8} y={GROUND_Y + 12}
        fill="#777" fontSize="9" fontFamily="monospace"
      >
        Base Y={vent.baseY}
      </text>

      {/* Crater radius dimension */}
      <line
        x1={centerX - geo.craterPxR} y1={geo.summitPx - 8}
        x2={centerX + geo.craterPxR} y2={geo.summitPx - 8}
        stroke="#aaa" strokeWidth="0.5"
      />
      {/* Ticks */}
      <line
        x1={centerX - geo.craterPxR} y1={geo.summitPx - 12}
        x2={centerX - geo.craterPxR} y2={geo.summitPx - 4}
        stroke="#aaa" strokeWidth="0.5"
      />
      <line
        x1={centerX + geo.craterPxR} y1={geo.summitPx - 12}
        x2={centerX + geo.craterPxR} y2={geo.summitPx - 4}
        stroke="#aaa" strokeWidth="0.5"
      />
      <text
        x={centerX} y={geo.summitPx - 13}
        fill="#bbb" fontSize="9" fontFamily="monospace" textAnchor="middle"
      >
        {geo.isCaldera ? 'Caldera' : 'Crater'} r={geo.isCaldera ? geo.calderaRadius : vent.craterRadius}
      </text>

    </g>
  );
}

// ── Eruption effects by style ────────────────────────────────────────────────

interface EffectProps {
  geo: Geo;
  intensity: number;
  lavaColor: string;
}

function EruptionEffects({ style, intensity, geo, lavaColor }: {
  style: string;
  intensity: number;
  vent: VentDetail;
  geo: Geo;
  lavaColor: string;
}) {
  const s = style.toLowerCase();
  if (s.includes('hawaiian')) return <HawaiianEffect geo={geo} intensity={intensity} lavaColor={lavaColor} />;
  if (s.includes('strombolian')) return <StrombolianEffect geo={geo} intensity={intensity} lavaColor={lavaColor} />;
  if (s.includes('vulcanian')) return <VulcanianEffect geo={geo} intensity={intensity} lavaColor={lavaColor} />;
  if (s.includes('pelean') || s.includes('peléan')) return <PeleanEffect geo={geo} intensity={intensity} lavaColor={lavaColor} />;
  if (s.includes('plinian')) return <PlinianEffect geo={geo} intensity={intensity} lavaColor={lavaColor} />;
  if (s.includes('dome')) return <LavaDomeEffect geo={geo} intensity={intensity} lavaColor={lavaColor} />;
  return <GenericEruption geo={geo} intensity={intensity} lavaColor={lavaColor} />;
}

// Hawaiian: gentle lava fountain arcs following quadratic trajectories
function HawaiianEffect({ geo, intensity, lavaColor }: EffectProps) {
  const { centerX } = geo;
  const craterY = geo.summitPx + geo.craterDepthPx * 0.3;
  const n = Math.ceil(intensity * 10);
  const particles = useMemo(() =>
    Array.from({ length: n }, (_, i) => {
      const spread = (i / n - 0.5) * geo.craterPxR * 1.8;
      const h = 20 + (i % 3) * 15 * intensity;
      const sx = centerX;
      const sy = craterY;
      const ex = centerX + spread;
      const ey = craterY + 10;
      // Control point: solve so bezier midpoint peaks at desired height
      const cpx = (sx + ex) / 2;
      const desiredMidY = sy - h;
      const cpy = (4 * desiredMidY - sy - ey) / 2;
      const path = `M ${sx} ${sy} Q ${cpx} ${cpy} ${ex} ${ey}`;
      return { path, delay: (i * 0.25) % 3, dur: 1.0 + (i % 4) * 0.3 };
    }),
  [n, geo.craterPxR, intensity, centerX, craterY]);

  return (
    <g>
      {/* Fountain particles: quadratic parabolic arcs */}
      {particles.map((p, i) => (
        <circle key={i} r={1.5 + (i % 2)} fill={i % 3 === 0 ? '#ffcc00' : lavaColor} opacity="0">
          <animateMotion path={p.path} dur={`${p.dur}s`} begin={`${p.delay}s`} repeatCount="indefinite" />
          <animate attributeName="opacity" values="0;0.9;0.7;0" dur={`${p.dur}s`} begin={`${p.delay}s`} repeatCount="indefinite" />
        </circle>
      ))}
    </g>
  );
}

// Strombolian: discrete bomb trajectories arcing outward, landing on slope
function StrombolianEffect({ geo, intensity, lavaColor }: EffectProps) {
  const { centerX } = geo;
  const craterY = geo.summitPx + geo.craterDepthPx * 0.2;
  const n = Math.ceil(intensity * 6);
  const bombs = useMemo(() => {
    const slopePxLen = geo.flowPxR - geo.craterPxR;
    return Array.from({ length: n }, (_, i) => {
      const side = i % 2 === 0 ? 1 : -1;
      const absLandDist = geo.craterPxR * 2 + (i + 1) * geo.bombPxR * 0.15;
      // Land on slope instead of flat ground
      const landFrac = slopePxLen > 0 ? Math.min(Math.max((absLandDist - geo.craterPxR) / slopePxLen, 0), 1) : 0;
      const landPt = slopePointAt(geo, landFrac);
      const sx = centerX;
      const sy = craterY;
      const ex = side > 0 ? landPt.x : 2 * centerX - landPt.x;
      const ey = landPt.y;
      const peakH = 40 + (i % 3) * 25 * intensity;
      // Solve for control point so bezier midpoint is at desired peak height
      const desiredMidY = sy - peakH;
      const cpx = (sx + ex) / 2;
      const cpy = (4 * desiredMidY - sy - ey) / 2;
      return { path: `M ${sx} ${sy} Q ${cpx} ${cpy} ${ex} ${ey}`, delay: i * 0.6, dur: 1.4 + (i % 3) * 0.4 };
    });
  }, [n, geo, centerX, craterY, intensity]);

  return (
    <g>
      {bombs.map((b, i) => (
        <circle key={i} r={2.5 + (i % 2)} fill={i % 2 === 0 ? '#ff8c00' : lavaColor} opacity="0">
          <animateMotion path={b.path} dur={`${b.dur}s`} begin={`${b.delay}s`} repeatCount="indefinite" />
          <animate attributeName="opacity" values="0;1;0.8;0" dur={`${b.dur}s`} begin={`${b.delay}s`} repeatCount="indefinite" />
        </circle>
      ))}
    </g>
  );
}

// Vulcanian: explosive burst + ejected fragments + rising ash cloud
function VulcanianEffect({ geo, intensity, lavaColor }: EffectProps) {
  const { centerX } = geo;
  const burstR = geo.craterPxR * 1.5 * intensity;
  const cloudMaxR = 50 * intensity;
  const cloudRiseH = 80 * intensity;
  const craterY = geo.summitPx + geo.craterDepthPx * 0.2;

  const n = Math.ceil(intensity * 5);
  const fragments = useMemo(() => {
    const slopePxLen = geo.flowPxR - geo.craterPxR;
    return Array.from({ length: n }, (_, i) => {
      const side = i % 2 === 0 ? 1 : -1;
      const absLandDist = geo.craterPxR * 1.5 + (i + 1) * geo.bombPxR * 0.12;
      const landFrac = slopePxLen > 0 ? Math.min(Math.max((absLandDist - geo.craterPxR) / slopePxLen, 0), 1) : 0;
      const landPt = slopePointAt(geo, landFrac);
      const sx = centerX;
      const sy = craterY;
      const ex = side > 0 ? landPt.x : 2 * centerX - landPt.x;
      const ey = landPt.y;
      const peakH = 50 + (i % 3) * 30 * intensity;
      const desiredMidY = sy - peakH;
      const cpx = (sx + ex) / 2;
      const cpy = (4 * desiredMidY - sy - ey) / 2;
      const path = `M ${sx} ${sy} Q ${cpx} ${cpy} ${ex} ${ey}`;
      return { path, delay: i * 0.3 + 0.2, dur: 1.2 + (i % 3) * 0.5 };
    });
  }, [n, geo, centerX, craterY, intensity]);

  return (
    <g>
      {/* Burst flash */}
      <circle cx={centerX} cy={geo.summitPx} r={burstR} fill={lavaColor} opacity="0">
        <animate attributeName="r" values={`${burstR * 0.2};${burstR};${burstR * 0.5}`} dur="1.5s" repeatCount="indefinite" />
        <animate attributeName="opacity" values="0;0.55;0" dur="1.5s" repeatCount="indefinite" />
      </circle>
      {/* Rising ash cloud */}
      <ellipse cx={centerX} cy={geo.summitPx} rx={cloudMaxR * 0.3} ry={cloudMaxR * 0.2} fill="#4a4a4a" opacity="0">
        <animate attributeName="cy" values={`${geo.summitPx};${geo.summitPx - cloudRiseH * 0.5};${geo.summitPx - cloudRiseH}`} dur="3.5s" repeatCount="indefinite" />
        <animate attributeName="rx" values={`${cloudMaxR * 0.2};${cloudMaxR * 0.6};${cloudMaxR}`} dur="3.5s" repeatCount="indefinite" />
        <animate attributeName="ry" values={`${cloudMaxR * 0.15};${cloudMaxR * 0.35};${cloudMaxR * 0.5}`} dur="3.5s" repeatCount="indefinite" />
        <animate attributeName="opacity" values="0;0.45;0.08" dur="3.5s" repeatCount="indefinite" />
      </ellipse>
      {/* Ejected fragments following quadratic trajectories */}
      {fragments.map((f, i) => (
        <circle key={i} r={3 + (i % 2)} fill={i % 2 === 0 ? '#ff6633' : lavaColor} opacity="0">
          <animateMotion path={f.path} dur={`${f.dur}s`} begin={`${f.delay}s`} repeatCount="indefinite" />
          <animate attributeName="opacity" values="0;0.9;0.7;0" dur={`${f.dur}s`} begin={`${f.delay}s`} repeatCount="indefinite" />
        </circle>
      ))}
      {/* Vent glow */}
      <ellipse cx={centerX} cy={geo.summitPx + geo.craterDepthPx * 0.3} rx={geo.craterPxR * 0.7} ry={4} fill={lavaColor}>
        <animate attributeName="opacity" values="0.3;0.8;0.3" dur="0.7s" repeatCount="indefinite" />
      </ellipse>
    </g>
  );
}

// Pelean: dome + pyroclastic flows down slopes
function PeleanEffect({ geo, intensity, lavaColor }: EffectProps) {
  const { centerX } = geo;
  const domeR = geo.craterPxR * 0.7;
  const flowDist = geo.flowPxR * 0.6 * intensity;
  const craterY = geo.summitPx + geo.craterDepthPx * 0.2;

  return (
    <g>
      {/* Dome */}
      <ellipse cx={centerX} cy={craterY} rx={domeR} ry={domeR * 0.5} fill={lavaColor} opacity="0.6">
        <animate attributeName="ry" values={`${domeR * 0.35};${domeR * 0.55};${domeR * 0.35}`} dur="5s" repeatCount="indefinite" />
      </ellipse>
      {/* Pyroclastic flow L — hugs the slope */}
      <path
        d={`M ${centerX - geo.craterPxR * 0.5} ${craterY + 8}
            Q ${centerX - geo.flowPxR * 0.3} ${GROUND_Y - (GROUND_Y - craterY) * 0.3}
              ${centerX - flowDist} ${GROUND_Y}
            L ${centerX - flowDist * 0.5} ${GROUND_Y} Z`}
        fill="#666" opacity="0"
      >
        <animate attributeName="opacity" values="0;0.35;0.1;0" dur="4.5s" repeatCount="indefinite" />
      </path>
      {/* Pyroclastic flow R */}
      <path
        d={`M ${centerX + geo.craterPxR * 0.5} ${craterY + 8}
            Q ${centerX + geo.flowPxR * 0.3} ${GROUND_Y - (GROUND_Y - craterY) * 0.3}
              ${centerX + flowDist} ${GROUND_Y}
            L ${centerX + flowDist * 0.5} ${GROUND_Y} Z`}
        fill="#666" opacity="0"
      >
        <animate attributeName="opacity" values="0;0.1;0.35;0" dur="4.5s" repeatCount="indefinite" />
      </path>
    </g>
  );
}

// Plinian: massive vertical ash column with mushroom top
function PlinianEffect({ geo, intensity, lavaColor }: EffectProps) {
  const { centerX } = geo;
  const colW = geo.craterPxR * 1.2 * intensity;
  const colH = Math.min((GROUND_Y - TOP_PAD) * 0.7 * intensity, geo.summitPx - TOP_PAD);
  const topY = geo.summitPx - colH;
  const mushRx = colW * 2;
  const mushRy = colW * 1;

  return (
    <g>
      {/* Column body */}
      <rect
        x={centerX - colW / 2} y={topY}
        width={colW} height={colH}
        fill="#4a4a4a" opacity="0" rx={colW * 0.15}
      >
        <animate attributeName="opacity" values="0;0.5;0.4;0.5" dur="2s" repeatCount="indefinite" />
        <animate attributeName="width" values={`${colW * 0.85};${colW * 1.15};${colW * 0.85}`} dur="1.8s" repeatCount="indefinite" />
        <animate attributeName="x" values={`${centerX - colW * 0.425};${centerX - colW * 0.575};${centerX - colW * 0.425}`} dur="1.8s" repeatCount="indefinite" />
      </rect>
      {/* Mushroom cap */}
      <ellipse cx={centerX} cy={topY} rx={mushRx} ry={mushRy} fill="#3a3a3a" opacity="0">
        <animate attributeName="opacity" values="0;0.45;0.35;0.45" dur="2.5s" repeatCount="indefinite" />
        <animate attributeName="rx" values={`${mushRx * 0.8};${mushRx * 1.1};${mushRx * 0.8}`} dur="3s" repeatCount="indefinite" />
      </ellipse>
      {/* Vent glow */}
      <ellipse cx={centerX} cy={geo.summitPx + geo.craterDepthPx * 0.3} rx={geo.craterPxR * 0.8} ry={6} fill={lavaColor} opacity={0.4 * intensity}>
        <animate attributeName="opacity" values={`${0.2 * intensity};${0.7 * intensity};${0.2 * intensity}`} dur="0.5s" repeatCount="indefinite" />
      </ellipse>
    </g>
  );
}

// Lava Dome: slow-growing dome filling the crater
function LavaDomeEffect({ geo, intensity, lavaColor }: EffectProps) {
  const { centerX } = geo;
  const domeRx = geo.craterPxR * 1.0;
  const domeRy = geo.craterDepthPx * 0.8 + geo.craterPxR * 0.3;
  const craterY = geo.summitPx + geo.craterDepthPx * 0.4;

  return (
    <g>
      <ellipse cx={centerX} cy={craterY} rx={domeRx} ry={domeRy} fill={lavaColor} opacity="0.55">
        <animate attributeName="ry" values={`${domeRy * 0.7};${domeRy};${domeRy * 0.7}`} dur="8s" repeatCount="indefinite" />
      </ellipse>
      {/* Hot crack glow on dome surface */}
      <ellipse cx={centerX} cy={craterY - domeRy * 0.2} rx={domeRx * 0.3} ry={2} fill="#ff9960" opacity={0.2 * intensity}>
        <animate attributeName="opacity" values={`${0.1 * intensity};${0.4 * intensity};${0.1 * intensity}`} dur="4s" repeatCount="indefinite" />
      </ellipse>
    </g>
  );
}

// Fumarole / geothermal effects for MINOR_ACTIVITY and MAJOR_ACTIVITY
function FumaroleEffects({ geo, intensity }: { geo: Geo; intensity: number }) {
  const { centerX } = geo;
  // More steam wisps as activity increases
  const n = intensity >= 0.7 ? 5 : intensity >= 0.3 ? 3 : 0;
  const craterY = geo.summitPx + geo.craterDepthPx * 0.4;

  return (
    <g>
      {/* Steam wisps rising from crater */}
      {Array.from({ length: n }, (_, i) => {
        const xOff = (i - (n - 1) / 2) * geo.craterPxR * 0.3;
        const riseH = 20 + i * 8 * intensity;
        const dur = 2.5 + i * 0.6;
        const delay = i * 0.7;
        return (
          <ellipse
            key={i}
            cx={centerX + xOff} cy={craterY}
            rx={3 + i} ry={2 + i * 0.5}
            fill="#ccc" opacity="0"
          >
            <animate attributeName="cy" values={`${craterY};${craterY - riseH};${craterY - riseH * 1.3}`} dur={`${dur}s`} begin={`${delay}s`} repeatCount="indefinite" />
            <animate attributeName="rx" values={`${2 + i};${5 + i * 2};${8 + i * 3}`} dur={`${dur}s`} begin={`${delay}s`} repeatCount="indefinite" />
            <animate attributeName="ry" values={`${1 + i * 0.3};${3 + i};${5 + i * 1.5}`} dur={`${dur}s`} begin={`${delay}s`} repeatCount="indefinite" />
            <animate attributeName="opacity" values={`0;${0.15 + intensity * 0.15};0`} dur={`${dur}s`} begin={`${delay}s`} repeatCount="indefinite" />
          </ellipse>
        );
      })}

      {/* Faint heat shimmer at crater for MAJOR_ACTIVITY */}
      {intensity >= 0.7 && (
        <ellipse
          cx={centerX} cy={craterY}
          rx={geo.craterPxR * 0.4} ry={3}
          fill="#ff8c00" opacity="0"
        >
          <animate attributeName="opacity" values="0;0.08;0" dur="3s" repeatCount="indefinite" />
        </ellipse>
      )}
    </g>
  );
}

// Generic fallback
function GenericEruption({ geo, intensity, lavaColor }: EffectProps) {
  const { centerX } = geo;
  return (
    <ellipse cx={centerX} cy={geo.summitPx + geo.craterDepthPx * 0.3} rx={geo.craterPxR * 0.6} ry={6} fill={lavaColor} opacity="0">
      <animate attributeName="opacity" values={`0;${0.5 * intensity};0`} dur="2s" repeatCount="indefinite" />
    </ellipse>
  );
}
