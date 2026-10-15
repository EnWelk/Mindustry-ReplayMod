package com.enwelk.replaymod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import arc.Core;
import arc.files.Fi;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.Vars;
import mindustry.gen.Icon;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;

public class ReplayDialog extends BaseDialog {
    private final ReplayMod mod;
    private final ReplayController controller;
    private final Table replayList = new Table();
    private final Label status;
    private final TextButton recordButton;
    private final TextButton playButton;
    private final TextButton stopButton;
    private final TextButton deleteButton;
    private final arc.scene.ui.TextField nameField;

    private Fi selectedReplay;
    private String statusText = "";

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

        recordButton = cont.button("Record", Icon.save, this::record)
            .width(256f).height(52f).get();
        cont.row();
        playButton = cont.button("Play", Icon.play, this::play)
            .width(256f).height(52f).get();
        cont.row();
        stopButton = cont.button("Stop", Icon.cancel, this::stop)
            .width(256f).height(52f).get();
        cont.row();
        deleteButton = cont.button("Delete", Icon.trash, this::delete)
            .width(256f).height(52f).get();
        cont.row();

        ScrollPane pane = new ScrollPane(replayList);
        pane.setFadeScrollBars(false);
        pane.setScrollingDisabled(true, false);
        cont.add(pane).grow().minHeight(180f).maxHeight(300f).padTop(8f);

        buttons.button("Refresh", Icon.refresh, this::refresh).size(180f, 64f);
        addCloseButton();

        cont.update(() -> {
            status.setText(statusText());
            recordButton.setDisabled(controller.isActive() || !canRecord());
            playButton.setDisabled(controller.isActive() || selectedReplay == null);
            stopButton.setDisabled(!controller.isActive());
            deleteButton.setDisabled(selectedReplay == null);
        });
        shown(this::refresh);
        refresh();
    }

    private boolean canRecord() {
        return Vars.state != null && Vars.state.isGame() && Vars.control != null &&
            Vars.control.input instanceof mindustry.input.DesktopInput;
    }

    private String statusText() {
        if (!statusText.isEmpty()) return statusText;
        if (controller.isRecording()) return Core.bundle.get("replaymod.status.recording");
        if (controller.isPlaying()) return Core.bundle.get("replaymod.status.playing");
        return Core.bundle.get("replaymod.status.idle");
    }

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

    private void startRecording(Path path) {
        try {
            controller.startRecording(path);
            statusText = "";
            refresh();
        } catch (IOException | RuntimeException e) {
            reportFailure("replaymod.error.record", e);
        }
    }

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

    private void stop() {
        try {
            controller.stop();
            statusText = "";
            refresh();
        } catch (IOException | RuntimeException e) {
            reportFailure("replaymod.error.stop", e);
        }
    }

    private void refresh() {
        Fi directory = mod.replayDirectory();
        directory.mkdirs();

        Fi[] files = directory.list(".rep");
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

    private void reportFailure(String key, Exception error) {
        statusText = Core.bundle.format(key, String.valueOf(error.getMessage()));
        Log.err("Replay UI action failed", error);
    }
}
