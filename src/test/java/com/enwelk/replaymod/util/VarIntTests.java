package com.enwelk.replaymod.util;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VarIntTests {
    @Test
    void unsignedIntegersRoundTripIncludingBoundaryValues() {
        for (int value : new int[]{0, 1, 127, 128, 16_383, 16_384, Integer.MAX_VALUE, -1}) {
            ByteBuffer buffer = ByteBuffer.allocate(5);
            VarInt.write(buffer, value);
            assertEquals(VarInt.size(value), buffer.position());
            buffer.flip();
            assertEquals(value, VarInt.read(buffer));
            assertEquals(0, buffer.remaining());
        }
    }

    @Test
    void signedIntegersRoundTripIncludingExtremes() {
        for (int value : new int[]{0, -1, 1, -64, 63, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            ByteBuffer buffer = ByteBuffer.allocate(5);
            VarInt.writeSigned(buffer, value);
            assertEquals(VarInt.sizeSigned(value), buffer.position());
            buffer.flip();
            assertEquals(value, VarInt.readSigned(buffer));
        }
    }

    @Test
    void unsignedLongsRoundTripIncludingExtremes() {
        for (long value : new long[]{0L, 1L, 127L, 128L, Long.MAX_VALUE, Long.MIN_VALUE, -1L}) {
            ByteBuffer buffer = ByteBuffer.allocate(10);
            VarLong.write(buffer, value);
            assertEquals(VarLong.size(value), buffer.position());
            buffer.flip();
            assertEquals(value, VarLong.read(buffer));
        }
    }

    @Test
    void signedLongsRoundTripIncludingExtremes() {
        for (long value : new long[]{0L, -1L, 1L, -64L, 63L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            ByteBuffer buffer = ByteBuffer.allocate(10);
            VarLong.writeSigned(buffer, value);
            assertEquals(VarLong.sizeSigned(value), buffer.position());
            buffer.flip();
            assertEquals(value, VarLong.readSigned(buffer));
        }
    }

    @Test
    void malformedAndNonCanonicalValuesAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> VarInt.read(ByteBuffer.wrap(new byte[]{(byte)0x80, 0})));
        assertThrows(IllegalArgumentException.class,
            () -> VarInt.read(ByteBuffer.wrap(new byte[]{(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, 0x10})));
        assertThrows(IllegalArgumentException.class,
            () -> VarLong.read(ByteBuffer.wrap(new byte[]{(byte)0x80, 0})));
        assertThrows(IllegalArgumentException.class,
            () -> VarLong.read(ByteBuffer.wrap(new byte[]{
                (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF,
                (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, 0x02
            })));
    }
}
