import type { MagmaBudget } from '../protocol/messages';

export type BudgetState = 'recharging' | 'idle' | 'settling' | 'steady' | 'draining' | 'pinned';

/** What a chamber's magma budget amounts to right now, in words. */
export interface BudgetVerdict {
  state: BudgetState;
  text: string;
}

/**
 * Reads a chamber's budget: an erupting chamber relaxes towards the pressure where outflow equals
 * supply. Below the eruption-end pressure that balance cannot be reached (the eruption drains and
 * stops); above the rupture limit the pressure is pinned there and the excess goes into dikes (or the
 * walls when dikes are off); in between the chamber settles into steady, pressurised effusion.
 */
export function budgetVerdict(b: MagmaBudget, overpressureMPa: number, ruptureMPa: number | undefined, erupting: boolean): BudgetVerdict {
  if (!erupting) {
    return b.supplyM3PerS > 0
      ? { state: 'recharging', text: 'Recharging: pressure rises until the roof or walls give way.' }
      : { state: 'idle', text: 'No supply: the pressure holds.' };
  }
  const balance = b.balanceOverpressureMPa;
  const end = b.eruptionEndOverpressureMPa ?? 0;
  if (balance === undefined || balance === null || !Number.isFinite(balance) || balance <= end) {
    return { state: 'draining', text: 'Outflow exceeds supply: the eruption is draining the chamber and will stop.' };
  }
  if (ruptureMPa !== undefined && balance > ruptureMPa) {
    return {
      state: 'pinned',
      text: `Supply exceeds what the conduit carries below the walls' limit: pressure is pinned at ${ruptureMPa.toFixed(1)} MPa and the excess goes into dikes (or chamber growth when dikes are off).`,
    };
  }
  const close = Math.abs(overpressureMPa - balance) <= Math.max(0.02 * balance, 0.05);
  return close
    ? { state: 'steady', text: `Steady: outflow balances supply at ${balance.toFixed(1)} MPa — it can erupt like this for as long as the supply lasts.` }
    : { state: 'settling', text: `Settling towards ${balance.toFixed(1)} MPa, where outflow will balance supply.` };
}
