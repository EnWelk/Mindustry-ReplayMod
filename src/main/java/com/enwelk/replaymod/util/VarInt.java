package com.enwelk.replaymod.util;

import java.nio.ByteBuffer;

public final class VarInt {
    private VarInt() {}

    public static void write(ByteBuffer buffer, int value) {
        while ((value & ~0x7F) != 0) {
            buffer.put((byte)((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buffer.put((byte)value);
    }

    public static int read(ByteBuffer buffer) {
        int result = 0;
        for (int index = 0; index < 5; index++) {
            int next = buffer.get() & 0xFF;
            if (index == 4 && (next & 0xF0) != 0) {
                throw new IllegalArgumentException("VarInt overflow");
            }
            result |= (next & 0x7F) << (index * 7);
            if ((next & 0x80) == 0) {
                if (index > 0 && (next & 0x7F) == 0) {
                    throw new IllegalArgumentException("Non-canonical VarInt");
                }
                return result;
            }
        }
        throw new IllegalArgumentException("VarInt too long");
    }

    public static void writeSigned(ByteBuffer buffer, int value) {
        write(buffer, (value << 1) ^ (value >> 31));
    }

    public static int readSigned(ByteBuffer buffer) {
        int encoded = read(buffer);
        return (encoded >>> 1) ^ -(encoded & 1);
    }

    public static int sizeSigned(int value) {
        return size((value << 1) ^ (value >> 31));
    }

    public static int size(int value) {
        int size = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            size++;
        }
        return size;
    }
}
