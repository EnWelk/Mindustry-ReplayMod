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
import com.enwelk.replaymod.util.VarInt;
import com.enwelk.replaymod.util.VarLong;

import arc.files.Fi;
import arc.math.Mathf;
import arc.util.io.FastDeflaterOutputStream;
import mindustry.io.SaveIO;

public class RepReader implements Closeable {
    private static final int MAX_MAP_SIZE = 32 * 1024 * 1024;
    private static final int MAX_RECORD_SIZE = 256 * 1024 * 1024;
    private static final int MAX_MOD_METADATA_SIZE = 1024 * 1024;
    private static final int MAX_MOD_COUNT = 4096;

    private final FileChannel channel;
    private final Path filePath;
    private final ByteBuffer inputBuffer = ByteBuffer.allocate(64 * 1024);
    private final ByteBuffer singleByte = ByteBuffer.allocate(1);
    private final float version;
    private List<String> usedMods = List.of();
    private boolean velocityRecorded;
    private long randomSeed0, randomSeed1;
    private boolean hasRandomState;
    private long recordsStart;
    private long firstTick = Long.MAX_VALUE;
    private long endTick;
    private long lastRecordTick;
    private int lastAimX;
    private int lastAimY;

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

    public boolean velocityRecorded() {
        return velocityRecorded;
    }

    static boolean hasVelocityMarker(List<String> mods) {
        return mods.contains(RepWriter.VELOCITY_RECORDING_MARKER);
    }

    static List<String> withoutVelocityMarker(List<String> mods) {
        if (!hasVelocityMarker(mods)) return mods;
        List<String> filtered = new ArrayList<>(mods);
        filtered.remove(RepWriter.VELOCITY_RECORDING_MARKER);
        return List.copyOf(filtered);
    }

    public boolean hasRandomState() {
        return hasRandomState;
    }

    public long randomSeed0() {
        if (!hasRandomState) throw new IllegalStateException("Replay has no random state");
        return randomSeed0;
    }

    public long randomSeed1() {
        if (!hasRandomState) throw new IllegalStateException("Replay has no random state");
        return randomSeed1;
    }

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

    public long firstTick() {
        return firstTick;
    }

    public long endTick() {
        return endTick;
    }

