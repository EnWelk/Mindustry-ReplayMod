package com.enwelk.replaymod.rep;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RepReaderRandomStateTests {

    /** Checks the two recorded RNG seeds are decoded from the replay header. */
    @Test
    void decodesRandomState() throws IOException {
        long seed0 = 0x123456789ABCDEFL;
        long seed1 = 0xFEDCBA9876543210L;
        ByteBuffer state = ByteBuffer.allocate(Long.BYTES * 2).order(ByteOrder.LITTLE_ENDIAN);
        state.putLong(seed0).putLong(seed1).flip();

        RepReader.RandomState decoded = RepReader.decodeRandomState(state);

        assertEquals(seed0, decoded.seed0());
        assertEquals(seed1, decoded.seed1());
    }

    /** Rejects invalid seed layouts and an unusable all-zero RNG state. */
    @Test
    void rejectsMalformedRandomState() {
        ByteBuffer badSize = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        badSize.putLong(1L).flip();
        assertThrows(IOException.class, () -> RepReader.decodeRandomState(badSize));

        ByteBuffer zeroState = ByteBuffer.allocate(Long.BYTES * 2).order(ByteOrder.LITTLE_ENDIAN);
        zeroState.putLong(0L).putLong(0L).flip();
        assertThrows(IOException.class, () -> RepReader.decodeRandomState(zeroState));
    }

    /** Checks an update checkpoint preserves its tick and generator state. */
    @Test
    void decodesRandomRecord() throws IOException {
        long tick = 42L;
        long seed0 = 0x123456789ABCDEFL;
        long seed1 = 0xFEDCBA9876543210L;
        ByteBuffer record = ByteBuffer.allocate(Long.BYTES * 2).order(ByteOrder.LITTLE_ENDIAN);
        record.putLong(seed0).putLong(seed1).flip();

        RepReader.RandomRecord decoded = RepReader.decodeRandomRecord(tick, record);

        assertEquals(tick, decoded.tick());
        assertEquals(seed0, decoded.seed0());
        assertEquals(seed1, decoded.seed1());
    }

    /** Rejects invalid checkpoint lengths and an unusable all-zero RNG state. */
    @Test
    void rejectsMalformedRandomRecord() {
        ByteBuffer shortRecord = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        assertThrows(IOException.class, () -> RepReader.decodeRandomRecord(3L, shortRecord));

        ByteBuffer zeroState = ByteBuffer.allocate(Long.BYTES * 2).order(ByteOrder.LITTLE_ENDIAN);
        zeroState.putLong(0L).putLong(0L).flip();
        assertThrows(IOException.class, () -> RepReader.decodeRandomRecord(3L, zeroState));
    }
}


