package me.alex4386.typhon.server;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.math.BlockPos;
import org.junit.jupiter.api.Test;

class TuningTest {
    @Test
    void sensibleRangesWarnOnlyOutsideThem() {
        assertNull(Tuning.advise("world:scaling.eruptiveTimeCompression", "x", 500), "retired: no time compression");
        assertNull(Tuning.META.get("volcano:timeCompression.eruptive"));
        assertNull(Tuning.advise("volcano:magma.chamber.supplyRate", "x", 0.3));
        assertNotNull(Tuning.advise("volcano:magma.chamber.supplyRate", "x", 50));
        assertNull(Tuning.advise("volcano:magma.chamber.coolingTimescale", "x", 1e30), "no advice, no warning");
        for (String key : Tuning.ADVICE.keySet()) {
            Tuning.Meta meta = Tuning.META.get(key);
            assertNotNull(meta, key + " has advice but no hard range");
            Tuning.Advice a = Tuning.ADVICE.get(key);
            if (a.high() != null && meta.max() != null) assertTrue(a.high() <= meta.max(), key);
            if (a.low() != null && meta.min() != null) assertTrue(a.low() >= meta.min(), key);
        }
    }

    @Test
    void largeInjectionsAreFlagged() {
        MagmaChamber chamber = new MagmaChamber(MagmaChamberConfig.builder("v", new BlockPos(0, -40, 0)).volume(5e7).build());
        assertNull(Tuning.injectionWarning(1e6, chamber));
        String note = Tuning.injectionWarning(1e9, chamber);
        assertNotNull(note);
        assertTrue(note.contains("rupture"), note);
    }
}
