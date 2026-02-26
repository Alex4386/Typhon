import { useState, useEffect } from 'react';
import type { VentRecordData, EjectaRecord } from '@/transport/types';
import { ClipboardList, ChevronDown, ChevronUp } from 'lucide-react';
import {
  Table, TableHeader, TableBody, TableHead, TableRow, TableCell,
} from '@/components/ui/table';
import { VEIBadge, EjectaCard, formatVolume } from '@/components/volcano/EjectaCard';
import { StatCard } from './shared';

/* ── Formatters ──────────────────────────────────────────────────────────── */

function fmtDuration(ms: number): string {
  const totalSec = Math.floor(ms / 1000);
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  const s = totalSec % 60;
  if (h > 0) return `${h}h ${m}m ${s}s`;
  if (m > 0) return `${m}m ${s}s`;
  return `${s}s`;
}

function fmtDate(epoch: number): string {
  return new Date(epoch).toLocaleString();
}

function fmtPct(v: number): string {
  return `${(v * 100).toFixed(0)}%`;
}

function fmtMinecraftTick(value?: number): string {
  if (typeof value !== 'number' || Number.isNaN(value) || value < 0) return 'N/A';
  const tick = Math.floor(value);
  const day = Math.floor(tick / 24000) + 1;
  const timeTicks = ((tick % 24000) + 24000) % 24000;
  // Minecraft day starts at 06:00. Shift by +6000 to align 0 -> 06:00.
  const shifted = (timeTicks + 6000) % 24000;
  const hour = Math.floor(shifted / 1000);
  const minute = Math.floor((shifted % 1000) * 60 / 1000);
  return `Day ${day} ${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

function fmtMinecraftDurationTicks(value?: number): string {
  if (typeof value !== 'number' || Number.isNaN(value) || value < 0) return 'N/A';
  const tick = Math.floor(value);
  const day = Math.floor(tick / 24000);
  const timeTicks = ((tick % 24000) + 24000) % 24000;
  const hour = Math.floor(timeTicks / 1000);
  const minute = Math.floor((timeTicks % 1000) * 60 / 1000);

  let length = '';
  if (day > 0) length += `${day}d`;
  if (hour > 0) length += `${hour}h`;
  if (minute > 0) length += `${minute}m`;
  return length.length ? length : '0m';
}

function getTotalLavaFlowDuration(
  records: EjectaRecord[],
  activeStartTime?: number,
  activeEndTime?: number
): number {
  const intervals = records
    .map((r) => {
      const start = r.startTime;
      const rawEnd = r.endOfLavaFlowTime ?? r.endTime;
      const end = Math.min(Math.max(rawEnd, start), r.endTime);
      return [start, end] as const;
    })
    .filter(([start, end]) => end > start)
    .sort((a, b) => a[0] - b[0]);

  if (
    typeof activeStartTime === 'number'
    && typeof activeEndTime === 'number'
    && activeStartTime > 0
    && activeEndTime > activeStartTime
  ) {
    intervals.push([activeStartTime, activeEndTime]);
    intervals.sort((a, b) => a[0] - b[0]);
  }

  if (intervals.length === 0) return 0;

  let total = 0;
  let [cursorStart, cursorEnd] = intervals[0];
  for (let i = 1; i < intervals.length; i++) {
    const [nextStart, nextEnd] = intervals[i];
    if (nextStart <= cursorEnd) {
      cursorEnd = Math.max(cursorEnd, nextEnd);
      continue;
    }
    total += cursorEnd - cursorStart;
    cursorStart = nextStart;
    cursorEnd = nextEnd;
  }
  total += cursorEnd - cursorStart;
  return total;
}

/* ── Expandable Row Detail ───────────────────────────────────────────────── */

function RecordDetailRow({ record }: { record: EjectaRecord }) {
  const meta = record.metadata;
  const height = meta ? meta.summit.y - meta.baseY : 0;

  return (
    <TableRow>
      <TableCell colSpan={6} className="bg-muted/30 p-0">
        <div className="px-6 py-3">
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-x-6 gap-y-2 text-xs">
            <div>
              <span className="text-muted-foreground">Lava Flow Start</span>
              <div className="flex flex-col">
                <div className="font-medium tabular-nums">{fmtDate(record.startTime)}</div>
                {record.startTick && <div className="text-sm">Minecraft: {fmtMinecraftTick(record.startTick)}</div>}
              </div>
            </div>
            <div>
              <span className="text-muted-foreground">Lava Flow End</span>
              <div className="flex flex-col">
                <div className="font-medium tabular-nums">{fmtDate(record.endTime)}</div>
                {record.endTick && <div className="text-sm">Minecraft: {fmtMinecraftTick(record.endTick)}</div>}
              </div>
            </div>
            <div>
              <span className="text-muted-foreground">Eruption Duration</span>
              <div className="flex flex-col">
                <div className="font-medium tabular-nums">{fmtDuration(Math.max(0, record.endTime - record.startTime))}</div>
                {(record.startTick && record.endTick) && <div className="text-sm">Minecraft: {fmtMinecraftDurationTicks(Math.max(0, record.endTick - record.startTick))}</div>}
              </div>
            </div>
            <div>
              <span className="text-muted-foreground">Lava Flow Duration</span>
              <div className="flex flex-col">
                <div className="font-medium tabular-nums">{fmtDuration(Math.max(0, Math.max(record.endTime, record.endOfLavaFlowTime ?? 0) - record.startTime))}</div>
                {(record.startTick && record.endTick) && <div className="text-sm">Minecraft: {fmtMinecraftDurationTicks(Math.max(record.endTick, record.endOfLavaFlowTick ?? 0) - record.startTick)}</div>}
              </div>
            </div>
          </div>
          {meta && (
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-x-6 gap-y-2 text-xs">
              <div>
                <span className="text-muted-foreground">Style</span>
                <div className="font-medium">{meta.eruptionStyle.replace(/_/g, ' ')}</div>
              </div>
              <div>
                <span className="text-muted-foreground">Type</span>
                <div className="font-medium">{meta.ventType}</div>
              </div>
              <div>
                <span className="text-muted-foreground">Summit</span>
                <div className="font-medium tabular-nums">{meta.summit.x}, {meta.summit.y}, {meta.summit.z}</div>
              </div>
              <div>
                <span className="text-muted-foreground">Height</span>
                <div className="font-medium tabular-nums">{height}m (base Y={meta.baseY})</div>
              </div>
              <div>
                <span className="text-muted-foreground">Silicate</span>
                <div className="font-medium tabular-nums">{fmtPct(meta.silicateLevel)}</div>
              </div>
              <div>
                <span className="text-muted-foreground">Gas Content</span>
                <div className="font-medium tabular-nums">{fmtPct(meta.gasContent)}</div>
              </div>
              <div>
                <span className="text-muted-foreground">Crater Radius</span>
                <div className="font-medium tabular-nums">{meta.craterRadius}m</div>
              </div>
              <div>
                <span className="text-muted-foreground">Lava Flow</span>
                <div className="font-medium tabular-nums">{meta.currentNormalLavaFlowLength.toFixed(1)}m / {meta.longestNormalLavaFlowLength.toFixed(1)}m</div>
              </div>
              <div>
                <span className="text-muted-foreground">Total Flow</span>
                <div className="font-medium tabular-nums">{meta.currentFlowLength.toFixed(1)}m / {meta.longestFlowLength.toFixed(1)}m</div>
              </div>
            </div>
          )}
        </div>
        {!meta && (
          <div className="px-6 pb-3 text-xs text-muted-foreground italic">
            No metadata recorded for this eruption (recorded before metadata tracking)
          </div>
        )}
      </TableCell>
    </TableRow>
  );
}

/* ── Main Component ──────────────────────────────────────────────────────── */

export function RecordTab({ record }: { record: VentRecordData | null }) {
  const [expandedIdx, setExpandedIdx] = useState<number | null>(null);

  if (!record) {
    return <p className="text-sm text-muted-foreground">Loading record data...</p>;
  }

  const { records, currentEjecta, totalEjecta, startEjectaTracking, startEjectaTrackingTick, currentLavaFlowEndTick } = record;
  const isTracking = startEjectaTracking > 0;

  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    if (!isTracking) return;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [isTracking]);

  const finishedDuration = records.reduce((acc, r) => acc + (r.endTime - r.startTime), 0);
  const totalDuration = finishedDuration + (isTracking ? now - startEjectaTracking : 0);
  const totalLavaFlowDuration = getTotalLavaFlowDuration(
    records,
    startEjectaTracking,
    record.currentLavaFlowEndTime
  );

  return (
    <div className="space-y-4">
      {/* Summary */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <StatCard label="Total Eruptions" value={String(records.length)} />
        <StatCard label="Lifetime Ejecta" value={formatVolume(totalEjecta)} />
        <StatCard label="Total Eruption Time" value={fmtDuration(totalDuration)} />
        <StatCard label="Total Lava Flow Time" value={fmtDuration(totalLavaFlowDuration)} />
      </div>

      {/* VEI cards */}
      <div className={`grid gap-3 ${isTracking ? 'grid-cols-1 sm:grid-cols-2' : 'grid-cols-1'}`}>
        <EjectaCard
          label="Lifetime VEI"
          volume={totalEjecta}
          subtitle={`${records.length} eruption${records.length !== 1 ? 's' : ''} — ${fmtDuration(totalDuration)} total`}
        />
        {isTracking && (
          <EjectaCard
            label="Current Eruption"
            volume={currentEjecta}
            subtitle={`Since ${fmtDate(startEjectaTracking)} — ${fmtDuration(Date.now() - startEjectaTracking)} elapsed ${
              (currentLavaFlowEndTick && startEjectaTrackingTick)
                ? `— ingame: ${fmtMinecraftDurationTicks(
                  Math.max((currentLavaFlowEndTick ?? 0) - (startEjectaTrackingTick ?? 0), 0)
                )}` : ''
            }`}
          />
        )}
      </div>

      {/* Eruption history table */}
      {records.length > 0 ? (
        <div className="rounded-lg border border-border bg-card overflow-hidden">
          <div className="px-4 py-3 border-b border-border flex items-center gap-2">
            <ClipboardList className="size-4 text-muted-foreground" />
            <span className="text-sm font-medium">Eruption History</span>
          </div>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="w-10">#</TableHead>
                <TableHead>Started</TableHead>
                <TableHead>Ended</TableHead>
                <TableHead className="text-right">Duration (MC)</TableHead>
                <TableHead className="text-right">Ejecta</TableHead>
                <TableHead className="text-center w-20">VEI</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {[...records].reverse().map((r, i) => {
                const idx = records.length - i;
                const durationMs = r.endTime - r.startTime;
                const durationTicks = typeof r.startTick === 'number' && typeof r.endTick === 'number'
                  ? Math.max(0, r.endTick - r.startTick)
                  : undefined;
                const durationLabel = typeof durationTicks === 'number'
                  ? fmtMinecraftDurationTicks(durationTicks)
                  : fmtDuration(durationMs);
                const isExpanded = expandedIdx === i;
                return (
                  <>{/* eslint-disable-next-line react/no-array-index-key */}
                    <TableRow key={i} className="cursor-pointer" onClick={() => setExpandedIdx(isExpanded ? null : i)}>
                      <TableCell className="text-muted-foreground tabular-nums">{idx}</TableCell>
                      <TableCell className="tabular-nums">{fmtDate(r.startTime)}</TableCell>
                      <TableCell className="tabular-nums">{fmtDate(r.endTime)}</TableCell>
                      <TableCell className="text-right tabular-nums">{durationLabel}</TableCell>
                      <TableCell className="text-right tabular-nums">
                        <div className="font-medium">{formatVolume(r.ejectaVolume)}</div>
                        <div className="text-[10px] text-muted-foreground">{r.ejectaVolume.toLocaleString()} blocks</div>
                      </TableCell>
                      <TableCell className="text-center">
                        <div className="flex items-center justify-center gap-1">
                          <VEIBadge volume={r.ejectaVolume} />
                          {isExpanded ? <ChevronUp className="size-3 text-muted-foreground" /> : <ChevronDown className="size-3 text-muted-foreground" />}
                        </div>
                      </TableCell>
                    </TableRow>
                    {isExpanded && <RecordDetailRow key={`detail-${i}`} record={r} />}
                  </>
                );
              })}
            </TableBody>
          </Table>
        </div>
      ) : (
        <div className="rounded-lg border border-dashed border-border bg-muted/30 p-8 text-center">
          <ClipboardList className="size-8 text-muted-foreground/40 mx-auto mb-3" />
          <p className="text-sm text-muted-foreground">No eruption records yet</p>
          <p className="text-xs text-muted-foreground/60 mt-1">Records are created when an eruption ends</p>
        </div>
      )}
    </div>
  );
}
