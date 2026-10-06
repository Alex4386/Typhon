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

/** "×20", "×5 000" for speeds and other factors. */
export function formatFactor(f: number): string {
  if (!(f > 0)) return '—';
  if (f < 10) return `×${Number(f.toPrecision(2))}`;
  return `×${Math.round(f).toLocaleString('en-US').replaceAll(',', ' ')}`;
}

/** Days in a year of the clock. */
const YEAR_DAYS = 365;

/**
 * The world's clock: "Day 12, 04:31:07" from the start (day 1), "Year 3, day 45, 04:31:07" after
 * the first year. Without {@code withSeconds}: "Day 12, 04:31".
 */
export function formatSimTime(seconds: number, withSeconds = true): string {
  const s = Math.max(0, Math.floor(seconds));
  const days = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const hm = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
  const clock = withSeconds ? `${hm}:${String(sec).padStart(2, '0')}` : hm;
  const year = Math.floor(days / YEAR_DAYS);
  return year > 0 ? `Year ${year + 1}, day ${(days % YEAR_DAYS) + 1}, ${clock}` : `Day ${days + 1}, ${clock}`;
}
