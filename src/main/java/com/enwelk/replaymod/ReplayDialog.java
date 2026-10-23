package com.enwelk.replaymod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.enwelk.replaymod.rep.RepReader;

import arc.Core;
import arc.files.Fi;
import arc.scene.ui.CheckBox;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.FileChooser;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;

public class ReplayDialog extends BaseDialog {
    private final ReplayMod mod;
    private final ReplayController controller;
    private final Table replayList = new Table();

    private final Label status;
    private final TextButton recordButton;
    private final TextButton playButton;
    private final TextButton stopRecordingButton;
    private final TextButton deleteButton;
    private final TextButton exportButton;
    private final TextButton exportAllButton;
    private final CheckBox compressExport;
    private final arc.scene.ui.TextField nameField;

    private final BaseDialog endDialog;
    private Label endMessage;

    private final BaseDialog playbackErrorDialog;
    private Label playbackErrorMessage;

    private Fi selectedReplay;
    private String statusText = "";
    private boolean hasReplays;

    /** Builds the replay manager with a mobile-friendly scrollable layout. */
    public ReplayDialog(ReplayMod mod, ReplayController controller) {
        super(Core.bundle.get("replaymod.title"));

        this.mod = mod;
        this.controller = controller;

        endDialog = createEndDialog();
        playbackErrorDialog = createPlaybackErrorDialog();

        controller.setPlaybackFinishedListener(this::showPlaybackEnded);
        controller.setPlaybackStoppedListener(this::showPlaybackStopped);
        controller.setPlaybackErrorListener(this::showPlaybackError);

        closeOnBack();

        Table root = new Table();
        root.margin(14f);
        root.defaults().growX().left().pad(4f);

        Label nameLabel = new Label(Core.bundle.get("replaymod.name"));
        nameLabel.setWrap(true);
        root.add(nameLabel).growX().left();
        root.row();

        status = new Label("");
        status.setWrap(true);
        root.add(status).growX().left().minHeight(34f);
        root.row();

        nameField = root.field("", text -> {})
            .maxTextLength(80)
            .growX()
            .minHeight(48f)
            .get();
        nameField.setMessageText(Core.bundle.get("replaymod.name.placeholder"));
        root.row();

        Table controls = new Table();
        controls.defaults().growX().height(50f).pad(3f);

        recordButton = controls.button(
            Core.bundle.get("replaymod.record"), Icon.save, this::record
        ).get();
        controls.row();

        playButton = controls.button(
            Core.bundle.get("replaymod.play"), Icon.play, this::play
        ).get();
        controls.row();

        stopRecordingButton = controls.button(
            Core.bundle.get("replaymod.stop"), Icon.cancel, this::stop
        ).visible(() -> controller.isRecording()).get();
        controls.row();

        deleteButton = controls.button(
            Core.bundle.get("replaymod.delete"), Icon.trash, this::delete
        ).get();
        controls.row();

        exportButton = controls.button(
            Core.bundle.get("replaymod.export"), Icon.export, this::export
        ).get();
        controls.row();

        controls.button(
            Core.bundle.get("replaymod.import"), Icon.download, this::chooseImport
        ).get();
        controls.row();

        exportAllButton = controls.button(
            Core.bundle.get("replaymod.exportAll"), Icon.export, this::exportAll
        ).get();
        controls.row();

        controls.button(
            Core.bundle.get("replaymod.refresh"), Icon.refresh, this::refresh
        ).get();
        controls.row();

        root.add(controls).growX().padTop(4f).padBottom(4f);
        root.row();

        compressExport = root.check(
            Core.bundle.get("replaymod.export.compress"),
            true,
            checked -> {}
        ).left().get();
        root.row();

        replayList.margin(8f);
        replayList.defaults().growX().pad(2f);
        root.add(replayList).growX().padTop(8f);
        root.row();

        ScrollPane scroll = new ScrollPane(root);
        scroll.setScrollingDisabled(true, false);
        scroll.setFadeScrollBars(false);
        scroll.setOverscroll(false, false);

        cont.margin(0f);
        cont.add(scroll)
            .grow()
            .maxWidth(560f)
            .maxHeight(Core.graphics.getHeight() * 0.85f)
            .center();

        buttons.defaults().growX().height(58f).pad(6f);
        buttons.button(Core.bundle.get("replaymod.close"), Icon.cancel, () -> hide()).get();

        cont.update(() -> {
            status.setText(statusText());

            recordButton.setDisabled(controller.isActive() || !canRecord());
            playButton.setDisabled(controller.isActive() || selectedReplay == null || Vars.net.active());
            stopRecordingButton.setDisabled(!controller.isRecording());
            deleteButton.setDisabled(selectedReplay == null);
            exportButton.setDisabled(selectedReplay == null);
            exportAllButton.setDisabled(!hasReplays);
        });

        shown(this::refresh);
        refresh();
    }

