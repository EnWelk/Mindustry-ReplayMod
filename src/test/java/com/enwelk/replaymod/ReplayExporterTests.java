package com.enwelk.replaymod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.ZipFile;

import arc.files.Fi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReplayExporterTests {
    @TempDir
    Path tempDir;

    /** Checks that uncompressed export copies replay bytes exactly. */
    @Test
    void exportsSingleReplayUncompressed() throws IOException {
        byte[] replay = {1, 2, 3, 4};
        Path source = Files.write(tempDir.resolve("game.rep"), replay);
        Path destination = tempDir.resolve("plain.rep");

        ReplayExporter.exportOne(new Fi(source.toFile()), new Fi(destination.toFile()), false);

        assertArrayEquals(replay, Files.readAllBytes(destination));
    }

    /** Checks that compressed export creates a ZIP with the replay filename. */
    @Test
    void exportsSingleReplayAsZipWithOriginalEntryName() throws IOException {
        byte[] replay = {4, 3, 2, 1};
        Path source = Files.write(tempDir.resolve("match.rep"), replay);
        Path destination = tempDir.resolve("match.zip");

        ReplayExporter.exportOne(new Fi(source.toFile()), new Fi(destination.toFile()), true);

        try (ZipFile zip = new ZipFile(destination.toFile())) {
            assertArrayEquals(replay, zip.getInputStream(zip.getEntry("match.rep")).readAllBytes());
        }
    }

    /** Checks that all replay files are exported into a named ZIP volume. */
    @Test
    void exportsAllReplaysInDeterministicOrderAndCreatesNumberedVolume() throws IOException {
        Path first = Files.write(tempDir.resolve("b.rep"), new byte[]{2});
        Path second = Files.write(tempDir.resolve("A.rep"), new byte[]{1});
        Path output = Files.createDirectory(tempDir.resolve("exports"));

        ReplayExporter.exportAll(
            new Fi[]{new Fi(first.toFile()), new Fi(second.toFile())},
            new Fi(output.toFile())
        );

        Path volume = output.resolve("001.zip");
        try (ZipFile zip = new ZipFile(volume.toFile())) {
            assertEquals(Arrays.asList("A.rep", "b.rep"),
                zip.stream().map(entry -> entry.getName()).toList());
            assertArrayEquals(new byte[]{1}, zip.getInputStream(zip.getEntry("A.rep")).readAllBytes());
            assertArrayEquals(new byte[]{2}, zip.getInputStream(zip.getEntry("b.rep")).readAllBytes());
        }
    }

    /** Checks empty exports and missing source files fail explicitly. */
    @Test
    void rejectsEmptyExportsAndMissingReplayFiles() throws IOException {
        Fi exportDir = new Fi(Files.createDirectory(tempDir.resolve("empty-export")).toFile());
        assertThrows(IOException.class, () -> ReplayExporter.exportAll(new Fi[0], exportDir));

        Fi missing = new Fi(tempDir.resolve("missing.rep").toFile());
        Fi destination = new Fi(tempDir.resolve("missing-copy.rep").toFile());
        assertThrows(IOException.class, () -> ReplayExporter.exportOne(missing, destination, false));
        assertThrows(IOException.class, () -> ReplayExporter.exportAll(new Fi[]{missing}, exportDir));
    }
}