    private void resetRecords() throws IOException {
        channel.position(recordsStart);
        inputBuffer.position(0);
        inputBuffer.limit(0);
        lastRecordTick = 0L;
        lastAimX = 0;
        lastAimY = 0;
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
        usedMods = readModMetadata();
        readRandomState();

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

    private void readRandomState() throws IOException {
        ByteBuffer state = ByteBuffer.allocate(Long.BYTES * 2).order(ByteOrder.LITTLE_ENDIAN);
        readFully(state, false);
        state.flip();
        RandomState randomState = decodeRandomState(state);
        randomSeed0 = randomState.seed0();
        randomSeed1 = randomState.seed1();
        hasRandomState = true;
    }

    private static RandomState readRandomState(FileChannel file) throws IOException {
        ByteBuffer state = ByteBuffer.allocate(Long.BYTES * 2).order(ByteOrder.LITTLE_ENDIAN);
        readChannelFully(file, state);
        state.flip();
        return decodeRandomState(state);
    }

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

    private List<String> readModMetadata() throws IOException {
        ByteBuffer metadataHeader = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        readFully(metadataHeader, false);
        metadataHeader.flip();
        if (metadataHeader.get() != 'M' || metadataHeader.get() != 'O' ||
            metadataHeader.get() != 'D' || metadataHeader.get() != 'S') {
            throw new IOException("Invalid replay mod metadata header");
        }
        List<String> mods = readModNames(metadataHeader.getInt(), this::readMetadataBytes);
        velocityRecorded = hasVelocityMarker(mods);
        return withoutVelocityMarker(mods);
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
        return withoutVelocityMarker(readModNames(metadataHeader.getInt(), size -> {
            ByteBuffer data = ByteBuffer.allocate(size);
            readChannelFully(file, data);
            return data.array();
        }));
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
        return Float.compare(version, ReplayMod.replayVersion()) == 0;
    }

    private static void readChannelFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer);
            if (count < 0) throw new IOException("Replay file is truncated");
            if (count == 0) throw new IOException("Could not make progress reading replay data");
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
        singleByte.clear();
        if (!readFully(singleByte, true)) return null;
        byte type = singleByte.get(0);
        try {
            return switch (type) {
                case RepWriter.TICK_RECORD -> readTick();
                case RepWriter.ACTION_RECORD -> readActions(false);
                case RepWriter.SINGLE_ACTION_RECORD -> readActions(true);
                case RepWriter.RANDOM_RECORD -> readRandomRecord();
                case RepWriter.END_RECORD -> new EndRecord(advanceTick(readVarLong()));
                default -> throw new IOException("Unknown replay record type: " + type);
            };
        } catch (RuntimeException e) {
            throw new IOException("Malformed replay record", e);
        }
    }

    private TickRecord readTick() throws IOException {
        long tick = advanceTick(readVarLong());
        TickControls controls = TickControls.decode(readFixed(Integer.BYTES).getInt());
        long aimDeltaX = readSignedVarLong();
        long aimDeltaY = readSignedVarLong();
        long decodedAimX = (long)lastAimX + aimDeltaX;
        long decodedAimY = (long)lastAimY + aimDeltaY;
        if (decodedAimX < Integer.MIN_VALUE || decodedAimX > Integer.MAX_VALUE ||
            decodedAimY < Integer.MIN_VALUE || decodedAimY > Integer.MAX_VALUE) {
            throw new IOException("Replay aim coordinate is outside the supported range");
        }
        lastAimX = (int)decodedAimX;
        lastAimY = (int)decodedAimY;

        ByteBuffer mining = readFixed(Short.BYTES * 2);
        short mineX = mining.getShort();
        short mineY = mining.getShort();
        if ((mineX == -1) != (mineY == -1) || mineX < -1 || mineY < -1) {
            throw new IOException("Invalid replay mining tile");
        }
        return new TickRecord(tick,
            controls.movementX(),
            controls.movementY(),
            lastAimX / 8f,
            lastAimY / 8f,
            controls.shooting(), controls.boosting(), mineX, mineY);
    }

    private ActionRecord readActions(boolean single) throws IOException {
        int payloadSize = readVarInt();
        if (payloadSize < 2 || payloadSize > MAX_RECORD_SIZE) {
            throw new IOException("Invalid action record size: " + payloadSize);
        }
        ByteBuffer payload = readFixed(payloadSize);
        long tick = advanceTick(VarLong.read(payload));
        int count = single ? 1 : VarInt.read(payload);
        if ((single && count != 1) ||
            (!single && (count < 2 || count > Short.MAX_VALUE))) {
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

    private RandomRecord readRandomRecord() throws IOException {
        long tick = advanceTick(readVarLong());
        return decodeRandomRecord(tick, readFixed(Long.BYTES * 2));
    }

    static RandomRecord decodeRandomRecord(long tick, ByteBuffer payload) throws IOException {
        if (payload.remaining() != Long.BYTES * 2) {
            throw new IOException("Invalid replay random state record");
        }
        long seed0 = payload.getLong();
        long seed1 = payload.getLong();
        if ((seed0 | seed1) == 0L) {
            throw new IOException("Invalid replay random state");
        }
        return new RandomRecord(tick, seed0, seed1);
    }

    private ByteBuffer readFixed(int size) throws IOException {
        ByteBuffer result = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        readFully(result, false);
        result.flip();
        return result;
    }

    private long readVarLong() throws IOException {
        long result = 0L;
        for (int index = 0; index < 10; index++) {
            int next = readUnsignedByte();
            if (index == 9 && (next & 0xFE) != 0) {
                throw new IOException("VarLong overflow");
            }
            result |= (long)(next & 0x7F) << (index * 7);
            if ((next & 0x80) == 0) {
                if (index > 0 && (next & 0x7F) == 0) {
                    throw new IOException("Non-canonical VarLong");
                }
                return result;
            }
        }
        throw new IOException("VarLong too long");
    }

    private long readSignedVarLong() throws IOException {
        long value = readVarLong();
        return (value >>> 1) ^ -(value & 1L);
    }

    private int readVarInt() throws IOException {
        int result = 0;
        for (int index = 0; index < 5; index++) {
            int next = readUnsignedByte();
            if (index == 4 && (next & 0xF0) != 0) {
                throw new IOException("VarInt overflow");
            }
            result |= (next & 0x7F) << (index * 7);
            if ((next & 0x80) == 0) {
                if (index > 0 && (next & 0x7F) == 0) {
                    throw new IOException("Non-canonical VarInt");
                }
                return result;
            }
        }
        throw new IOException("VarInt too long");
    }

    private int readUnsignedByte() throws IOException {
        singleByte.clear();
        readFully(singleByte, false);
        return singleByte.get(0) & 0xFF;
    }

    private long advanceTick(long delta) throws IOException {
        if (delta < 0L || lastRecordTick > Long.MAX_VALUE - delta) {
            throw new IOException("Invalid replay tick delta");
        }
        lastRecordTick += delta;
        return lastRecordTick;
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
                if (count == 0) throw new IOException("Could not make progress reading replay data");
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
                             boolean shooting, boolean boosting, short mineX, short mineY)
        implements ReplayRecord {}

    record RandomState(long seed0, long seed1) {}

    public record ActionRecord(long tick, List<Action> actions) implements ReplayRecord {}

    public record RandomRecord(long tick, long seed0, long seed1) implements ReplayRecord {}

    public record EndRecord(long tick) implements ReplayRecord {}
}


