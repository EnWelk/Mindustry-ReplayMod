package com.enwelk.replaymod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

import com.enwelk.replaymod.rep.RepReader;

import arc.Core;
import arc.files.Fi;
import arc.scene.ui.CheckBox;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.Slider;
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
    private final Table playbackControls = new Table();
    private final Label status;
    private final TextButton recordButton;
    private final TextButton playButton;
    private final TextButton pauseButton;
    private final TextButton stopButton;
    private final TextButton speedButton;
    private final TextButton seekBackButton;
    private final TextButton seekForwardButton;
    private final Slider timeline;
    private final Label timelineLabel;
    private final TextButton deleteButton;
    private final TextButton exportButton;
    private final TextButton exportAllButton;
    private final CheckBox compressExport;
    private final arc.scene.ui.TextField nameField;
    private final BaseDialog speedDialog;
    private final BaseDialog endDialog;
    private boolean updatingTimeline;
    private long pendingTimelineTick;

    private Fi selectedReplay;
    private String statusText = "";
    private boolean hasReplays;

    /** Builds the replay manager and its playback/export controls. */
    public ReplayDialog(ReplayMod mod, ReplayController controller) {
        super(Core.bundle.get("replaymod.title"));
        this.mod = mod;
        this.controller = controller;
        speedDialog = createSpeedDialog();
        endDialog = createEndDialog();
        controller.setPlaybackFinishedListener(this::showPlaybackEnded);

        cont.margin(12f);
        cont.add(Core.bundle.get("replaymod.name")).left().padRight(8f);

        status = cont.add("").left().growX().pad(6f).get();
        cont.row();

        playbackControls.defaults().size(256f, 52f).pad(4f);
        pauseButton = playbackControls.button(
            Core.bundle.get("replaymod.pause"), Icon.pause, this::togglePause
        ).get();
        stopButton = playbackControls.button(
            Core.bundle.get("replaymod.stop"), Icon.cancel, this::stop
        ).get();
        playbackControls.row();
        seekBackButton = playbackControls.button(
            Core.bundle.get("replaymod.seek.back"), Icon.left, () -> seekBy(-600L)
        ).get();
        seekForwardButton = playbackControls.button(
            Core.bundle.get("replaymod.seek.forward"), Icon.right, () -> seekBy(600L)
        ).get();
        playbackControls.row();
        speedButton = playbackControls.button("", Icon.add, this::showSpeedOptions).get();
        timelineLabel = playbackControls.add("0:00 / 0:00").left().pad(4f).get();
        playbackControls.row();
        timeline = new Slider(0f, 1f, 1f, false);
        playbackControls.add(timeline).colspan(2).growX().height(32f).pad(8f);
        playbackControls.row();
        timeline.changed(() -> {
            if (updatingTimeline) return;
            pendingTimelineTick = Math.round(timeline.getValue());
            if (!timeline.isDragging()) controller.seek(pendingTimelineTick);
        });
        cont.add(playbackControls).growX();
        playbackControls.visible(() -> controller.isPlaying());
        cont.row();

                Table controls = cont.table().growX().get();
        controls.defaults()
            .size(256f, 52f)
            .pad(4f)
            .left();

        recordButton = controls.button(
            Core.bundle.get("replaymod.record"), Icon.save, this::record
        ).get();
        playButton = controls.button(
            Core.bundle.get("replaymod.play"), Icon.play, this::play
        ).get();
        controls.row();

        deleteButton = controls.button(
            Core.bundle.get("replaymod.delete"), Icon.trash, this::delete
        ).get();
        exportButton = controls.button(
            Core.bundle.get("replaymod.export"), Icon.export, this::export
        ).get();
        controls.row();

        controls.button(
            Core.bundle.get("replaymod.import"), Icon.download, this::chooseImport
        ).get();
        exportAllButton = controls.button(
            Core.bundle.get("replaymod.exportAll"), Icon.export, this::exportAll
        ).get();

        cont.row();

        nameField = cont.field("", text -> {})
            .maxTextLength(80)
            .width(300f)
            .left()
            .get();
        nameField.setMessageText(Core.bundle.get("replaymod.name.placeholder"));
        cont.row();

        compressExport = cont.check(
            Core.bundle.get("replaymod.export.compress"),
            true,
            checked -> {}
        ).left().padTop(6f).get();
        cont.row();

        ScrollPane pane = new ScrollPane(replayList);
        pane.setFadeScrollBars(false);
        pane.setScrollingDisabled(true, false);
        cont.add(pane).grow().minHeight(180f).maxHeight(300f).padTop(8f);

        buttons.button(Core.bundle.get("replaymod.refresh"), Icon.refresh, this::refresh).size(180f, 64f);
        addCloseButton();

        cont.update(() -> {
            status.setText(statusText());
            recordButton.setDisabled(controller.isActive() || !canRecord());
            playButton.setDisabled(controller.isActive() || selectedReplay == null || Vars.net.active());
            pauseButton.setText(controller.isPlaybackPaused()
                ? Core.bundle.get("replaymod.resume")
                : Core.bundle.get("replaymod.pause"));
            stopButton.setDisabled(!controller.isActive());
            boolean playing = controller.isPlaying();
            pauseButton.setDisabled(!playing);
            speedButton.setDisabled(!playing);
            seekBackButton.setDisabled(!playing || controller.currentTick() <= 0L);
            seekForwardButton.setDisabled(!playing ||
                controller.currentTick() >= controller.durationTicks());
            long duration = controller.durationTicks();
            timeline.setRange(0f, Math.max(1L, duration));
            updatingTimeline = true;
            timeline.setValue(Math.min(controller.currentTick(), Math.max(1L, duration)));
            updatingTimeline = false;
            timeline.setDisabled(!playing || duration <= 0L || controller.isSeeking());
            timelineLabel.setText(formatTime(controller.currentTick()) + " / " + formatTime(duration));
            speedButton.setText(Core.bundle.format("replaymod.speed.current", controller.playbackSpeed()));
            deleteButton.setDisabled(selectedReplay == null);
            exportButton.setDisabled(selectedReplay == null);
            exportAllButton.setDisabled(!hasReplays);
        });
        shown(this::refresh);
        refresh();
    }

    /** Checks whether recording is available in the current game state. */
    private boolean canRecord() {
        return !Vars.net.active() && Vars.state != null && Vars.state.isGame() && Vars.control != null &&
            Vars.control.input instanceof mindustry.input.DesktopInput;
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
                Vars.ui.showConfirm("Confirm", Core.bundle.format("replaymod.overwrite", destination.name()), () -> startRecording(path));
            } else {
                startRecording(path);
            }
        } catch (IOException | RuntimeException e) {
            reportFailure("replaymod.error.record", e);
        }
    }

    /** Starts recording and reports any startup error. */
    private void startRecording(Path path) {
        try {
            controller.startRecording(path);
            statusText = "";
            refresh();
        } catch (IOException | RuntimeException e) {
            reportFailure("replaymod.error.record", e);
        }
    }

    /** Starts playback for the selected replay. */
    private void play() {
        if (selectedReplay == null) return;
        try {
            controller.startPlayback(selectedReplay.file().toPath());
            statusText = "";
            hide();
        } catch (IOException | RuntimeException e) {
            reportFailure("replaymod.error.play", e);
        }
    }

    /** Pauses or resumes the active replay. */
    private void togglePause() {
        controller.setPlaybackPaused(!controller.isPlaybackPaused());
    }

    /** Opens a dropdown-style list of replay speed multipliers. */
    private void showSpeedOptions() {
        if (controller.isPlaying()) speedDialog.show();
    }

    /** Creates the replay speed selection popup. */
    private BaseDialog createSpeedDialog() {
        BaseDialog dialog = new BaseDialog(Core.bundle.get("replaymod.speed"));
        dialog.cont.button(Core.bundle.get("replaymod.speed.normal"), () -> {
            controller.setPlaybackSpeed(1);
            dialog.hide();
        }).width(220f).height(48f).row();
        for (int speed : new int[]{2, 3, 5, 10}) {
            dialog.cont.button(Core.bundle.format("replaymod.speed.choice", speed), () -> {
                controller.setPlaybackSpeed(speed);
                dialog.hide();
            }).width(220f).height(48f).row();
        }
        dialog.addCloseButton();
        return dialog;
    }

    /** Creates the end-of-playback screen. */
    private BaseDialog createEndDialog() {
        BaseDialog dialog = new BaseDialog(Core.bundle.get("replaymod.finished.title"));
        dialog.cont.add(Core.bundle.get("replaymod.finished.message")).pad(12f);
        dialog.buttons.button(Core.bundle.get("replaymod.finished.replayAgain"), Icon.play, () -> {
            dialog.hide();
            play();
        }).size(190f, 58f);
        dialog.buttons.button(Core.bundle.get("replaymod.finished.manager"), Icon.menu, () -> {
            dialog.hide();
            show();
        }).size(190f, 58f);
        dialog.addCloseButton();
        return dialog;
    }

    /** Shows the completion screen and refreshes replay controls. */
    private void showPlaybackEnded() {
        statusText = Core.bundle.get("replaymod.status.finished");
        refresh();
        endDialog.show();
    }

    /** Seeks relative to the current replay position. */
    private void seekBy(long ticks) {
        controller.seek(controller.currentTick() + ticks);
    }

    /** Formats replay ticks as minutes and seconds. */
    private String formatTime(long ticks) {
        long seconds = ticks / 60L;
        return (seconds / 60L) + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60L);
    }

    /** Stops recording or playback. */
    private void stop() {
        try {
            controller.stop();
            statusText = "";
            refresh();
        } catch (IOException | RuntimeException e) {
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
            replayList.add(Core.bundle.get("replaymod.empty")).left().pad(8f);
            return;
        }

        for (Fi file : files) {
            String size = String.format(java.util.Locale.ROOT, "%.1f", file.length() / (1024d * 1024d));
            TextButton button = replayList.button(
                Core.bundle.format("replaymod.list.entry", file.nameWithoutExtension(), size),
                Styles.grayt, () -> selectedReplay = file).height(42f).get();
            button.update(() -> button.setChecked(file.equals(selectedReplay)));
            replayList.row();
        }
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
        } catch (IOException | RuntimeException e) {
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
            } catch (IOException | RuntimeException e) {
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
                Vars.ui.showConfirm(Core.bundle.get("confirm"),
                    Core.bundle.format("replaymod.import.overwrite", destination.name()),
                    () -> copyImportedReplay(source, destination));
            } else {
                copyImportedReplay(source, destination);
            }
        } catch (IOException | RuntimeException e) {
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
        } catch (IOException | RuntimeException e) {
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
            Vars.ui.showConfirm(Core.bundle.get("confirm"), Core.bundle.get("replaymod.exportAll.replace"),
                () -> writeAllVolumes(directory));
        } else {
            writeAllVolumes(directory);
        }
    }

    /** Writes the current replay collection as numbered ZIP volumes. */
    private void writeAllVolumes(Fi directory) {
        try {
            ReplayExporter.exportAll(mod.replayDirectory().list(".rep"), directory);
            statusText = Core.bundle.get("replaymod.status.exportedAll");
        } catch (IOException | RuntimeException e) {
            reportFailure("replaymod.error.export", e);
        }
    }

    /** Displays an export or file-operation failure. */
    private void reportFailure(String key, Exception error) {
        statusText = Core.bundle.format(key, String.valueOf(error.getMessage()));
        Log.err("Replay UI action failed", error);
    }
}
