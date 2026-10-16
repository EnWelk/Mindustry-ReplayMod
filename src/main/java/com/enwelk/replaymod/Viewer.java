package com.enwelk.replaymod;

import arc.Core;
import arc.input.KeyCode;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.input.Binding;
import mindustry.input.DesktopInput;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

/** Routes safe player input to the replay's free camera and inspection UI. */
public class Viewer extends DesktopInput {
    private final ReplayController controller;

    /** Creates the spectator input handler used during replay playback. */
    public Viewer(ReplayController controller) {
        this.controller = controller;
    }

    /** Advances playback and moves the spectator camera without controlling the player unit. */
    @Override
    public void update() {
        if (!controller.isPlaying()) return;
        if (controller.isSeeking()) {
            controller.advanceSeek();
            return;
        }
        updateCamera();
        if (!controller.isPlaybackPaused()) {
            controller.syncPlaybackClock();
            controller.updatePlayback();
        }
    }

    /** Keeps camera movement independent from the unit controlled by playback. */
    private void updateCamera() {
        if (Core.scene.hasDialog() || Core.scene.hasKeyboard() ||
            Core.camera == null || Vars.renderer == null) return;

        float moveX = Core.input.axis(Binding.moveX);
        float moveY = Core.input.axis(Binding.moveY);
        float length = (float)Math.sqrt(moveX * moveX + moveY * moveY);
        if (length > 1f) {
            moveX /= length;
            moveY /= length;
        }

        float speed = (Core.input.keyDown(Binding.boost) ? 15f : 4.5f) * Time.delta;
        Core.camera.position.add(moveX * speed, moveY * speed);
        if (Core.input.keyDown(Binding.pan)) {
            float panX = Math.max(-1f, Math.min(1f,
                (Core.input.mouseX() - Core.graphics.getWidth() / 2f) * 0.005f));
            float panY = Math.max(-1f, Math.min(1f,
                (Core.input.mouseY() - Core.graphics.getHeight() / 2f) * 0.005f));
            Core.camera.position.add(panX * speed, panY * speed);
        }

        float zoom = Core.input.axisTap(Binding.zoom);
        if (zoom != 0f) Vars.renderer.scaleCamera(zoom);
    }

    /** Lets scene controls handle their own clicks and inspects buildings in the world. */
    @Override
    public boolean tap(float x, float y, int count, KeyCode button) {
        if (button != KeyCode.mouseLeft || Core.scene.hasMouse() ||
            Core.scene.hasDialog() || Vars.world == null) return false;

        inspectBuilding();
        return true;
    }

    /** Captures world gestures only; clicks over UI are left for the scene. */
    @Override
    public boolean touchDown(float x, float y, int pointer, KeyCode button) {
        return !Core.scene.hasMouse() && !Core.scene.hasDialog();
    }

    /** Opens read-only block inspection without forwarding gameplay actions. */
    private void inspectBuilding() {
        Building building = Vars.world.buildWorld(Core.input.mouseWorldX(), Core.input.mouseWorldY());
        if (building == null) {
            config.hideConfig();
            inv.hide();
            return;
        }

        inv.hide();
        if (building instanceof LogicBuild logic) {
            Vars.ui.logic.show(logic.code, logic.executor, logic.block.privileged, ignored -> {});
        } else if (building.block.configurable && building.interactable(Vars.player.team()) &&
            building.shouldShowConfigure(Vars.player)) {
            config.showConfig(building);
        } else if (building.block.hasItems && building.items != null && building.items.total() > 0) {
            inv.showFor(building);
        } else {
            config.hideConfig();
            Vars.ui.content.show(building.block);
        }
    }
}
