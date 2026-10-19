package com.enwelk.replaymod.rep;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.enwelk.replaymod.ReplayMod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepReaderMetadataTests {
    private static final float CURRENT_VERSION = 5f;

    @TempDir
    Path tempDir;

    private float previousVersion;

    /** Uses the current replay format version for validation tests. */
    @BeforeEach
    void setCurrentReplayVersion() {
        Field version = replayVersionField();
        previousVersion = getReplayVersion(version);
        setReplayVersion(version, CURRENT_VERSION);
    }

    /** Restores the configured replay version after each test. */
    @AfterEach
    void restoreReplayVersion() {
        setReplayVersion(replayVersionField(), previousVersion);
    }

    /** Checks metadata decoding for the current replay format. */
    @Test
    void readsCurrentFormatModMetadata() throws IOException {
        Path current = Files.write(tempDir.resolve("mods.rep"),
            replay(CURRENT_VERSION, List.of("alpha@1.0", "beta@2")));

        assertEquals(List.of("alpha@1.0", "beta@2"), RepReader.readUsedMods(current));
    }

    @Test
    void velocityMarkerIsStrippedFromUsedMods() throws IOException {
        Path file = Files.write(tempDir.resolve("velocity.rep"),
            replay(CURRENT_VERSION, List.of(RepWriter.VELOCITY_RECORDING_MARKER, "alpha@1.0")));

        assertEquals(List.of("alpha@1.0"), RepReader.readUsedMods(file));
    }

    @Test
    void velocityMarkerOnlyFileReportsNoMods() throws IOException {
        Path file = Files.write(tempDir.resolve("velocity-only.rep"),
            replay(CURRENT_VERSION, List.of(RepWriter.VELOCITY_RECORDING_MARKER)));

        assertEquals(List.of(), RepReader.readUsedMods(file));
    }

    @Test
    void velocityMarkerHelpersDetectAndStrip() {
        assertTrue(RepReader.hasVelocityMarker(List.of("a@1", RepWriter.VELOCITY_RECORDING_MARKER)));
        assertFalse(RepReader.hasVelocityMarker(List.of("a@1")));
        assertEquals(List.of("a@1"),
            RepReader.withoutVelocityMarker(List.of("a@1", RepWriter.VELOCITY_RECORDING_MARKER)));
    }

    /** Accepts only the configured replay format and rejects legacy formats. */
    @Test
    void validatesOnlyCurrentReplayVersion() throws IOException {
        Path current = Files.write(tempDir.resolve("current.rep"), replay(CURRENT_VERSION, List.of()));
        Path legacy = Files.write(tempDir.resolve("legacy.rep"), replay(4f, List.of()));

        RepReader.validateForImport(current);
        assertThrows(IOException.class, () -> RepReader.readUsedMods(legacy));
        assertThrows(IOException.class, () -> RepReader.validateForImport(legacy));
    }

    /** Checks malformed headers, unsupported versions, and metadata markers. */
    @Test
    void rejectsMalformedHeadersVersionsAndMetadata() throws IOException {
        Path badHeader = Files.write(tempDir.resolve("bad-header.rep"), new byte[]{1, 2, 3});
        Path unsupported = Files.write(tempDir.resolve("future.rep"), replay(99f, List.of()));
        Path badMetadata = Files.write(tempDir.resolve("bad-metadata.rep"),
            replay(CURRENT_VERSION, List.of()));
        byte[] bytes = Files.readAllBytes(badMetadata);
        int metadataOffset = 3 + Float.BYTES + Integer.BYTES + 1;
        bytes[metadataOffset] = 'X';
        Files.write(badMetadata, bytes);

        assertThrows(IOException.class, () -> RepReader.readUsedMods(badHeader));
        assertThrows(IOException.class, () -> RepReader.validateForImport(unsupported));
        assertThrows(IOException.class, () -> RepReader.readUsedMods(badMetadata));
    }

    /** Checks truncated replay framing and invalid mod counts. */
    @Test
    void rejectsTruncatedReplayDataAndMalformedModNames() throws IOException {
        Path truncated = Files.write(tempDir.resolve("truncated.rep"),
            new byte[]{'R', 'E', 'P', 0, 0, 64, 64, 1, 0, 0, 0});
        Path invalidMod = Files.write(tempDir.resolve("invalid-mod.rep"),
            replay(CURRENT_VERSION, List.of()));
        byte[] bytes = Files.readAllBytes(invalidMod);
        int metadataOffset = 3 + Float.BYTES + Integer.BYTES + 1;
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(metadataOffset + 4, 4097);
        Files.write(invalidMod, bytes);

        assertThrows(IOException.class, () -> RepReader.readUsedMods(truncated));
        assertThrows(IOException.class, () -> RepReader.validateForImport(truncated));
        assertThrows(IOException.class, () -> RepReader.readUsedMods(invalidMod));
    }

    /** Builds a minimal replay header using the current format layout. */
    private static byte[] replay(float version, List<String> mods) {
        int metadataSize = 8;
        for (String mod : mods) {
            metadataSize += Integer.BYTES + mod.getBytes(StandardCharsets.UTF_8).length;
        }
        int metadataSizeTotal = metadataSize + Long.BYTES * 2;
        ByteBuffer buffer = ByteBuffer.allocate(
            3 + Float.BYTES + Integer.BYTES + 1 + metadataSizeTotal + 13
        ).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte)'R').put((byte)'E').put((byte)'P');
        buffer.putFloat(version);
        buffer.putInt(1);
        buffer.put((byte)42);
        buffer.put((byte)'M').put((byte)'O').put((byte)'D').put((byte)'S');
        buffer.putInt(mods.size());
        for (String mod : mods) {
            byte[] name = mod.getBytes(StandardCharsets.UTF_8);
            buffer.putInt(name.length).put(name);
        }
        buffer.putLong(1L).putLong(2L);
        return buffer.array();
    }

    /** Locates the replay version field for isolated validation setup. */
    private static Field replayVersionField() {
        try {
            Field field = ReplayMod.class.getDeclaredField("replayVersion");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Reads the replay version field while preserving test isolation. */
    private static float getReplayVersion(Field field) {
        try {
            return field.getFloat(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    /** Sets the replay version field while preserving test isolation. */
    private static void setReplayVersion(Field field, float version) {
        try {
            field.setFloat(null, version);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }
}


