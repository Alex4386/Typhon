import type { SimCommand } from '../protocol/messages';
import type { EntityView } from '../store/entities';

/** The chamber magma fields: command field, entity property with the current bulk value, label, unit, range. */
export const MAGMA_FIELDS = [
  { id: 'temperatureC', prop: 'temperatureC', label: 'Temperature', unit: '°C', min: 600, max: 1400 },
  { id: 'silicaWt', prop: 'bulkSilicaWt', label: 'Silica (SiO₂, bulk)', unit: 'wt%', min: 35, max: 80 },
  { id: 'waterWt', prop: 'bulkWaterWt', label: 'Water (H₂O, bulk)', unit: 'wt%', min: 0, max: 15 },
  { id: 'co2Wt', prop: 'bulkCo2Wt', label: 'CO₂ (bulk)', unit: 'wt%', min: 0, max: 5 },
] as const;

/** The `setChamberMagma` command for typed values, or the first problem with them. */
export function magmaCommand(e: EntityView, values: Record<string, string>): { command?: SimCommand; error?: string } {
  if (!e.volcanoId) return { error: 'No volcano' };
  const c: Extract<SimCommand, { kind: 'setChamberMagma' }> = { kind: 'setChamberMagma', volcanoId: e.volcanoId };
  const chamberId = e.props.chamberId;
  if (typeof chamberId === 'string') c.chamberId = chamberId;
  for (const f of MAGMA_FIELDS) {
    const raw = (values[f.id] ?? '').trim();
    if (raw === '') continue;
    const v = Number(raw);
    if (!Number.isFinite(v) || v < f.min || v > f.max) return { error: `${f.label} must be between ${f.min} and ${f.max} ${f.unit}` };
    c[f.id] = v;
  }
  return { command: c };
}
