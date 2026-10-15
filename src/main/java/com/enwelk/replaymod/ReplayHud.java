package com.enwelk.replaymod;

import arc.input.KeyCode;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.gen.Icon;

public class ReplayHud {
    private final ReplayMod mod;
    private final ReplayController controller;
    private ReplayDialog dialog;

    public ReplayHud(ReplayMod mod, ReplayController controller) {
        this.mod = mod;
        this.controller = controller;
    }

    public void load() {
        arc.Events.on(ClientLoadEvent.class, event -> {
            if (Vars.ui == null || Vars.ui.menufrag == null) return;

            dialog = new ReplayDialog(mod, controller);
            Vars.ui.menufrag.addButton("@replaymod.open", Icon.play, this::open);

            Table hudButton = new Table();
            TextButton button = hudButton.button("@replaymod.open", Icon.play, () -> {})
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

                @Override
                public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode key) {
                    if (activePointer != -1) return false;
                    activePointer = pointer;
                    downStageX = lastStageX = event.stageX;
                    downStageY = lastStageY = event.stageY;
                    dragged = false;
                    return true;
                }

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

    /** Opens replay controls; the replay input handler remains active underneath. */
    private void open() {
        dialog.show();
    }
}
