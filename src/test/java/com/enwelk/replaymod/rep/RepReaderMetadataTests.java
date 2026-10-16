package com.enwelk.replaymod.rep;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.enwelk.replaymod.ReplayMod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RepReaderMetadataTests {
    @TempDir
    Path tempDir;

    /** Checks metadata decoding and legacy replays without mod lists. */
    @Test
    void readsModMetadataAndAllowsLegacyReplaysWithoutMetadata() throws IOException {
        Path version3 = Files.write(tempDir.resolve("mods.rep"), replay(3f, List.of("alpha@1.0", "beta@2")));
        Path version2 = Files.write(tempDir.resolve("old.rep"), replay(2f, List.of()));

        assertEquals(List.of("alpha@1.0", "beta@2"), RepReader.readUsedMods(version3));
        assertEquals(List.of(), RepReader.readUsedMods(version2));
    }

    /** Checks import validation for current and legacy file versions. */
    @Test
    void validatesImportForSupportedReplayVersions() throws IOException {
        Path version3 = Files.write(tempDir.resolve("valid.rep"), replay(3f, List.of("alpha@1")));
        Path version1 = Files.write(tempDir.resolve("legacy.rep"), replay(1f, List.of()));

        Field version = replayVersionField();
        float previous = getReplayVersion(version);
        try {
            version.setFloat(null, 3f);
            RepReader.validateForImport(version3);
            RepReader.validateForImport(version1);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        } finally {
            try {
                version.setFloat(null, previous);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** Checks malformed headers, unsupported versions, and metadata markers. */
    @Test
    void rejectsMalformedHeadersVersionsAndMetadata() throws IOException {
        Path badHeader = Files.write(tempDir.resolve("bad-header.rep"), new byte[]{1, 2, 3});
        Path unsupported = Files.write(tempDir.resolve("future.rep"), replay(99f, List.of()));
        Path badMetadata = Files.write(tempDir.resolve("bad-metadata.rep"), replay(3f, List.of()));
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
        Path invalidMod = Files.write(tempDir.resolve("invalid-mod.rep"), replay(3f, List.of()));
        byte[] bytes = Files.readAllBytes(invalidMod);
        int metadataOffset = 3 + Float.BYTES + Integer.BYTES + 1;
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(metadataOffset + 4, 4097);
        Files.write(invalidMod, bytes);

        assertThrows(IOException.class, () -> RepReader.readUsedMods(truncated));
        assertThrows(IOException.class, () -> RepReader.validateForImport(truncated));
        assertThrows(IOException.class, () -> RepReader.readUsedMods(invalidMod));
    }

    /** Builds a minimal replay header with optional mod metadata. */
    private static byte[] replay(float version, List<String> mods) {
        int metadataSize = 8;
        for (String mod : mods) {
            metadataSize += Integer.BYTES + mod.getBytes(StandardCharsets.UTF_8).length;
        }
        int metadataSizeTotal = Float.compare(version, 3f) >= 0 ? metadataSize : 0;
        ByteBuffer buffer = ByteBuffer.allocate(
            3 + Float.BYTES + Integer.BYTES + 1 + metadataSizeTotal + 13
        ).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte)'R').put((byte)'E').put((byte)'P');
        buffer.putFloat(version);
        buffer.putInt(1);
        buffer.put((byte)42);
        if (Float.compare(version, 3f) >= 0) {
            buffer.put((byte)'M').put((byte)'O').put((byte)'D').put((byte)'S');
            buffer.putInt(mods.size());
            for (String mod : mods) {
                byte[] name = mod.getBytes(StandardCharsets.UTF_8);
                buffer.putInt(name.length).put(name);
            }
        }
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
}
