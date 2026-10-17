package com.enwelk.replaymod.rep;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import arc.util.io.FastDeflaterOutputStream;
import com.enwelk.replaymod.Action;
import com.enwelk.replaymod.ReplayMod;

import mindustry.Vars;
import mindustry.io.SaveIO;
import mindustry.io.SaveOptions;
import mindustry.world.Tile;

public class RepWriter implements Closeable {
    public static final byte TICK_RECORD = 1;
    public static final byte ACTION_RECORD = 2;
    public static final byte END_RECORD = 3;
    public static final byte RANDOM_RECORD = 4;

    private final FileChannel channel;
    private final long randomSeed0, randomSeed1;
    private final ByteBuffer outputBuffer = ByteBuffer.allocate(64 * 1024);
    private final ByteBuffer tickPayload = ByteBuffer.allocate(Long.BYTES + 7 * Float.BYTES + 2 + 4)
        .order(ByteOrder.LITTLE_ENDIAN);
    private boolean finished;

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
                                boolean boosting, Tile mineTile,
                                float cameraX, float cameraY, float cameraScale) throws IOException {
        ByteBuffer payload = tickPayload;
        payload.clear();
        payload.putLong(tick);
        payload.putFloat(movementX).putFloat(movementY);
        payload.putFloat(aimX).putFloat(aimY);
        payload.putFloat(cameraX).putFloat(cameraY).putFloat(cameraScale);
        payload.put((byte)(shooting ? 1 : 0));
        payload.put((byte)(boosting ? 1 : 0));
        if (mineTile == null) {
            payload.putShort((short)-1).putShort((short)-1);
        } else {
            payload.putShort((short)mineTile.x).putShort((short)mineTile.y);
        }
        payload.flip();
        writeRecord(TICK_RECORD, payload);
    }

    /** Writes one packet containing the recorded actions for a tick. */
    public void writeActions(long tick, List<Action> actions) throws IOException {
        if (actions.size() > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Too many actions in one replay record: " + actions.size());
        }

        int size = Long.BYTES + Short.BYTES;
        for (Action action : actions) {
            size = Math.addExact(size, action.getBufferSize());
        }

        ByteBuffer payload = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        payload.putLong(tick);
        payload.putShort((short)actions.size());
        for (Action action : actions) {
            action.write(payload);
        }
        payload.flip();
        writeRecord(ACTION_RECORD, payload);
    }

    /** Writes the global random generator state before a game update. */
    public void writeRandomState(long tick) throws IOException {
        ByteBuffer payload = ByteBuffer.allocate(Long.BYTES * 3).order(ByteOrder.LITTLE_ENDIAN);
        payload.putLong(tick);
        payload.putLong(arc.math.Mathf.rand.seed0);
        payload.putLong(arc.math.Mathf.rand.seed1);
        payload.flip();
        writeRecord(RANDOM_RECORD, payload);
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
        ByteBuffer payload = ByteBuffer.allocate(Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        payload.putLong(tick).flip();
        writeRecord(END_RECORD, payload);
        finished = true;
    }

    /** Writes a typed record header and its payload. */
    private void writeRecord(byte type, ByteBuffer payload) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(1 + Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        header.put(type).putInt(payload.remaining()).flip();
        writeFully(header);
        writeFully(payload);
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
            channel.write(outputBuffer);
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