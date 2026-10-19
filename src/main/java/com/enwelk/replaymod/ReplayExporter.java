package com.enwelk.replaymod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import arc.files.Fi;

/** Writes replay files as ordinary ZIP archives. */
public final class ReplayExporter {
    private static final long MAX_VOLUME_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final long VOLUME_PAYLOAD_LIMIT = MAX_VOLUME_BYTES - 64L * 1024L * 1024L;
    private static final int ENTRY_CHUNK_BYTES = 512 * 1024 * 1024;
    private static final int BUFFER_SIZE = 64 * 1024;

    /** Prevents creating exporter instances. */
    private ReplayExporter() {}

    /** Copies one replay, optionally wrapping it in a ZIP archive. */
    public static void exportOne(Fi replay, Fi destination, boolean compressed) throws IOException {
        if (!replay.exists() || replay.isDirectory()) {
            throw new IOException("Replay file does not exist: " + replay.name());
        }
        if (!compressed) {
            replay.copyTo(destination);
            return;
        }

        try (ZipOutputStream zip = new ZipOutputStream(destination.write(false));
             InputStream input = replay.read()) {
            zip.putNextEntry(new ZipEntry(replay.name()));
            copy(input, zip, replay.length());
            zip.closeEntry();
        }
    }

    /** Creates sequential ZIP volumes whose files stay below 2 GiB. */
    public static void exportAll(Fi[] replayFiles, Fi destinationDirectory) throws IOException {
        if (replayFiles.length == 0) throw new IOException("No replay files to export");
        Arrays.sort(replayFiles,
            (first, second) -> first.name().compareToIgnoreCase(second.name()));

        Path destination = destinationDirectory.file().toPath();
        Files.createDirectories(destination);
        Path staging = Files.createTempDirectory(destination, "replay-export-");
        try {
            try (VolumeWriter writer = new VolumeWriter(staging)) {
                for (Fi replay : replayFiles) {
                    addReplay(writer, replay);
                }
            }

            int volumeCount = countVolumes(staging);
            for (int index = 1; index <= volumeCount; index++) {
                Path staged = staging.resolve(volumeName(index));
                Path output = destination.resolve(volumeName(index));
                Files.move(staged, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            clearStaging(staging);
        }
    }

    /** Adds one replay as a single entry or numbered chunks. */
    private static void addReplay(VolumeWriter writer, Fi replay) throws IOException {
        if (!replay.exists() || replay.isDirectory()) {
            throw new IOException("Replay file does not exist: " + replay.name());
        }

        long size = replay.length();
        long partCount = Math.max(1, (size + ENTRY_CHUNK_BYTES - 1) / ENTRY_CHUNK_BYTES);
        try (InputStream input = replay.read()) {
            for (long part = 0; part < partCount; part++) {
                long partSize = Math.min(ENTRY_CHUNK_BYTES, size - part * ENTRY_CHUNK_BYTES);
                String entryName = partCount == 1
                    ? replay.name()
                    : replay.name() + ".part" + String.format(Locale.ROOT, "%03d", part + 1);
                writer.addEntry(entryName, input, partSize);
            }
        }
    }

    /** Copies exactly the requested number of bytes. */
    private static void copy(InputStream input, OutputStream output, long length) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
            if (count < 0) throw new IOException("Replay file ended while exporting");
            if (count == 0) continue;
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }

    /** Counts staged volume files. */
    private static int countVolumes(Path staging) throws IOException {
        try (var files = Files.list(staging)) {
            return Math.toIntExact(files.count());
        }
    }

    /** Returns a three-digit ZIP volume name. */
    private static String volumeName(int index) {
        return String.format(Locale.ROOT, "%03d.zip", index);
    }

    /** Removes only the temporary files created for this export. */
    private static void clearStaging(Path staging) throws IOException {
        if (!Files.exists(staging)) return;
        try (var files = Files.list(staging)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
        Files.deleteIfExists(staging);
    }

    private static final class VolumeWriter implements AutoCloseable {
        private final Path staging;
        private ZipOutputStream zip;
        private long reservedBytes;
        private int index;

        /** Creates a writer that stages ZIP volumes in the given directory. */
        private VolumeWriter(Path staging) {
            this.staging = staging;
        }

        /** Adds an entry, opening another volume when this one is full. */
        private void addEntry(String name, InputStream input, long length) throws IOException {
            long entryReserve = length + name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length * 2L + 256L;
            if (entryReserve > VOLUME_PAYLOAD_LIMIT) {
                throw new IOException("Replay chunk exceeds the ZIP volume limit: " + name);
            }
            if (zip == null || reservedBytes + entryReserve > VOLUME_PAYLOAD_LIMIT) {
                closeVolume();
                openVolume();
            }

            zip.putNextEntry(new ZipEntry(name));
            copy(input, zip, length);
            zip.closeEntry();
            reservedBytes += entryReserve;
        }

        /** Opens the next temporary ZIP volume. */
        private void openVolume() throws IOException {
            Path file = staging.resolve(volumeName(++index));
            zip = new ZipOutputStream(Files.newOutputStream(file));
            reservedBytes = 0;
        }

        /** Closes the current volume. */
        private void closeVolume() throws IOException {
            if (zip == null) return;
            ZipOutputStream current = zip;
            zip = null;
            current.close();
        }

        @Override
        /** Closes the currently open ZIP volume. */
        public void close() throws IOException {
            closeVolume();
        }
    }
}


