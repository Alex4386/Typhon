package me.alex4386.typhon.engine.subsurface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WaterSaturationTest {
    @Test
    void saturationTemperatureMatchesTheSteamTables() {
        // IAPWS-IF97 verification values (Table 35) and steam-table points
        assertEquals(99.97, WaterSaturation.saturationTemperatureC(101_325), 0.02);
        assertEquals(120.21, WaterSaturation.saturationTemperatureC(0.2e6), 0.02);
        assertEquals(179.88, WaterSaturation.saturationTemperatureC(1e6), 0.02);
        assertEquals(311.00, WaterSaturation.saturationTemperatureC(10e6), 0.02);
        assertEquals(WaterSaturation.CRITICAL_TEMPERATURE_C, WaterSaturation.saturationTemperatureC(30e6), 1e-9);
    }

    @Test
    void waterBoilsCoolerAtAltitudeAndHotterAtDepth() {
        assertEquals(101_325, WaterSaturation.atmosphericPressurePa(0), 1e-6);
        // Old Faithful, ~2240 m: water boils at ~92-93 °C (Hurwitz & Manga 2017)
        assertEquals(92.5, WaterSaturation.boilingPointC(0, 2240), 0.7);
        // 10 m below a sea-level table: ~2 bar, ~120 °C
        assertEquals(120.4, WaterSaturation.boilingPointC(10, 0), 0.5);
        double previous = 0;
        for (double d = 0; d < 2000; d += 25) {
            double bp = WaterSaturation.boilingPointC(d, 0);
            assertTrue(bp > previous || bp == WaterSaturation.CRITICAL_TEMPERATURE_C);
            previous = bp;
        }
        // ~1 km: ~310 °C (Haas 1971 gives ~307 °C with the density of boiling water)
        assertEquals(311, WaterSaturation.boilingPointC(1000, 0), 3);
    }
}
