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
import arc.math.Mathf;
import arc.util.io.FastDeflaterOutputStream;
import mindustry.io.SaveIO;

public class RepReader implements Closeable {
    private static final int MAX_MAP_SIZE = 256 * 1024 * 1024;
    private static final int MAX_RECORD_SIZE = 256 * 1024 * 1024;
    private static final int MAX_MOD_METADATA_SIZE = 1024 * 1024;
    private static final int MAX_MOD_COUNT = 4096;

    private final FileChannel channel;
    private final Path filePath;
    private final ByteBuffer inputBuffer = ByteBuffer.allocate(64 * 1024);
    private final float version;
    private List<String> usedMods = List.of();
    private long randomSeed0, randomSeed1;
    private boolean hasRandomState;
    private long recordsStart;
    private long firstTick = Long.MAX_VALUE;
    private long endTick;

    /** Opens a replay and reads its header and embedded map. */
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

    /** Returns the replay file format version. */
    public float version() {
        return version;
    }

    /** Returns mods recorded in the replay header. */
    public List<String> usedMods() {
        return usedMods;
    }

    /** Returns whether this replay contains a recorded global random state. */
    public boolean hasRandomState() {
        return hasRandomState;
    }

    /** Returns the first recorded random generator seed. */
    public long randomSeed0() {
        if (!hasRandomState) throw new IllegalStateException("Replay has no random state");
        return randomSeed0;
    }

    /** Returns the second recorded random generator seed. */
    public long randomSeed1() {
        if (!hasRandomState) throw new IllegalStateException("Replay has no random state");
        return randomSeed1;
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
            if (!isSupportedVersion(version)) {
                throw new IOException("Unsupported replay version: " + version);
            }
            int mapSize = header.getInt();
            if (mapSize <= 0 || mapSize > MAX_MAP_SIZE ||
                file.size() < 3L + Float.BYTES + Integer.BYTES + mapSize) {
                throw new IOException("Invalid replay map size");
            }

            file.position(3L + Float.BYTES + Integer.BYTES + mapSize);
            List<String> mods = readModMetadata(file);
            readRandomState(file);
            return mods;
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
            file.position(3L + Float.BYTES + Integer.BYTES + mapSize);
            readModMetadata(file);
            readRandomState(file);
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

    /** Reads the current-format header and seeds RNG before loading the map. */
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
        usedMods = readModMetadata();
        readRandomState();
        Mathf.rand.setState(randomSeed0, randomSeed1);
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

    /** Reads the recorded global RNG state from the replay header. */
    private void readRandomState() throws IOException {
        ByteBuffer state = ByteBuffer.allocate(Long.BYTES * 2).order(ByteOrder.LITTLE_ENDIAN);
        readFully(state, false);
        state.flip();
        RandomState randomState = decodeRandomState(state);
        randomSeed0 = randomState.seed0();
        randomSeed1 = randomState.seed1();
        hasRandomState = true;
    }

    /** Reads the fixed-width RNG state from a channel. */
    private static RandomState readRandomState(FileChannel file) throws IOException {
        ByteBuffer state = ByteBuffer.allocate(Long.BYTES * 2).order(ByteOrder.LITTLE_ENDIAN);
        readChannelFully(file, state);
        state.flip();
        return decodeRandomState(state);
    }

    /** Decodes the two fixed-width seeds in a replay header. */
    static RandomState decodeRandomState(ByteBuffer state) throws IOException {
        if (state.remaining() != Long.BYTES * 2) {
            throw new IOException("Invalid replay random state size");
        }
        long seed0 = state.getLong();
        long seed1 = state.getLong();
        if ((seed0 | seed1) == 0L) {
            throw new IOException("Invalid replay random state");
        }
        return new RandomState(seed0, seed1);
    }

    /** Reads mod metadata from this reader's current channel position. */
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

    /** Reads a fixed number of metadata bytes from this replay. */
    private byte[] readMetadataBytes(int size) throws IOException {
        ByteBuffer data = ByteBuffer.allocate(size);
        readFully(data, false);
        return data.array();
    }

    /** Reads mod metadata from a channel without creating a replay reader. */
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

    /** Decodes and validates the names in a replay's mod list. */
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

    /** Checks whether a replay format version is supported. */
    private static boolean isSupportedVersion(float version) {
        return Float.compare(version, ReplayMod.replayVersion()) == 0;
    }

    /** Fills a buffer from a file channel or reports truncation. */
    private static void readChannelFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw new IOException("Replay file is truncated");
        }
    }

    private interface MetadataReader {
        /** Reads exactly the requested number of metadata bytes. */
        byte[] readBytes(int size) throws IOException;

        /** Copies the requested metadata bytes into a buffer. */
        default void readFully(ByteBuffer buffer) throws IOException {
            buffer.put(readBytes(buffer.remaining()));
        }
    }

    /** Checks whether the embedded save uses Mindustry's uncompressed header. */
    private boolean isUncompressedSave(byte[] data) {
        return data.length >= SaveIO.header.length &&
            data[0] == SaveIO.header[0] &&
            data[1] == SaveIO.header[1] &&
            data[2] == SaveIO.header[2] &&
            data[3] == SaveIO.header[3];
    }

    /** Reads and decodes the next replay record, or returns null at clean EOF. */
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
            if (type == RepWriter.RANDOM_RECORD) return readRandomRecord(payload);
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

    /** Decodes one player-state record. */
    private TickRecord readTick(ByteBuffer payload) throws IOException {
        int expectedSize = Long.BYTES + 7 * Float.BYTES + 2 + 4;
        if (payload.remaining() != expectedSize) {
            throw new IOException("Invalid tick record size: " + payload.remaining());
        }

        long tick = payload.getLong();
        float movementX = payload.getFloat();
        float movementY = payload.getFloat();
        float aimX = payload.getFloat();
        float aimY = payload.getFloat();
        float cameraX = payload.getFloat();
        float cameraY = payload.getFloat();
        float cameraScale = payload.getFloat();
        if (!Float.isFinite(movementX) || !Float.isFinite(movementY) ||
            !Float.isFinite(aimX) || !Float.isFinite(aimY) ||
            !Float.isFinite(cameraX) || !Float.isFinite(cameraY) ||
            !Float.isFinite(cameraScale) || cameraScale <= 0f) {
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

    /** Decodes one action record and its serialized actions. */
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

    /** Reads a per-update random generator checkpoint. */
    private RandomRecord readRandomRecord(ByteBuffer payload) throws IOException {
        return decodeRandomRecord(payload);
    }

    /** Decodes a per-update random generator checkpoint. */
    static RandomRecord decodeRandomRecord(ByteBuffer payload) throws IOException {
        if (payload.remaining() != Long.BYTES * 3) {
            throw new IOException("Invalid replay random state record");
        }
        long tick = payload.getLong();
        long seed0 = payload.getLong();
        long seed1 = payload.getLong();
        if ((seed0 | seed1) == 0L) {
            throw new IOException("Invalid replay random state");
        }
        return new RandomRecord(tick, seed0, seed1);
    }

    /** Reads the requested bytes, optionally accepting a clean end of file. */
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
    /** Closes the replay file channel. */
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

    record RandomState(long seed0, long seed1) {}

    public record ActionRecord(long tick, List<Action> actions) implements ReplayRecord {}

    public record RandomRecord(long tick, long seed0, long seed1) implements ReplayRecord {}

    public record EndRecord(long tick) implements ReplayRecord {}
}
