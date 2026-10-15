package com.enwelk.replaymod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import arc.Core;
import arc.files.Fi;
import arc.scene.ui.CheckBox;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.util.Log;
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
    private final TextButton pauseButton;
    private final TextButton stopButton;
    private final TextButton deleteButton;
    private final TextButton exportButton;
    private final TextButton exportAllButton;
    private final CheckBox compressExport;
    private final arc.scene.ui.TextField nameField;

    private Fi selectedReplay;
    private String statusText = "";
    private boolean hasReplays;

    /** Builds the replay manager and its playback/export controls. */
    public ReplayDialog(ReplayMod mod, ReplayController controller) {
        super("@replaymod.title");
        this.mod = mod;
        this.controller = controller;

        cont.margin(12f);
        cont.add("@replaymod.name").left().padRight(8f);
        nameField = cont.field("replay", text -> {}).maxTextLength(80).width(300f).get();
        cont.row();

        status = cont.add("").left().growX().pad(6f).get();
        cont.row();

        recordButton = cont.button("@replaymod.record", Icon.save, this::record)
            .width(256f).height(52f).get();
        cont.row();
        playButton = cont.button("@replaymod.play", Icon.play, this::play)
            .width(256f).height(52f).get();
        cont.row();
        pauseButton = cont.button("@replaymod.pause", Icon.pause, this::togglePause)
            .width(256f).height(52f).get();
        cont.row();
        stopButton = cont.button("@replaymod.stop", Icon.cancel, this::stop)
            .width(256f).height(52f).get();
        cont.row();
        deleteButton = cont.button("@replaymod.delete", Icon.trash, this::delete)
            .width(256f).height(52f).get();
        cont.row();
        exportButton = cont.button("@replaymod.export", Icon.export, this::export)
            .width(256f).height(52f).get();
        cont.row();
        exportAllButton = cont.button("@replaymod.exportAll", Icon.export, this::exportAll)
            .width(256f).height(52f).get();
        cont.row();
        compressExport = cont.check("@replaymod.export.compress", true, checked -> {})
            .left().pad(8f).get();
        cont.row();

        ScrollPane pane = new ScrollPane(replayList);
        pane.setFadeScrollBars(false);
        pane.setScrollingDisabled(true, false);
        cont.add(pane).grow().minHeight(180f).maxHeight(300f).padTop(8f);

        buttons.button("@replaymod.refresh", Icon.refresh, this::refresh).size(180f, 64f);
        addCloseButton();

        cont.update(() -> {
            status.setText(statusText());
            recordButton.setDisabled(controller.isActive() || !canRecord());
            playButton.setDisabled(controller.isActive() || selectedReplay == null || Vars.net.active());
            pauseButton.setDisabled(!controller.isPlaying());
            pauseButton.setText(controller.isPlaybackPaused()
                ? Core.bundle.get("replaymod.resume")
                : Core.bundle.get("replaymod.pause"));
            stopButton.setDisabled(!controller.isActive());
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
        String name = nameField.getText().trim();
        try {
            Path path = mod.replayPath(name);
            Fi destination = new Fi(path.toFile());
            if (destination.exists()) {
                Vars.ui.showConfirm("@confirm", Core.bundle.format("replaymod.overwrite", destination.name()), () -> startRecording(path));
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
        replayList.defaults().growX().height(42f).pad(2f);
        if (files.length == 0) {
            replayList.add("@replaymod.empty").left().pad(8f);
            return;
        }

        for (Fi file : files) {
            TextButton button = replayList.button(file.nameWithoutExtension(), Styles.grayt,
                () -> selectedReplay = file).get();
            button.update(() -> button.setChecked(file.equals(selectedReplay)));
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
            .title("@replaymod.exportAll.title")
            .submit(this::confirmExportAll);
    }

    /** Confirms replacement before exporting numbered ZIP volumes. */
    private void confirmExportAll(Fi selectedFile) {
        Fi directory = selectedFile.parent();
        Fi[] existingVolumes = directory.list(file -> file.getName().matches("\\d{3,}\\.zip"));
        if (existingVolumes.length > 0) {
            Vars.ui.showConfirm("@confirm", Core.bundle.get("replaymod.exportAll.replace"),
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
