package me.alex4386.typhon.engine.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The style estimate reads observable behaviour (rates in kg/s, bursts per hour), not magma inputs. */
class EruptionClassifierTest {
    private static EruptionStyle top(EnumMap<EruptionStyle, Double> m) {
        EruptionStyle best = EruptionStyle.MIXED;
        double value = 0;
        double sum = 0;
        for (Map.Entry<EruptionStyle, Double> e : m.entrySet()) {
            sum += e.getValue();
            if (e.getValue() > value) {
                value = e.getValue();
                best = e.getKey();
            }
        }
        return value / sum >= EruptionClassifier.MIXED_BELOW ? best : EruptionStyle.MIXED;
    }

    private static EruptionStyle style(double magma, double lava, double column, double collapse, double ballistic,
            double wet, double eta, double slugsPerHour, double plugsPerHour, double submerged) {
        return top(EruptionClassifier.memberships(magma, lava, column, collapse, ballistic, wet, eta, slugsPerHour,
                plugsPerHour, submerged));
    }

    @Test
    void lavaFountainsAndFlowsAreHawaiian() {
        assertEquals(EruptionStyle.HAWAIIAN, style(2.5e5, 2.2e5, 1e4, 0, 2e4, 0, 2.5, 0, 0, 0));
    }

    @Test
    void rhythmicSlugBurstsAreStrombolian() {
        assertEquals(EruptionStyle.STROMBOLIAN, style(0, 0, 0, 0, 0, 0, 2.5, 30, 0, 0));
        assertEquals(EruptionStyle.STROMBOLIAN, style(50, 40, 0, 0, 10, 0, 3, 30, 0, 0), "even with a little lava");
    }

    @Test
    void columnHeightSeparatesSubplinianFromPlinian() {
        assertEquals(EruptionStyle.SUBPLINIAN, style(2.6e6, 0, 2.5e6, 0, 1e5, 0, 7, 0, 0, 0));
        assertEquals(EruptionStyle.PLINIAN, style(5e7, 0, 4.9e7, 0, 1e6, 0, 7, 0, 0, 0));
    }

    @Test
    void stiffLavaIsADomeUntilItsPlugBlowsOut() {
        assertEquals(EruptionStyle.LAVA_DOME, style(250, 250, 0, 0, 0, 0, 10, 0, 0, 0));
        assertEquals(EruptionStyle.VULCANIAN, style(250, 250, 0, 0, 0, 0, 10, 0, 1, 0));
    }

    @Test
    void collapsingLowColumnsArePelean() {
        assertEquals(EruptionStyle.PELEAN, style(6e4, 1e4, 5e4, 4e4, 0, 0, 9, 0, 0, 0));
    }

    @Test
    void waterFragmentedEruptionsAreSurtseyan() {
        assertEquals(EruptionStyle.SURTSEYAN, style(1.5e6, 0, 5e5, 0, 1e5, 9e5, 1.6, 0, 0, 1));
    }

    @Test
    void veiFollowsTephraVolumeAndColumnHeight() {
        assertEquals(0, EruptionClassifier.volumeVei(1e3));
        assertEquals(1, EruptionClassifier.volumeVei(1e5));
        assertEquals(2, EruptionClassifier.volumeVei(5e6));
        assertEquals(4, EruptionClassifier.volumeVei(2e8));
        assertEquals(5, EruptionClassifier.volumeVei(1e9 * 1.2));
        assertEquals(6, EruptionClassifier.volumeVei(1e10));
        assertEquals(3, EruptionClassifier.heightVei(10));
        assertEquals(5, EruptionClassifier.heightVei(30));
        assertTrue(EruptionClassifier.columnHeightKm(1.6e8 * 2500 / 2500) > 20, "Mastin: 1.6e8 kg/s is Plinian");
    }
}
