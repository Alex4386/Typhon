package me.alex4386.typhon.engine.massflow;

/**
 * Notified whenever a flow field lays down deposit, e.g. so fresh PDC deposits become erodible
 * material for {@link Lahars} ({@code pdc.setDepositListener(lahars::addErodibleDeposit)}).
 *
 * <p>Wiring is not persisted: re-attach listeners when rebuilding an engine.
 */
@FunctionalInterface
public interface DepositListener {
    /**
     * @param x column x
     * @param z column z
     * @param thicknessM deposit thickness added to the column, in real metres
     */
    void deposited(int x, int z, double thicknessM);
}
