package com.enwelk.replaymod;

import java.io.IOException;

import arc.Core;
import arc.input.KeyCode;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.ui.Button;
import arc.scene.ui.Label;
import arc.scene.ui.Slider;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.gen.Icon;
import mindustry.ui.dialogs.BaseDialog;

public class ReplayHud {
    private final ReplayMod mod;
    private final ReplayController controller;
    private ReplayDialog dialog;
    private Table playbackControls;
    private TextButton pauseButton;
    private TextButton speedButton;
    private TextButton seekBackButton;
    private TextButton seekForwardButton;
    private Slider timeline;
    private Label timelineLabel;
    private BaseDialog speedDialog;

    /** Creates the HUD overlay for active replay controls. */
    public ReplayHud(ReplayMod mod, ReplayController controller) {
        this.mod = mod;
        this.controller = controller;
    }

    /** Adds the draggable replay-menu button and playback controls. */
    public void load() {
        arc.Events.on(ClientLoadEvent.class, event -> {
            if (Vars.ui == null || Vars.ui.menufrag == null) return;

            dialog = new ReplayDialog(mod, controller);
            Vars.ui.menufrag.addButton(arc.Core.bundle.get("replaymod.open"), Icon.play, this::open);
            addPlaybackControls();
            Element skipWave = arc.Core.scene.find("skip");
            if (skipWave != null) {
                skipWave.addCaptureListener(new InputListener() {
                    /** Prevents advancing waves during replay playback. */
                    @Override
                    public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode key) {
                        return controller.isPlaying();
                    }
                });
                skipWave.update(() -> {
                    if (skipWave instanceof Button button) {
                        button.setDisabled(controller.isPlaying());
                    }
                });
            }

            Table hudButton = new Table();
            TextButton button = hudButton.button(arc.Core.bundle.get("replaymod.open"), Icon.play, () -> {})
                .size(150f, 48f).get();
            hudButton.setSize(150f, 48f);
            hudButton.setPosition(
                Math.max(0f, Math.min(
                    ReplayMod.ofLeft,
                    Math.max(0f, Vars.ui.hudGroup.getWidth() - hudButton.getWidth())
                )),
                Math.max(0f, Math.min(
                    ReplayMod.ofTop,
                    Math.max(0f, Vars.ui.hudGroup.getHeight() - hudButton.getHeight())
                ))
            );
            Vars.ui.hudGroup.addChild(hudButton);

            button.addListener(new InputListener() {
                private float downStageX;
                private float downStageY;
                private float lastStageX;
                private float lastStageY;
                private boolean dragged;
                private int activePointer = -1;

                /** Begins dragging the replay button. */
                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode key) {
                    if (activePointer != -1) return false;
                    activePointer = pointer;
                    downStageX = lastStageX = event.stageX;
                    downStageY = lastStageY = event.stageY;
                    dragged = false;
                    return true;
                }

                /** Moves the button while clamping it to the HUD bounds. */
                @Override
                public void touchDragged(InputEvent event, float x, float y, int pointer) {
                    if (pointer != activePointer) return;

                    float dx = event.stageX - lastStageX;
                    float dy = event.stageY - lastStageY;
                    if (!dragged && (Math.abs(event.stageX - downStageX) > 6f ||
                        Math.abs(event.stageY - downStageY) > 6f)) {
                        dragged = true;
                    }
                    if (dragged) {
                        hudButton.moveBy(dx, dy);
                        hudButton.setPosition(
                            Math.max(0f, Math.min(
                                hudButton.x,
                                Math.max(0f, Vars.ui.hudGroup.getWidth() - hudButton.getWidth())
                            )),
                            Math.max(0f, Math.min(
                                hudButton.y,
                                Math.max(0f, Vars.ui.hudGroup.getHeight() - hudButton.getHeight())
                            ))
                        );
                    }
                    lastStageX = event.stageX;
                    lastStageY = event.stageY;
                }

                /** Saves the button position or opens the replay manager. */
                @Override
                public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode key) {
                    if (pointer != activePointer) return;
                    if (dragged) {
                        mod.saveButtonPosition(hudButton.x, hudButton.y);
                    } else if (x >= 0f && y >= 0f &&
                        x < button.getWidth() && y < button.getHeight()) {
                        open();
                    }
                    activePointer = -1;
                }
            });
        });
    }

    /** Adds playback controls directly to the game HUD. */
    private void addPlaybackControls() {
        playbackControls = new Table();
        playbackControls.defaults().size(140f, 48f).pad(3f);
        pauseButton = playbackControls.button("", Icon.pause, this::togglePause).get();
        TextButton stopButton = playbackControls.button(
            Core.bundle.get("replaymod.stop"), Icon.cancel, this::stopPlayback
        ).get();
        seekBackButton = playbackControls.button(
            Core.bundle.get("replaymod.seek.back"), Icon.left, () -> seekBy(-600L)
        ).get();
        seekForwardButton = playbackControls.button(
            Core.bundle.get("replaymod.seek.forward"), Icon.right, () -> seekBy(600L)
        ).get();
        speedButton = playbackControls.button("", Icon.add, this::showSpeedOptions).get();
        playbackControls.row();
        timelineLabel = playbackControls.add("0:00 / 0:00").left().pad(3f).get();
        timeline = new Slider(0f, 1f, 1f, false);
        playbackControls.add(timeline).colspan(4).growX().height(28f).pad(3f);
        timeline.addListener(new InputListener() {
            /** Captures slider gestures and seeks when the pointer is released. */
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode key) {
                return controller.isPlaying();
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode key) {
                if (controller.isPlaying()) {
                    controller.seek(Math.round(timeline.getValue()));
                }
            }
        });
        playbackControls.setSize(760f, 112f);
        playbackControls.setPosition(0f, 12f);
        playbackControls.visible(() -> controller.isPlaying());
        playbackControls.update(() -> {
            playbackControls.setPosition(
                Math.max(0f, (Vars.ui.hudGroup.getWidth() - playbackControls.getWidth()) / 2f),
                12f
            );
            boolean playing = controller.isPlaying();
            pauseButton.setDisabled(!playing);
            pauseButton.setText(Core.bundle.get(controller.isPlaybackPaused()
                ? "replaymod.resume"
                : "replaymod.pause"));
            stopButton.setDisabled(!playing);
            seekBackButton.setDisabled(!playing || controller.currentTick() <= 0L);
            seekForwardButton.setDisabled(!playing ||
                controller.currentTick() >= controller.durationTicks());
            speedButton.setDisabled(!playing);
            speedButton.setText(Core.bundle.format("replaymod.speed.current", controller.playbackSpeed()));
            long duration = controller.durationTicks();
            timeline.setRange(0f, Math.max(1L, duration));
            timeline.setDisabled(!playing || duration <= 0L || controller.isSeeking());
            if (!timeline.isDragging()) {
                timeline.setValue(Math.min(controller.currentTick(), Math.max(1L, duration)));
            }
            timelineLabel.setText(formatTime(controller.currentTick()) + " / " + formatTime(duration));
        });
        Vars.ui.hudGroup.addChild(playbackControls);
        speedDialog = createSpeedDialog();
    }

    /** Builds the list of supported replay speed choices. */
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

    /** Toggles playback pause state and updates its button label. */
    private void togglePause() {
        controller.setPlaybackPaused(!controller.isPlaybackPaused());
    }

    /** Shows the available playback speed multipliers. */
    private void showSpeedOptions() {
        if (controller.isPlaying()) speedDialog.show();
    }

    /** Seeks by a relative number of simulation ticks. */
    private void seekBy(long ticks) {
        long target = Math.max(0L, Math.min(controller.currentTick() + ticks, controller.durationTicks()));
        controller.seek(target);
    }

    /** Stops playback and reports any shutdown error. */
    private void stopPlayback() {
        try {
            controller.stopPlaybackManually();
        } catch (IOException | RuntimeException e) {
            Log.err("Could not stop replay playback", e);
        }
    }

    /** Formats a replay tick as a user-facing time value. */
    private String formatTime(long ticks) {
        long seconds = ticks / 60L;
        return (seconds / 60L) + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60L);
    }

    /** Opens replay controls; the replay input handler remains active underneath. */
    private void open() {
        dialog.show();
    }
}
