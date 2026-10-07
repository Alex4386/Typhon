package me.alex4386.typhon.engine.testing;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.volcano.MagmaState;

/** Mutable {@link MagmaState} for tests of subsystems that read magma state. */
public final class StubMagmaState implements MagmaState {
    public BlockPos center = new BlockPos(0, -40, 0);
    public double overpressure;
    public double overpressureRate;
    public double temperature = 1150;
    public double silica = 50;
    public double water = 1.0;
    public double crystals = 0.05;
    public double eruptionRate;

    public static StubMagmaState basalt() {
        return new StubMagmaState();
    }

    /** Wet, crystal-bearing dacite: viscous and explosive. */
    public static StubMagmaState wetDacite() {
        StubMagmaState s = new StubMagmaState();
        s.temperature = 880;
        s.silica = 66;
        s.water = 4.5;
        s.crystals = 0.3;
        return s;
    }

    @Override public Point3 chamberCenter() { return Point3.ofBlock(center, 1); } // 1-m blocks
    @Override public double overpressureMPa() { return overpressure; }
    @Override public double overpressureRateMPaPerSecond() { return overpressureRate; }
    @Override public double temperatureC() { return temperature; }
    @Override public double silicaWt() { return silica; }
    @Override public double waterWt() { return water; }
    @Override public double crystalFraction() { return crystals; }
    @Override public double eruptionRate() { return eruptionRate; }
}
