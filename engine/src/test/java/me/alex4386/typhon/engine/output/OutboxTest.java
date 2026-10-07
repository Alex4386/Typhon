package me.alex4386.typhon.engine.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OutboxTest {
    record Note(double time, String text) implements EngineEvent {}

    @Test
    void preservesEmitOrderAndClearsOnDrain() {
        Outbox outbox = new Outbox();
        outbox.emit(new Note(0, "a"));
        outbox.emit(new Note(0, "b"));

        EngineFrame frame = outbox.drain(3, 0);
        assertEquals(3, frame.step());
        assertEquals(List.of(new Note(0, "a"), new Note(0, "b")), frame.events());
        assertTrue(outbox.drain(4, 0).isEmpty());
    }

    @Test
    void absorbAppendsAndEmptiesTheOther() {
        Outbox outbox = new Outbox();
        Outbox lane = new Outbox();
        outbox.emit(new Note(0, "a"));
        lane.emit(new Note(0, "b"));
        outbox.absorb(lane);
        assertEquals(List.of(new Note(0, "a"), new Note(0, "b")), outbox.drain(0, 0).events());
        assertTrue(lane.drain(0, 0).isEmpty());
    }
}
