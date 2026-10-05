/** Display formatting for times and factors (no DOM or store dependencies, for tests). */

/** A rough human duration: "42 s", "5 min", "3 h 20 min", "12 days", "4.2 years". */
export function formatDuration(seconds: number): string {
  const s = Math.max(0, seconds);
  if (s < 90) return `${Math.round(s)} s`;
  if (s < 3600) return `${Math.round(s / 60)} min`;
  if (s < 86400) {
    const h = Math.floor(s / 3600);
    const m = Math.round((s % 3600) / 60);
    return m > 0 && h < 10 ? `${h} h ${m} min` : `${Math.round(s / 3600)} h`;
  }
  if (s < 86400 * 365.25 * 2) return `${(s / 86400).toFixed(s < 86400 * 10 ? 1 : 0)} days`;
  return `${(s / (86400 * 365.25)).toFixed(1)} years`;
}

/** "×20", "×5 000" for compression factors. */
export function formatFactor(f: number): string {
  if (!(f > 0)) return '—';
  if (f < 10) return `×${Number(f.toPrecision(2))}`;
  return `×${Math.round(f).toLocaleString('en-US').replaceAll(',', ' ')}`;
}

export function formatSimTime(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const hms = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
  return d > 0 ? `${d}d ${hms}` : hms;
}
