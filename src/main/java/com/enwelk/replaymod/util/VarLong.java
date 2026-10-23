package com.enwelk.replaymod.util;

import java.nio.ByteBuffer;

public final class VarLong {
    private VarLong() {}

    public static void write(ByteBuffer buffer, long value) {
        while ((value & ~0x7FL) != 0L) {
            buffer.put((byte)((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buffer.put((byte)value);
    }

    public static long read(ByteBuffer buffer) {
        long result = 0L;
        for (int index = 0; index < 10; index++) {
            int next = buffer.get() & 0xFF;
            if (index == 9 && (next & 0xFE) != 0) {
                throw new IllegalArgumentException("VarLong overflow");
            }
            result |= (long)(next & 0x7F) << (index * 7);
            if ((next & 0x80) == 0) {
                if (index > 0 && (next & 0x7F) == 0) {
                    throw new IllegalArgumentException("Non-canonical VarLong");
                }
                return result;
            }
        }
        throw new IllegalArgumentException("VarLong too long");
    }

    public static void writeSigned(ByteBuffer buffer, long value) {
        write(buffer, (value << 1) ^ (value >> 63));
    }

    public static long readSigned(ByteBuffer buffer) {
        long encoded = read(buffer);
        return (encoded >>> 1) ^ -(encoded & 1L);
    }

    public static int sizeSigned(long value) {
        return size((value << 1) ^ (value >> 63));
    }

    public static int size(long value) {
        int size = 1;
        while ((value & ~0x7FL) != 0L) {
            value >>>= 7;
            size++;
        }
        return size;
    }
}
