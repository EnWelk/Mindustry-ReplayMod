package com.enwelk.replaymod.rep;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import com.enwelk.replaymod.Action;
import com.enwelk.replaymod.ReplayMod;

import arc.files.Fi;
import arc.util.io.FastDeflaterOutputStream;
import mindustry.io.SaveIO;

public class RepReader implements Closeable {
    private static final int MAX_MAP_SIZE = 256 * 1024 * 1024;
    private static final int MAX_RECORD_SIZE = 64 * 1024 * 1024;
    private static final int MAX_MOD_METADATA_SIZE = 1024 * 1024;
    private static final int MAX_MOD_COUNT = 4096;

    private final FileChannel channel;
    private final Path filePath;
    private final ByteBuffer inputBuffer = ByteBuffer.allocate(64 * 1024);
    private final float version;
    private List<String> usedMods = List.of();
    private long recordsStart;
    private long firstTick = Long.MAX_VALUE;
    private long endTick;

    public RepReader(Path filePath) throws IOException {
        this.filePath = filePath;
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

    public List<String> usedMods() {
        return usedMods;
    }

    /** Reads replay mod metadata without loading the embedded save into the active world. */
    public static List<String> readUsedMods(Path filePath) throws IOException {
        try (FileChannel file = FileChannel.open(filePath, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(3 + Float.BYTES + Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
            readChannelFully(file, header);
            header.flip();
            if (header.get() != 'R' || header.get() != 'E' || header.get() != 'P') {
                throw new IOException("Invalid replay header");
            }
            float version = header.getFloat();
            int mapSize = header.getInt();
            if (mapSize <= 0 || mapSize > MAX_MAP_SIZE ||
                file.size() < 3L + Float.BYTES + Integer.BYTES + mapSize) {
                throw new IOException("Invalid replay map size");
            }
            if (Float.compare(version, 3f) < 0) return List.of();
            if (Float.compare(version, 3f) > 0) {
                throw new IOException("Unsupported replay version: " + version);
            }

            file.position(3L + Float.BYTES + Integer.BYTES + mapSize);
            return readModMetadata(file);
        }
    }

    /** Validates an import's framing without loading its embedded map into the active world. */
    public static void validateForImport(Path filePath) throws IOException {
        try (FileChannel file = FileChannel.open(filePath, StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(3 + Float.BYTES + Integer.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
            while (header.hasRemaining()) {
                if (file.read(header) < 0) throw new IOException("Replay file is truncated");
            }
            header.flip();
            if (header.get() != 'R' || header.get() != 'E' || header.get() != 'P') {
                throw new IOException("Invalid replay header");
            }
            float version = header.getFloat();
            if (!isSupportedVersion(version)) {
                throw new IOException("Unsupported replay version: " + version);
            }
            int mapSize = header.getInt();
            if (mapSize <= 0 || mapSize > MAX_MAP_SIZE ||
                file.size() < 3L + Float.BYTES + Integer.BYTES + mapSize + 1L + Integer.BYTES + Long.BYTES) {
                throw new IOException("Invalid or truncated replay map data");
            }
            if (Float.compare(version, 3f) >= 0) {
                file.position(3L + Float.BYTES + Integer.BYTES + mapSize);
                readModMetadata(file);
            }
        }
    }

    /** Scans the replay timeline without retaining every record in memory. */
    public void scanTimeline() throws IOException {
        resetRecords();
        firstTick = Long.MAX_VALUE;
        endTick = 0L;
        boolean foundEnd = false;

        ReplayRecord record;
        while ((record = readNext()) != null) {
            if (record instanceof EndRecord end) {
                endTick = end.tick();
                foundEnd = true;
                break;
            }
            firstTick = Math.min(firstTick, record.tick());
            endTick = Math.max(endTick, record.tick());
        }
        if (!foundEnd) {
            throw new IOException("Replay is missing its end record");
        }
        if (firstTick == Long.MAX_VALUE) {
            firstTick = 0L;
        }
        if (endTick < firstTick) {
            throw new IOException("Invalid replay timeline");
        }
        resetRecords();
    }

    /** Returns the first recorded game tick, used as the replay's time origin. */
    public long firstTick() {
        return firstTick;
    }

    /** Returns the end tick in the original replay timeline. */
    public long endTick() {
        return endTick;
    }

    /** Rewinds record reading to the first record after the embedded save. */
    private void resetRecords() throws IOException {
        channel.position(recordsStart);
        inputBuffer.position(0);
        inputBuffer.limit(0);
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
        if (!isSupportedVersion(fileVersion)) {
            throw new IOException("Unsupported replay version: " + fileVersion);
        }

        int mapDataSize = header.getInt();
        if (mapDataSize <= 0 || mapDataSize > MAX_MAP_SIZE) {
            throw new IOException("Invalid replay map size: " + mapDataSize);
        }

        byte[] mapData = new byte[mapDataSize];
        readFully(ByteBuffer.wrap(mapData), false);
        if (Float.compare(fileVersion, 3f) >= 0) {
            usedMods = readModMetadata();
        }
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
        recordsStart = channel.position() - inputBuffer.remaining();
        return fileVersion;
    }

    private List<String> readModMetadata() throws IOException {
        ByteBuffer metadataHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        readFully(metadataHeader, false);
        metadataHeader.flip();
        if (metadataHeader.get() != 'M' || metadataHeader.get() != 'O' ||
            metadataHeader.get() != 'D' || metadataHeader.get() != 'S') {
            throw new IOException("Invalid replay mod metadata header");
        }
        return readModNames(metadataHeader.getInt(), this::readMetadataBytes);
    }

    private byte[] readMetadataBytes(int size) throws IOException {
        ByteBuffer data = ByteBuffer.allocate(size);
        readFully(data, false);
        return data.array();
    }

    private static List<String> readModMetadata(FileChannel file) throws IOException {
        ByteBuffer metadataHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        readChannelFully(file, metadataHeader);
        metadataHeader.flip();
        if (metadataHeader.get() != 'M' || metadataHeader.get() != 'O' ||
            metadataHeader.get() != 'D' || metadataHeader.get() != 'S') {
            throw new IOException("Invalid replay mod metadata header");
        }
        return readModNames(metadataHeader.getInt(), size -> {
            ByteBuffer data = ByteBuffer.allocate(size);
            readChannelFully(file, data);
            return data.array();
        });
    }

    private static List<String> readModNames(int count, MetadataReader reader) throws IOException {
        if (count < 0 || count > MAX_MOD_COUNT) {
            throw new IOException("Invalid replay mod count: " + count);
        }
        List<String> mods = new ArrayList<>(count);
        int totalSize = 8;
        for (int i = 0; i < count; i++) {
            ByteBuffer length = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            reader.readFully(length);
            length.flip();
            int size = length.getInt();
            if (size <= 0 || size > 512) {
                throw new IOException("Invalid replay mod metadata size");
            }
            totalSize = Math.addExact(totalSize, Integer.BYTES + size);
            if (totalSize > MAX_MOD_METADATA_SIZE) {
                throw new IOException("Replay mod metadata is too large");
            }
            mods.add(new String(reader.readBytes(size), StandardCharsets.UTF_8));
        }
        return List.copyOf(mods);
    }

    private static boolean isSupportedVersion(float version) {
        return Float.compare(version, ReplayMod.replayVersion()) == 0 ||
            Float.compare(version, 1f) == 0 ||
            Float.compare(version, 2f) == 0;
    }

    private static void readChannelFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw new IOException("Replay file is truncated");
        }
    }

    private interface MetadataReader {
        byte[] readBytes(int size) throws IOException;

        default void readFully(ByteBuffer buffer) throws IOException {
            buffer.put(readBytes(buffer.remaining()));
        }
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
