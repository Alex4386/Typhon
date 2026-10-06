import { describe, expect, it } from 'vitest';
import { budgetVerdict } from './budget';
import type { MagmaBudget } from '../protocol/messages';

const base: MagmaBudget = { supplyM3PerS: 5, eruptionM3PerS: 5, intrudedM3: 0, wallGrowthM3: 0, eruptedM3: 1e6, eruptionEndOverpressureMPa: 0.5 };

describe('magma budget verdict', () => {
  it('a quiet chamber with supply recharges', () => {
    expect(budgetVerdict(base, 3, 16, false).state).toBe('recharging');
    expect(budgetVerdict({ ...base, supplyM3PerS: 0 }, 3, 16, false).state).toBe('idle');
  });

  it('balance inside the open range: steady once there, settling before', () => {
    expect(budgetVerdict({ ...base, balanceOverpressureMPa: 15.3 }, 15.25, 16, true).state).toBe('steady');
    expect(budgetVerdict({ ...base, balanceOverpressureMPa: 15.3 }, 9, 16, true).state).toBe('settling');
  });

  it('balance below the end pressure drains; beyond the rupture limit is pinned', () => {
    expect(budgetVerdict({ ...base, balanceOverpressureMPa: -1.2 }, 1, 16, true).state).toBe('draining');
    expect(budgetVerdict({ ...base }, 1, 16, true).state).toBe('draining');
    expect(budgetVerdict({ ...base, balanceOverpressureMPa: 34 }, 16, 16, true).state).toBe('pinned');
  });
});
