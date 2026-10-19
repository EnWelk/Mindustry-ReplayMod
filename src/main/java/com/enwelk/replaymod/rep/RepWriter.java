package com.enwelk.replaymod.rep;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import com.enwelk.replaymod.Action;
import com.enwelk.replaymod.ReplayMod;
import com.enwelk.replaymod.util.VarInt;
import com.enwelk.replaymod.util.VarLong;

import arc.util.io.FastDeflaterOutputStream;
import mindustry.Vars;
import mindustry.io.SaveIO;
import mindustry.io.SaveOptions;
import mindustry.world.Tile;

public class RepWriter implements Closeable {
    public static final String VELOCITY_RECORDING_MARKER = "replaymod-velocity@1";

    public static final byte TICK_RECORD = 1;
    public static final byte ACTION_RECORD = 2;
    public static final byte END_RECORD = 3;
    public static final byte RANDOM_RECORD = 4;
    public static final byte SINGLE_ACTION_RECORD = 5;

    private final FileChannel channel;
    private final long randomSeed0, randomSeed1;
    private final ByteBuffer outputBuffer = ByteBuffer.allocate(64 * 1024);
    private boolean finished;
    private long lastRecordTick;
    private int lastAimX;
    private int lastAimY;

    /** Creates a replay file and writes its save and metadata header. */
    public RepWriter(Path filePath) throws IOException {
        randomSeed0 = arc.math.Mathf.rand.seed0;
        randomSeed1 = arc.math.Mathf.rand.seed1;
        channel = FileChannel.open(filePath,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );

        try {
            writeHeader();
        } catch (IOException | RuntimeException e) {
            try {
                channel.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    /** Captures and writes the embedded save and replay format header. */
    private void writeHeader() throws IOException {
        ByteArrayOutputStream saveOutput = new ByteArrayOutputStream();
        SaveOptions options = new SaveOptions();
        options.extraTags = Vars.state.map.tags;
        options.embedAssets = true;
        SaveIO.write(new FastDeflaterOutputStream(saveOutput), options);
        byte[] saveData = saveOutput.toByteArray();

        ByteBuffer header = ByteBuffer.allocate(3 + Float.BYTES + Integer.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
        header.put((byte)'R').put((byte)'E').put((byte)'P');
        header.putFloat(ReplayMod.replayVersion());
        header.putInt(saveData.length);
        header.flip();

        writeFully(header);
        writeFully(ByteBuffer.wrap(saveData));
        writeModMetadata();
        writeRandomState();
    }

    /** Writes the enabled mod names and versions into the replay header. */
    private void writeModMetadata() throws IOException {
        List<String> mods = new ArrayList<>();
        Vars.mods.list().each(mod -> {
            if (mod.enabled()) {
                String version = mod.meta.version == null ? "" : mod.meta.version;
                mods.add(mod.name + "@" + version);
            }
        });
        mods.add(VELOCITY_RECORDING_MARKER);
        mods.sort(String::compareTo);

        int size = 8;
        List<byte[]> encodedMods = new ArrayList<>(mods.size());
        for (String mod : mods) {
            byte[] encoded = mod.getBytes(StandardCharsets.UTF_8);
            if (encoded.length == 0 || encoded.length > 512) {
                throw new IOException("Invalid mod identifier in active mod list");
            }
            size = Math.addExact(size, Integer.BYTES + encoded.length);
            encodedMods.add(encoded);
        }
        if (size > 1024 * 1024) {
            throw new IOException("Active mod metadata is too large");
        }

        ByteBuffer metadata = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        metadata.put((byte)'M').put((byte)'O').put((byte)'D').put((byte)'S');
        metadata.putInt(encodedMods.size());
        for (byte[] encoded : encodedMods) {
            metadata.putInt(encoded.length).put(encoded);
        }
        metadata.flip();
        writeFully(metadata);
    }

    /** Writes one recorded player-state packet. */
    public void writeTickPacket(long tick, float movementX, float movementY,
                                float aimX, float aimY, boolean shooting,
                                boolean boosting, Tile mineTile) throws IOException {
        if (!Float.isFinite(movementX) || !Float.isFinite(movementY) ||
            !Float.isFinite(aimX) || !Float.isFinite(aimY)) {
            throw new IOException("Replay controls must be finite");
        }
        float length = (float)Math.sqrt(movementX * movementX + movementY * movementY);
        if (length > 1f) {
            movementX /= length;
            movementY /= length;
        }
        movementX = arc.math.Mathf.clamp(movementX, -1f, 1f);
        movementY = arc.math.Mathf.clamp(movementY, -1f, 1f);
        long deltaTick = tickDelta(tick);
        int nextAimX = quantizeAim(aimX);
        int nextAimY = quantizeAim(aimY);
        long aimDeltaX = (long)nextAimX - lastAimX;
        long aimDeltaY = (long)nextAimY - lastAimY;
        ByteBuffer payload = ByteBuffer.allocate(
            VarLong.size(deltaTick) + 4 + VarLong.sizeSigned(aimDeltaX) +
                VarLong.sizeSigned(aimDeltaY) + 4
        ).order(ByteOrder.LITTLE_ENDIAN);
        VarLong.write(payload, deltaTick);
        payload.putInt(TickControls.encode(movementX, movementY, shooting, boosting));
        VarLong.writeSigned(payload, aimDeltaX);
        VarLong.writeSigned(payload, aimDeltaY);
        if (mineTile == null) {
            payload.putShort((short)-1).putShort((short)-1);
        } else {
            payload.putShort((short)mineTile.x).putShort((short)mineTile.y);
        }
        payload.flip();
        writeRecord(TICK_RECORD, payload, false);
        lastRecordTick = tick;
        lastAimX = nextAimX;
        lastAimY = nextAimY;
    }

    /** Writes one packet containing one or more recorded actions for a tick. */
    public void writeActions(long tick, List<Action> actions) throws IOException {
        if (actions.isEmpty()) return;
        if (actions.size() > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Too many actions in one replay record: " + actions.size());
        }

        long deltaTick = tickDelta(tick);
        int size = VarLong.size(deltaTick);
        if (actions.size() > 1) {
            size = Math.addExact(size, VarInt.size(actions.size()));
        }
        for (Action action : actions) {
            size = Math.addExact(size, action.getBufferSize());
        }

        ByteBuffer payload = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        VarLong.write(payload, deltaTick);
        if (actions.size() > 1) VarInt.write(payload, actions.size());
        for (Action action : actions) {
            action.write(payload);
        }
        payload.flip();
        writeRecord(actions.size() == 1 ? SINGLE_ACTION_RECORD : ACTION_RECORD, payload, true);
        lastRecordTick = tick;
    }

    /** Writes the global random generator state before a game update. */
    public void writeRandomState(long tick) throws IOException {
        long deltaTick = tickDelta(tick);
        ByteBuffer payload = ByteBuffer.allocate(VarLong.size(deltaTick) + Long.BYTES * 2)
            .order(ByteOrder.LITTLE_ENDIAN);
        VarLong.write(payload, deltaTick);
        payload.putLong(arc.math.Mathf.rand.seed0);
        payload.putLong(arc.math.Mathf.rand.seed1);
        payload.flip();
        writeRecord(RANDOM_RECORD, payload, false);
        lastRecordTick = tick;
    }

    /** Stores the current global random generator state after replay metadata. */
    private void writeRandomState() throws IOException {
        ByteBuffer state = ByteBuffer.allocate(Long.BYTES * 2).order(ByteOrder.LITTLE_ENDIAN);
        state.putLong(randomSeed0).putLong(randomSeed1).flip();
        writeFully(state);
    }

    /** Writes the terminal record once to mark the replay's last tick. */
    public void finish(long tick) throws IOException {
        if (finished) return;
        long deltaTick = tickDelta(tick);
        ByteBuffer payload = ByteBuffer.allocate(VarLong.size(deltaTick)).order(ByteOrder.LITTLE_ENDIAN);
        VarLong.write(payload, deltaTick);
        payload.flip();
        writeRecord(END_RECORD, payload, false);
        lastRecordTick = tick;
        finished = true;
    }

    /** Writes a typed record and optional variable-length payload size. */
    private void writeRecord(byte type, ByteBuffer payload, boolean includeSize) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(
            1 + (includeSize ? VarInt.size(payload.remaining()) : 0)
        ).order(ByteOrder.LITTLE_ENDIAN);
        header.put(type);
        if (includeSize) VarInt.write(header, payload.remaining());
        header.flip();
        writeFully(header);
        writeFully(payload);
    }

    private long tickDelta(long tick) throws IOException {
        if (tick < 0L || tick < lastRecordTick) {
            throw new IOException("Replay records must have nondecreasing non-negative ticks");
        }
        return tick - lastRecordTick;
    }

    private static int quantizeAim(float value) throws IOException {
        double scaled = (double)value * 8d;
        if (scaled < Integer.MIN_VALUE || scaled > Integer.MAX_VALUE) {
            throw new IOException("Replay aim coordinate is outside the supported range");
        }
        return (int)Math.round(scaled);
    }

    /** Copies a buffer into the writer's output buffer. */
    private void writeFully(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (!outputBuffer.hasRemaining()) flushBuffer();
            int count = Math.min(buffer.remaining(), outputBuffer.remaining());
            ByteBuffer slice = buffer.slice();
            slice.limit(count);
            outputBuffer.put(slice);
            buffer.position(buffer.position() + count);
        }
    }

    /** Flushes buffered replay bytes to disk. */
    private void flushBuffer() throws IOException {
        outputBuffer.flip();
        while (outputBuffer.hasRemaining()) {
            if (channel.write(outputBuffer) <= 0) {
                throw new IOException("Could not make progress writing replay data");
            }
        }
        outputBuffer.clear();
    }

    @Override
    /** Flushes pending bytes and closes the replay file. */
    public void close() throws IOException {
        try {
            flushBuffer();
        } finally {
            channel.close();
        }
    }
}


