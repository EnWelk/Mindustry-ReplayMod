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

    private float lastZoom = -1f;

    /** Creates the spectator input handler used during replay playback. */
    public Viewer(ReplayController controller) {
        this.controller = controller;
    }

    /** The replay controller moves the unit; normal desktop movement must be ignored. */
    @Override
    protected void updateMovement(mindustry.gen.Unit unit) {
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
        if (!controller.isPlaybackPaused() && Vars.state != null && Vars.state.isPlaying()) {
            controller.syncPlaybackClock();
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

        float speed = (Core.input.keyDown(Binding.boost) ? 15f : 4.5f) *
            (Time.delta / controller.playbackSpeed());
        Core.camera.position.add(moveX * speed, moveY * speed);
        if (!Vars.mobile && Core.input.keyDown(Binding.pan)) {
            float panX = Math.max(-1f, Math.min(1f,
                (Core.input.mouseX() - Core.graphics.getWidth() / 2f) * 0.005f));
            float panY = Math.max(-1f, Math.min(1f,
                (Core.input.mouseY() - Core.graphics.getHeight() / 2f) * 0.005f));
            Core.camera.position.add(panX * speed, panY * speed);
        }

        float zoom = Core.input.axisTap(Binding.zoom);
        if (zoom != 0f) Vars.renderer.scaleCamera(zoom);
    }

    @Override
    public boolean pan(float x, float y, float deltaX, float deltaY) {
        if (!controller.isPlaying() || Core.scene.hasDialog() ||
            Core.camera == null || Core.graphics == null) return false;

        float screenWidth = Core.graphics.getWidth();
        if (screenWidth <= 0f) return false;

        float scale = Core.camera.width / screenWidth;
        Core.camera.position.x -= deltaX * scale;
        Core.camera.position.y -= deltaY * scale;
        clampCameraToWorld();
        return false;
    }

    @Override
    public boolean zoom(float initialDistance, float distance) {
        if (!controller.isPlaying() || Vars.renderer == null || initialDistance <= 0f) return false;
        if (lastZoom < 0f) lastZoom = Vars.renderer.getScale();
        Vars.renderer.setScale(distance / initialDistance * lastZoom);
        return true;
    }

    private void clampCameraToWorld() {
        if (Vars.world == null) return;
        Core.camera.position.clamp(
            -Core.camera.width / 4f,
            -Core.camera.height / 4f,
            Vars.world.unitWidth() + Core.camera.width / 4f,
            Vars.world.unitHeight() + Core.camera.height / 4f);
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
        lastZoom = -1f;
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

    /** Snaps the camera to the player when the player position changes too quickly. */
    /**
    private void snapCameraOnPlayerTeleport() {
        if (Vars.player == null || Vars.player.unit() == null || Core.camera == null) {
            lastPlayerX = Float.NaN;
            lastPlayerY = Float.NaN;
            return;
        }

        float x = Vars.player.unit().x;
        float y = Vars.player.unit().y;

        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            lastPlayerX = Float.NaN;
            lastPlayerY = Float.NaN;
            return;
        }

        if (Float.isFinite(lastPlayerX) && Float.isFinite(lastPlayerY)) {
            float dx = x - lastPlayerX;
            float dy = y - lastPlayerY;
            float distanceSquared = dx * dx + dy * dy;

            if (distanceSquared > TELEPORT_SNAP_DISTANCE * TELEPORT_SNAP_DISTANCE) {
                Core.camera.position.set(x, y);
            }
        }

        lastPlayerX = x;
        lastPlayerY = y;
    }
    */
}
