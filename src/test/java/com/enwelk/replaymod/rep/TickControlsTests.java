package com.enwelk.replaymod.rep;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TickControlsTests {
    @Test
    void packsMovementAndFlagsIntoOneInteger() throws IOException {
        for (boolean shooting : new boolean[]{false, true}) {
            for (boolean boosting : new boolean[]{false, true}) {
                TickControls decoded = TickControls.decode(
                    TickControls.encode(-0.375f, 0.8125f, shooting, boosting)
                );
                assertEquals(-0.375f, decoded.movementX(), 0.0001f);
                assertEquals(0.8125f, decoded.movementY(), 0.0001f);
                assertEquals(shooting, decoded.shooting());
                assertEquals(boosting, decoded.boosting());
            }
        }
    }

    @Test
    void rejectsReservedMovementValues() {
        assertThrows(IOException.class, () -> TickControls.decode(0x4000));
        assertThrows(IOException.class, () -> TickControls.decode(0x20000000));
    }
}