    /** Checks whether recording is available in the current game state. */
    private boolean canRecord() {
        return !Vars.net.active()
            && Vars.state != null
            && Vars.state.isGame()
            && Vars.control != null;
    }

    /** Returns the current operation status or the default state message. */
    private String statusText() {
        if (!statusText.isEmpty()) return statusText;

        if (Vars.net.active()) return Core.bundle.get("replaymod.status.multiplayerUnsupported");
        if (controller.isRecording()) return Core.bundle.get("replaymod.status.recording");
        if (controller.isPlaying()) return Core.bundle.get("replaymod.status.playing");

        return Core.bundle.get("replaymod.status.idle");
    }

    /** Starts recording to the name entered in the dialog. */
    private void record() {
        String name = !nameField.getText().trim().isEmpty()
            ? nameField.getText().trim()
            : String.valueOf(Time.globalTime);

        try {
            Path path = mod.replayPath(name);
            Fi destination = new Fi(path.toFile());

            if (destination.exists()) {
                Vars.ui.showConfirm(
                    "Confirm",
                    Core.bundle.format("replaymod.overwrite", destination.name()),
                    () -> startRecording(path)
                );
            } else {
                startRecording(path);
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            reportFailure("replaymod.error.record", e);
        }
    }

    /** Starts recording and reports any startup error. */
    private void startRecording(Path path) {
        try {
            controller.startRecording(path);
            statusText = "";
            refresh();
        } catch (IOException | RuntimeException | LinkageError e) {
            reportFailure("replaymod.error.record", e);
        }
    }

    /** Starts playback for the selected replay. */
    private void play() {
        if (selectedReplay == null) return;

        try {
            Path path = selectedReplay.file().toPath();
            List<String> usedMods = RepReader.readUsedMods(path);

            if (usedMods.isEmpty()) {
                startPlayback(path);
            } else {
                showModWarning(path, usedMods);
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            reportFailure("replaymod.error.play", e);
        }
    }

    /** Creates the end-of-playback screen with a mobile-friendly vertical button layout. */
    private BaseDialog createEndDialog() {
        BaseDialog dialog = new BaseDialog(Core.bundle.get("replaymod.finished.title"));

        dialog.cont.defaults().growX().left();

        endMessage = new Label(Core.bundle.get("replaymod.finished.message"));
        endMessage.setWrap(true);
        dialog.cont.add(endMessage).growX().maxWidth(480f).pad(12f);

        dialog.buttons.defaults().growX().height(54f).pad(4f);

        dialog.buttons.button(Core.bundle.get("replaymod.finished.replayAgain"), Icon.play, () -> {
            dialog.hide();
            play();
        });
        dialog.buttons.row();

        dialog.buttons.button(Core.bundle.get("replaymod.finished.manager"), Icon.menu, () -> {
            dialog.hide();
            show();
        });
        dialog.buttons.row();

        dialog.buttons.button(Core.bundle.get("replaymod.close"), Icon.cancel, () -> dialog.hide());
        dialog.buttons.row();

        dialog.closeOnBack();
        return dialog;
    }

    /** Creates the dialog shown when replay data cannot be read or applied. */
    private BaseDialog createPlaybackErrorDialog() {
        BaseDialog dialog = new BaseDialog(Core.bundle.get("replaymod.error.playbackTitle"));

        dialog.cont.defaults().growX().left();

        playbackErrorMessage = new Label("");
        playbackErrorMessage.setWrap(true);
        dialog.cont.add(playbackErrorMessage).growX().maxWidth(480f).pad(12f);

        dialog.buttons.defaults().growX().height(54f).pad(4f);

        dialog.buttons.button(Core.bundle.get("replaymod.finished.replayAgain"), Icon.play, () -> {
            dialog.hide();
            play();
        });
        dialog.buttons.row();

        dialog.buttons.button(Core.bundle.get("replaymod.finished.manager"), Icon.menu, () -> {
            dialog.hide();
            show();
        });
        dialog.buttons.row();

        dialog.buttons.button(Core.bundle.get("replaymod.close"), Icon.cancel, () -> dialog.hide());
        dialog.buttons.row();

        dialog.closeOnBack();
        return dialog;
    }

    /** Shows the completion screen and refreshes replay controls. */
    private void showPlaybackEnded() {
        statusText = Core.bundle.get("replaymod.status.finished");
        if (endMessage != null) endMessage.setText(Core.bundle.get("replaymod.finished.message"));
        refresh();
        endDialog.show();
    }

    /** Shows the replay-stopped dialog. */
    private void showPlaybackStopped() {
        statusText = Core.bundle.get("replaymod.status.stopped");
        if (endMessage != null) endMessage.setText(Core.bundle.get("replaymod.stopped.message"));
        refresh();
        endDialog.show();
    }

    /** Displays the failure reason after playback has been safely stopped. */
    private void showPlaybackError(String message) {
        statusText = Core.bundle.get("replaymod.status.playbackError");
        playbackErrorMessage.setText(Core.bundle.format("replaymod.error.playbackEnded", message));
        refresh();
        playbackErrorDialog.show();
    }

    /** Checks recorded mod metadata before starting playback. */
    private void startPlayback(Path path) {
        try {
            controller.startPlayback(path);
            statusText = "";
            hide();
        } catch (IOException | RuntimeException | LinkageError e) {
            reportFailure("replaymod.error.play", e);
        }
    }

    /** Displays a compatibility warning for missing or changed mods. */
    private void showModWarning(Path path, List<String> usedMods) {
        Set<String> installedMods = new HashSet<>();

        Vars.mods.list().each(loaded -> {
            if (loaded.enabled()) {
                String version = loaded.meta.version == null ? "" : loaded.meta.version;
                installedMods.add(loaded.name + "@" + version);
            }
        });

        List<String> missingMods = usedMods.stream()
            .filter(required -> !installedMods.contains(required))
            .toList();

        String message = Core.bundle.format(
            "replaymod.mods.warning.message",
            String.join("\n", usedMods),
            missingMods.isEmpty()
                ? Core.bundle.get("replaymod.mods.warning.noneMissing")
                : String.join("\n", missingMods)
        );

        Vars.ui.showConfirm(
            Core.bundle.get("replaymod.mods.warning.title"),
            message,
            () -> startPlayback(path)
        );
    }

    /** Stops recording or playback. */
    private void stop() {
        try {
            controller.stop();
            statusText = "";
            refresh();
        } catch (IOException | RuntimeException | LinkageError e) {
            reportFailure("replaymod.error.stop", e);
        }
    }

    /** Reloads the replay list from disk. */
    private void refresh() {
        Fi directory = mod.replayDirectory();
        directory.mkdirs();

        Fi[] files = directory.list(".rep");
        hasReplays = files.length > 0;

        Arrays.sort(files, (first, second) ->
            first.nameWithoutExtension().compareToIgnoreCase(second.nameWithoutExtension()));

        if (selectedReplay != null && Arrays.stream(files).noneMatch(selectedReplay::equals)) {
            selectedReplay = null;
        }

        replayList.clearChildren();
        replayList.defaults().growX().pad(2f);

        if (files.length == 0) {
            Label emptyLabel = new Label(Core.bundle.get("replaymod.empty"));
            emptyLabel.setWrap(true);
            replayList.add(emptyLabel).left().pad(8f).growX();
            replayList.row();
            replayList.marginBottom(12f);
            return;
        }

        for (Fi file : files) {
            String size = String.format(java.util.Locale.ROOT, "%.1f", file.length() / (1024d * 1024d));
            String displayName = shorten(file.nameWithoutExtension(), 26);

            TextButton button = replayList.button(
                Core.bundle.format("replaymod.list.entry", displayName, size),
                Styles.grayt,
                () -> selectedReplay = file
            ).height(46f).get();

            button.update(() -> button.setChecked(file.equals(selectedReplay)));
            replayList.row();
        }

        replayList.marginBottom(12f);
    }

    /** Deletes the selected replay file. */
    private void delete() {
        if (selectedReplay == null) return;

        try {
            Fi replay = selectedReplay;

            if (!replay.exists()) {
                throw new IOException("Replay file no longer exists: " + replay.name());
            }

            if (!replay.delete()) {
                throw new IOException("Could not delete replay file: " + replay.name());
            }

            selectedReplay = null;
            statusText = "";
            refresh();
        } catch (IOException | RuntimeException | LinkageError e) {
            reportFailure("replaymod.error.delete", e);
        }
    }

    /** Exports the selected replay as a file or ZIP. */
    private void export() {
        if (selectedReplay == null) return;

        Fi replay = selectedReplay;
        boolean compressed = compressExport.isChecked();
        String extension = compressed ? "zip" : "rep";

        FileChooser.export(replay.nameWithoutExtension(), extension, destination -> {
            try {
                ReplayExporter.exportOne(replay, destination, compressed);
            } catch (IOException | RuntimeException | LinkageError e) {
                reportFailure("replaymod.error.export", e);
            }
        });
    }

    /** Chooses a destination and exports all replays to bounded ZIP volumes. */
    private void exportAll() {
        FileChooser.save("zip").name("001.zip")
            .title(Core.bundle.get("replaymod.exportAll.title"))
            .submit(this::confirmExportAll);
    }

    /** Opens a file chooser and imports the selected replay into the replay library. */
    private void chooseImport() {
        FileChooser.open("rep").title(Core.bundle.get("replaymod.import")).submit(this::importReplay);
    }

    /** Validates and copies an imported replay without replacing a file silently. */
    private void importReplay(Fi source) {
        try {
            if (!source.exists() || source.isDirectory() || !source.extEquals("rep")) {
                throw new IOException("Select a .rep replay file");
            }

            RepReader.validateForImport(source.file().toPath());

            String name = source.nameWithoutExtension().replaceAll("[^A-Za-z0-9._-]", "_");
            if (name.isBlank() || name.equals(".") || name.equals("..")) {
                name = "imported-" + Long.toUnsignedString(System.nanoTime());
            }

            Fi destination = mod.replayDirectory().child(name + ".rep");
            destination.parent().mkdirs();

            if (destination.exists()) {
                Vars.ui.showConfirm(
                    Core.bundle.get("confirm"),
                    Core.bundle.format("replaymod.import.overwrite", destination.name()),
                    () -> copyImportedReplay(source, destination)
                );
            } else {
                copyImportedReplay(source, destination);
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            reportFailure("replaymod.error.import", e);
        }
    }

    /** Copies an import via a temporary file so failures leave no partial replay. */
    private void copyImportedReplay(Fi source, Fi destination) {
        Path temporary = null;

        try {
            temporary = Files.createTempFile(destination.parent().file().toPath(), "replay-import-", ".tmp");
            Files.copy(source.file().toPath(), temporary, StandardCopyOption.REPLACE_EXISTING);

            RepReader.validateForImport(temporary);
            Files.move(temporary, destination.file().toPath(), StandardCopyOption.REPLACE_EXISTING);

            statusText = Core.bundle.format("replaymod.status.imported", destination.name());
            refresh();
        } catch (IOException | RuntimeException | LinkageError e) {
            reportFailure("replaymod.error.import", e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    Log.err("Could not clean up temporary replay import", e);
                }
            }
        }
    }

    /** Confirms replacement before exporting numbered ZIP volumes. */
    private void confirmExportAll(Fi selectedFile) {
        Fi directory = selectedFile.parent();
        Fi[] existingVolumes = directory.list(file -> file.getName().matches("\\d{3,}\\.zip"));

        if (existingVolumes.length > 0) {
            Vars.ui.showConfirm(
                Core.bundle.get("confirm"),
                Core.bundle.get("replaymod.exportAll.replace"),
                () -> writeAllVolumes(directory)
            );
        } else {
            writeAllVolumes(directory);
        }
    }

    /** Writes the current replay collection as numbered ZIP volumes. */
    private void writeAllVolumes(Fi directory) {
        try {
            ReplayExporter.exportAll(mod.replayDirectory().list(".rep"), directory);
            statusText = Core.bundle.get("replaymod.status.exportedAll");
        } catch (IOException | RuntimeException | LinkageError e) {
            reportFailure("replaymod.error.export", e);
        }
    }

    /** Displays an export or file-operation failure. */
    private void reportFailure(String key, Throwable error) {
        String detail = error.getMessage() == null
            ? error.getClass().getSimpleName()
            : error.getClass().getSimpleName() + ": " + error.getMessage();
        if (error instanceof LinkageError) {
            detail = detail + "\n" + Core.bundle.get("replaymod.error.compatHint");
        }
        statusText = Core.bundle.format(key, detail);
        Log.err("Replay UI action failed", error);
    }

    /** Shortens long replay names so narrow mobile layouts do not overflow. */
    private static String shorten(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, Math.max(0, maxLength - 3)) + "...";
    }
}
