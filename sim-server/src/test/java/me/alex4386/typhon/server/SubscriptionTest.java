package me.alex4386.typhon.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import me.alex4386.typhon.server.protocol.Field;
import org.junit.jupiter.api.Test;

/** Re-subscribing keeps what the client holds of fields and levels that stay subscribed (§3.1, §5.4). */
class SubscriptionTest {
    private static ClientConnection connection() {
        return new ClientConnection("t", new ClientConnection.Sender() {
            @Override public void text(String json) {}

            @Override public void binary(byte[] frame) {}

            @Override public boolean open() {
                return true;
            }
        });
    }

    @Test
    void resubscribeKeepsHeldTilesOfRemainingFieldsAndLevels() {
        ClientConnection c = connection();
        c.subscribe(EnumSet.of(Field.SURFACE_ELEVATION, Field.LAVA_DEPTH), null, Set.of(1, -2));
        c.sentVersions.put(Field.SURFACE_ELEVATION, new long[] {3, 4});
        c.sentVersions.put(Field.LAVA_DEPTH, new long[] {1, 1});
        for (int level : new int[] {1, -2}) {
            Map<Field, long[]> held = new EnumMap<>(Field.class);
            held.put(Field.SURFACE_ELEVATION, new long[] {2});
            c.lodSent.put(level, held);
        }
        c.tilesSent = 40;
        c.tilesAcked = 10;

        // drop the detail level and the lava field
        c.subscribe(EnumSet.of(Field.SURFACE_ELEVATION), null, Set.of(1));

        assertTrue(c.sentVersions.containsKey(Field.SURFACE_ELEVATION), "elevation held at level 0 is not resent");
        assertFalse(c.sentVersions.containsKey(Field.LAVA_DEPTH), "an unsubscribed field is forgotten");
        assertTrue(c.lodSent.containsKey(1), "the context level is still held");
        assertFalse(c.lodSent.containsKey(-2), "a dropped level is forgotten and sent in full when asked again");
        assertEquals(0, c.tilesSent, "the credit window restarts");
        assertEquals(0, c.tilesAcked);

        // attaching subscribes to nothing: everything is forgotten
        c.subscribe(Set.of(), null);
        assertTrue(c.sentVersions.isEmpty());
        assertTrue(c.lodSent.isEmpty());
    }
}
