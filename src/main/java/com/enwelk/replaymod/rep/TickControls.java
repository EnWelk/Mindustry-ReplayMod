package com.enwelk.replaymod.rep;

import java.io.IOException;

record TickControls(float movementX, float movementY, boolean shooting, boolean boosting) {
    private static final float MOVEMENT_SCALE = 16_383f;

    static int encode(float movementX, float movementY, boolean shooting, boolean boosting) {
        int x = Math.round(Math.max(-1f, Math.min(1f, movementX)) * MOVEMENT_SCALE);
        int y = Math.round(Math.max(-1f, Math.min(1f, movementY)) * MOVEMENT_SCALE);
        return (x & 0x7FFF) |
            ((y & 0x7FFF) << 15) |
            (shooting ? 1 << 30 : 0) |
            (boosting ? 1 << 31 : 0);
    }

    static TickControls decode(int packed) throws IOException {
        int x = packed & 0x7FFF;
        int y = (packed >>> 15) & 0x7FFF;
        if (x == 0x4000 || y == 0x4000) {
            throw new IOException("Invalid replay movement");
        }
        return new TickControls(
            signExtend15(x) / MOVEMENT_SCALE,
            signExtend15(y) / MOVEMENT_SCALE,
            (packed & (1 << 30)) != 0,
            (packed & (1 << 31)) != 0
        );
    }

    private static int signExtend15(int value) {
        return (value & 0x4000) == 0 ? value : value - 0x8000;
    }
}
