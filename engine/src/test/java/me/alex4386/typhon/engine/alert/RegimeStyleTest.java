package me.alex4386.typhon.engine.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import me.alex4386.typhon.engine.testing.StubMagmaState;
import me.alex4386.typhon.engine.volcano.EruptiveRegime;
import org.junit.jupiter.api.Test;

class RegimeStyleTest {
    @Test
    void conduitRegimeDecidesTheStyleOfAnOngoingEruption() {
        StubMagmaState magma = StubMagmaState.wetDacite();
        magma.eruptionRate = 10;
        assertEquals(EruptionStyle.HAWAIIAN, EruptionStyleClassifier.fromRegime(EruptiveRegime.FOUNTAINING, magma));
        assertEquals(EruptionStyle.STROMBOLIAN, EruptionStyleClassifier.fromRegime(EruptiveRegime.OPEN_VENT, magma));
        assertEquals(EruptionStyle.LAVA_DOME, EruptionStyleClassifier.fromRegime(EruptiveRegime.DOME, magma),
                "wet chamber magma that outgassed on the way up builds a dome");
        assertEquals(EruptionStyle.VULCANIAN, EruptionStyleClassifier.fromRegime(EruptiveRegime.EXPLOSIVE, magma));
        magma.eruptionRate = 8000;
        assertEquals(EruptionStyle.PLINIAN, EruptionStyleClassifier.fromRegime(EruptiveRegime.EXPLOSIVE, magma));
        assertNull(EruptionStyleClassifier.fromRegime(EruptiveRegime.UNKNOWN, magma));
    }

    @Test
    void magmaWithoutConduitModelFallsBackToComposition() {
        StubMagmaState magma = StubMagmaState.wetDacite();
        magma.eruptionRate = 50;
        assertEquals(EruptionStyleClassifier.classify(
                me.alex4386.typhon.engine.magma.MeltViscosity.log10(magma.silica, magma.water, magma.temperature, magma.crystals),
                magma.water, magma.eruptionRate), EruptionStyleClassifier.classify(magma));
    }
}
