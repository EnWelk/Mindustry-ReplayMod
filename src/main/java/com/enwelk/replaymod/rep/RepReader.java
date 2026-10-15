package com.enwelk.replaymod.rep;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import arc.files.Fi;
import arc.util.io.FastDeflaterOutputStream;
import com.enwelk.replaymod.Action;
import com.enwelk.replaymod.ReplayMod;
import mindustry.io.SaveIO;

public class RepReader implements Closeable {
    private static final int MAX_MAP_SIZE = 256 * 1024 * 1024;
    private static final int MAX_RECORD_SIZE = 64 * 1024 * 1024;

    private final FileChannel channel;
    private final ByteBuffer inputBuffer = ByteBuffer.allocate(64 * 1024);
    private final float version;

    public RepReader(Path filePath) throws IOException {
        channel = FileChannel.open(filePath, StandardOpenOption.READ);
        inputBuffer.limit(0);
        try {
            version = readHeader();
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    public float version() {
        return version;
    }

    private float readHeader() throws IOException {
        ByteBuffer header = ByteBuffer.allocate(3 + Float.BYTES + Integer.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        readFully(header, false);
        header.flip();

        if (header.get() != 'R' || header.get() != 'E' || header.get() != 'P') {
            throw new IOException("Invalid replay header");
        }

        float fileVersion = header.getFloat();
        if (Float.compare(fileVersion, ReplayMod.replayVersion()) != 0 &&
            Float.compare(fileVersion, 1f) != 0) {
            throw new IOException("Unsupported replay version: " + fileVersion);
        }

        int mapDataSize = header.getInt();
        if (mapDataSize <= 0 || mapDataSize > MAX_MAP_SIZE) {
            throw new IOException("Invalid replay map size: " + mapDataSize);
        }

        byte[] mapData = new byte[mapDataSize];
        readFully(ByteBuffer.wrap(mapData), false);
        Path tempPath = Files.createTempFile("replay-map", ".msav");
        try {
            if (isUncompressedSave(mapData)) {
                try (OutputStream output = new FastDeflaterOutputStream(Files.newOutputStream(tempPath))) {
                    output.write(mapData);
                }
            } else {
                Files.write(tempPath, mapData);
            }
            SaveIO.load(new Fi(tempPath.toFile()));
        } finally {
            Files.deleteIfExists(tempPath);
        }
        return fileVersion;
    }

    private boolean isUncompressedSave(byte[] data) {
        return data.length >= SaveIO.header.length &&
            data[0] == SaveIO.header[0] &&
            data[1] == SaveIO.header[1] &&
            data[2] == SaveIO.header[2] &&
            data[3] == SaveIO.header[3];
    }

    public ReplayRecord readNext() throws IOException {
        ByteBuffer recordHeader = ByteBuffer.allocate(1 + Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        if (!readFully(recordHeader, true)) {
            return null;
        }
        recordHeader.flip();

        byte type = recordHeader.get();
        int payloadSize = recordHeader.getInt();
        if (payloadSize < Long.BYTES || payloadSize > MAX_RECORD_SIZE) {
            throw new IOException("Invalid replay record size: " + payloadSize);
        }

        ByteBuffer payload = ByteBuffer.allocate(payloadSize).order(ByteOrder.LITTLE_ENDIAN);
        readFully(payload, false);
        payload.flip();

        try {
            if (type == RepWriter.TICK_RECORD) return readTick(payload);
            if (type == RepWriter.ACTION_RECORD) return readActions(payload);
            if (type == RepWriter.END_RECORD) {
                if (payload.remaining() != Long.BYTES) {
                    throw new IOException("Invalid replay end record size");
                }
                return new EndRecord(payload.getLong());
            }
            throw new IOException("Unknown replay record type: " + type);
        } catch (RuntimeException e) {
            throw new IOException("Malformed replay record", e);
        }
    }

    private TickRecord readTick(ByteBuffer payload) throws IOException {
        boolean hasCamera = Float.compare(version, 1f) != 0;
        int expectedSize = Long.BYTES + (hasCamera ? 7 : 4) * Float.BYTES + 2 + 4;
        if (payload.remaining() != expectedSize) {
            throw new IOException("Invalid tick record size: " + payload.remaining());
        }

        long tick = payload.getLong();
        float movementX = payload.getFloat();
        float movementY = payload.getFloat();
        float aimX = payload.getFloat();
        float aimY = payload.getFloat();
        float cameraX = hasCamera ? payload.getFloat() : Float.NaN;
        float cameraY = hasCamera ? payload.getFloat() : Float.NaN;
        float cameraScale = hasCamera ? payload.getFloat() : Float.NaN;
        if (!Float.isFinite(movementX) || !Float.isFinite(movementY) ||
            !Float.isFinite(aimX) || !Float.isFinite(aimY) ||
            (hasCamera && (!Float.isFinite(cameraX) || !Float.isFinite(cameraY) ||
                !Float.isFinite(cameraScale) || cameraScale <= 0f))) {
            throw new IOException("Invalid non-finite replay control value");
        }
        byte shooting = payload.get();
        byte boosting = payload.get();
        if ((shooting != 0 && shooting != 1) || (boosting != 0 && boosting != 1)) {
            throw new IOException("Invalid replay control flags");
        }
        short mineX = payload.getShort();
        short mineY = payload.getShort();
        if ((mineX == -1) != (mineY == -1) || mineX < -1 || mineY < -1) {
            throw new IOException("Invalid replay mining tile");
        }
        return new TickRecord(tick, movementX, movementY, aimX, aimY,
            shooting == 1, boosting == 1, mineX, mineY,
            cameraX, cameraY, cameraScale);
    }

    private ActionRecord readActions(ByteBuffer payload) throws IOException {
        if (payload.remaining() < Long.BYTES + Short.BYTES) {
            throw new IOException("Truncated action record");
        }
        long tick = payload.getLong();
        short count = payload.getShort();
        if (count < 0) {
            throw new IOException("Invalid action count: " + count);
        }
        List<Action> actions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            actions.add(Action.read(payload));
        }
        if (payload.hasRemaining()) {
            throw new IOException("Unexpected trailing data in action record");
        }
        return new ActionRecord(tick, List.copyOf(actions));
    }

    private boolean readFully(ByteBuffer buffer, boolean allowCleanEof) throws IOException {
        boolean readAny = false;
        while (buffer.hasRemaining()) {
            if (!inputBuffer.hasRemaining()) {
                inputBuffer.clear();
                int count = channel.read(inputBuffer);
                if (count < 0) {
                    if (allowCleanEof && !readAny) return false;
                    throw new IOException("Unexpected end of replay file");
                }
                if (count == 0) continue;
                inputBuffer.flip();
            }

            int count = Math.min(buffer.remaining(), inputBuffer.remaining());
            ByteBuffer slice = inputBuffer.slice();
            slice.limit(count);
            buffer.put(slice);
            inputBuffer.position(inputBuffer.position() + count);
            readAny = true;
        }
        return true;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    public interface ReplayRecord {
        long tick();
    }

    public record TickRecord(long tick, float movementX, float movementY, float aimX, float aimY,
                             boolean shooting, boolean boosting, short mineX, short mineY,
                             float cameraX, float cameraY, float cameraScale)
        implements ReplayRecord {}

    public record ActionRecord(long tick, List<Action> actions) implements ReplayRecord {}

    public record EndRecord(long tick) implements ReplayRecord {}
}
