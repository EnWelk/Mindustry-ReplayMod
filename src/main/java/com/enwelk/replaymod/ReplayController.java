package com.enwelk.replaymod;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import arc.Core;
import arc.input.KeyCode;
import arc.input.KeyBind.Axis;
import arc.math.Mathf;
import arc.util.Log;
import arc.math.geom.Vec2;
import arc.util.Time;
import arc.util.io.Reads;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.gen.Mechc;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.input.DesktopInput;
import mindustry.input.Binding;
import mindustry.input.InputHandler;
import mindustry.input.PlaceMode;
import mindustry.io.TypeIO;
import mindustry.world.Block;
import mindustry.world.Tile;
import com.enwelk.replaymod.rep.RepReader;
import com.enwelk.replaymod.rep.RepWriter;

public class ReplayController {
    private InputHandler previousInput;
    private RepWriter writer;
    private ReplayRecordInput recordingInput;
    private RepReader reader;
    private Viewer playbackInput;
    private Path playbackPath;
    private RepReader.ReplayRecord nextRecord;
    private RepReader.TickRecord lastTick;
    private long currentTick;
    private long playbackOrigin;
    private long durationTicks;
    private long seekTarget = -1L;
    private boolean playbackPaused;
    private boolean pauseAfterSeek;
    private int playbackSpeed = 1;
    private Axis previousSkipWaveBinding;
    private Runnable playbackFinished = () -> {};

    public void startRecording(Path path) throws IOException {
        ensureIdle();
        if (Vars.net.active()) {
            throw new IllegalStateException("Full multiplayer replay recording is not supported yet");
        }
        if (Vars.control == null || Vars.player == null || Vars.state == null || !Vars.state.isGame()) {
            throw new IllegalStateException("Recording can only start during a game");
        }
        if (!(Vars.control.input instanceof DesktopInput)) {
            throw new IllegalStateException("Replay recording currently requires desktop input");
        }

        RepWriter newWriter = new RepWriter(path);
        try {
            previousInput = Vars.control.input;
            writer = newWriter;
            recordingInput = new ReplayRecordInput(writer);
            Vars.control.setInput(recordingInput);
        } catch (RuntimeException e) {
            if (recordingInput != null) recordingInput.close();
            recordingInput = null;
            writer = null;
            previousInput = null;
            try {
                newWriter.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    public boolean isActive() {
        return recordingInput != null || reader != null;
    }

    public boolean isRecording() {
        return recordingInput != null;
    }

    public boolean isPlaying() {
        return reader != null;
    }

    public long currentTick() {
        return currentTick;
    }

    public long durationTicks() {
        return durationTicks;
    }

    public int playbackSpeed() {
        return playbackSpeed;
    }

    public void setPlaybackFinishedListener(Runnable listener) {
        playbackFinished = listener == null ? () -> {} : listener;
    }

    /** Sets the simulation multiplier used while playback is running. */
    public void setPlaybackSpeed(int speed) {
        if (speed != 1 && speed != 2 && speed != 3 && speed != 5 && speed != 10) {
            throw new IllegalArgumentException("Unsupported replay speed: " + speed);
        }
        playbackSpeed = speed;
        if (reader != null && seekTarget < 0L) installPlaybackTimeScale();
    }

    /** Requests a seek; backward seeks restore the embedded map before simulating forward. */
    public void seek(long targetTick) {
        if (reader == null) return;
        long target = Math.max(0L, Math.min(targetTick, durationTicks));
        if (target < currentTick) {
            try {
                restartPlayback();
            } catch (IOException | RuntimeException e) {
                Log.err("Could not restart replay for backward seek", e);
                return;
            }
            if (reader == null) return;
        }
        if (target == currentTick) return;
        seekTarget = target;
        pauseAfterSeek = playbackPaused;
        playbackPaused = false;
        if (Vars.state != null) Vars.state.set(GameState.State.playing);
    }

    /** Runs simulation ticks without a frame-rate cap until the requested tick is reached. */
    void advanceSeek() {
        if (reader == null || seekTarget < 0L || Vars.state == null) return;
        long target = seekTarget;
        Time.setDeltaProvider(() -> 1f);
        try {
            while (reader != null && currentTick < target) {
                double before = Vars.state.tick;
                Vars.logic.update();
                if (reader == null) return;
                Vars.state.tick = before + 1d;
                currentTick = (long)Math.floor(Vars.state.tick);
                updatePlayback();
            }
        } finally {
            if (reader != null) {
                seekTarget = -1L;
                installPlaybackTimeScale();
                playbackPaused = pauseAfterSeek;
                if (playbackPaused) Vars.state.set(GameState.State.paused);
            }
        }
    }

    /** Advances the replay clock to match the simulation multiplier. */
    void syncPlaybackClock() {
        if (reader == null || playbackPaused || seekTarget >= 0L || Vars.state == null) return;
        float rawDelta = Core.graphics.getDeltaTime() * 60f;
        Vars.state.tick += Time.delta - rawDelta;
    }

    public boolean isPlaybackPaused() {
        return playbackPaused;
    }

    public boolean isSeeking() {
        return seekTarget >= 0L;
    }

    /** Pauses or resumes replay simulation. */
    public void setPlaybackPaused(boolean paused) {
        if (reader == null) return;
        playbackPaused = paused;
        if (Vars.state != null && Vars.state.isGame()) {
            Vars.state.set(paused ? GameState.State.paused : GameState.State.playing);
        }
    }

    /** Starts playback and replaces the active game input handler. */
    public void startPlayback(Path path) throws IOException {
        ensureIdle();
        if (Vars.net.active()) {
            throw new IllegalStateException("Replay playback is only supported offline");
        }
        if (Vars.control == null || Vars.player == null) {
            throw new IllegalStateException("Replay playback requires a loaded client");
        }

        InputHandler input = Vars.control.input;
        RepReader newReader = new RepReader(path);
        try {
            newReader.scanTimeline();
            previousInput = input;
            reader = newReader;
            playbackPath = path;
            playbackOrigin = newReader.firstTick();
            durationTicks = Math.max(0L, newReader.endTick() - playbackOrigin);
            playbackInput = new Viewer(this);
            nextRecord = reader.readNext();
            lastTick = null;
            currentTick = 0L;
            playbackPaused = false;
            Vars.state.tick = 0d;
            Vars.control.setInput(playbackInput);
            previousSkipWaveBinding = Binding.skipWave.value;
            Binding.skipWave.value = new Axis(KeyCode.unset);
            installPlaybackTimeScale();
            Vars.state.set(GameState.State.playing);
            updatePlayback();
        } catch (IOException | RuntimeException e) {
            if (Vars.control != null && Vars.control.input == playbackInput) {
                try {
                    Vars.control.setInput(input);
                } catch (RuntimeException restoreError) {
                    e.addSuppressed(restoreError);
                }
            }
            reader = null;
            playbackInput = null;
            nextRecord = null;
            lastTick = null;
            playbackPaused = false;
            previousInput = null;
            try {
                newReader.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    public void stop() throws IOException {
        if (recordingInput != null) {
            ReplayRecordInput oldInput = recordingInput;
            RepWriter oldWriter = writer;
            recordingInput = null;
            writer = null;
            oldInput.close();
            try {
                if (Vars.control != null && Vars.control.input == oldInput && previousInput != null) {
                    Vars.control.setInput(previousInput);
                }
            } finally {
                previousInput = null;
                if (oldWriter != null) {
                    try {
                        oldWriter.finish(Vars.state == null ? 0 : Math.round(Vars.state.tick));
                    } catch (IOException finishError) {
                        try {
                            oldWriter.close();
                        } catch (IOException closeError) {
                            finishError.addSuppressed(closeError);
                        }
                        throw finishError;
                    }
                    try {
                        oldWriter.close();
                    } catch (IOException closeError) {
                        throw closeError;
                    }
                }
            }
            return;
        }

        if (reader != null) {
            stopPlayback();
            return;
        }

        throw new IllegalStateException("No replay recording or playback is active");
    }

    /** Advances replay records and applies the latest recorded player state. */
    void updatePlayback() {
        if (reader == null || Vars.state == null || Vars.player == null) return;

        long currentTick = (long)Math.floor(Vars.state.tick);
        this.currentTick = currentTick;
        long replayTick = playbackOrigin + currentTick;
        boolean finished = false;
        try {
            while (nextRecord != null && nextRecord.tick() <= replayTick) {
                if (nextRecord instanceof RepReader.TickRecord tickRecord) {
                    lastTick = tickRecord;
                } else if (nextRecord instanceof RepReader.ActionRecord actionRecord) {
                    for (Action action : actionRecord.actions()) {
                        apply(action);
                    }
                } else if (nextRecord instanceof RepReader.EndRecord) {
                    finished = true;
                    nextRecord = null;
                    break;
                }
                nextRecord = reader.readNext();
            }
            if (nextRecord == null && !finished) finished = true;
        } catch (IOException | RuntimeException e) {
            Log.err("Replay playback stopped because a replay record could not be read", e);
            finished = true;
        }

        applyLastTick();
        if (finished) {
            try {
                stopPlayback();
                playbackFinished.run();
            } catch (IOException e) {
                Log.err("Failed to close replay after playback", e);
            }
        }
    }

    private void applyLastTick() {
        if (lastTick == null || Vars.player == null || Vars.player.unit() == null) return;

        Player player = Vars.player;
        Unit unit = player.unit();
        playbackInput.movement.set(lastTick.movementX(), lastTick.movementY());
        unit.movePref(playbackInput.movement);
        unit.aim(lastTick.aimX(), lastTick.aimY());
        player.shooting = lastTick.shooting();
        player.boosting = lastTick.boosting();
        boolean boosted = unit instanceof Mechc && unit.isFlying();
        unit.controlWeapons(true, player.shooting && !boosted);
        if (lastTick.mineX() < 0 || lastTick.mineY() < 0) {
            unit.mineTile = null;
        } else {
            unit.mineTile = Vars.world.tile(lastTick.mineX(), lastTick.mineY());
        }
        player.mouseX = lastTick.aimX();
        player.mouseY = lastTick.aimY();
    }

    private void apply(Action action) {
        Player player = Vars.player;
        if (player == null) return;

        if (action instanceof Action.BuildPlan plan) {
            Block block = Vars.content.block(plan.blockId);
            if (block == null || player.unit() == null) {
                Log.warn("Skipping replay build plan with missing block @", plan.blockId);
                return;
            }
            Object config = readConfig(plan.configType, plan.configInt, plan.configShortX,
                plan.configShortY, plan.configItemId, plan.configData);
            player.unit().plans.addLast(new BuildPlan(plan.x, plan.y, plan.rotation, block, config));
        } else if (action instanceof Action.BreakPlan plan) {
            if (player.unit() != null) player.unit().plans.addLast(new BuildPlan(plan.x, plan.y));
        } else if (action instanceof Action.Config config) {
            Building building = Vars.world.build(config.buildId);
            if (building != null) {
                Object value = readConfig(config.configType, config.configInt, config.configShortX,
                    config.configShortY, config.configItemId, config.configData);
                Call.tileConfig(player, building, value);
            }
        } else if (action instanceof Action.UnitControl control) {
            Unit unit = Groups.unit.getByID(control.unitId);
            if (unit != null) Call.unitControl(player, unit);
        } else if (action instanceof Action.UnitClear) {
            Call.unitClear(player);
        } else if (action instanceof Action.RotateBlock rotate) {
            Building building = Vars.world.build(rotate.buildId);
            if (building != null) Call.rotateBlock(player, building, rotate.direction != 0);
        } else if (action instanceof Action.TileTap tap) {
            Tile tile = Vars.world.tile(tap.x, tap.y);
            if (tile != null) InputHandler.tileTap(player, tile);
        } else if (action instanceof Action.BuildPause pause) {
            playbackInput.isBuilding = pause.isBuilding != 0;
        } else if (action instanceof Action.ModeChange change) {
            PlaceMode[] modes = PlaceMode.values();
            if (change.mode >= 0 && change.mode < modes.length) {
                playbackInput.mode = modes[change.mode];
            } else {
                Log.warn("Skipping replay with invalid placement mode @", change.mode);
            }
        } else if (action instanceof Action.PayloadAction payload) {
            applyPayload(payload, player);
        } else if (action instanceof Action.Command command) {
            applyCommand(command, player);
        }
    }

    private void applyCommand(Action.Command action, Player player) {
        int targetType = action.cmdType & 3;
        boolean queued = (action.cmdType & 4) != 0;
        if ((action.cmdType & ~7) != 0 || targetType > 2) {
            Log.warn("Skipping replay unit command with invalid command type @", action.cmdType);
            return;
        }

        Building building = null;
        Unit unit = null;
        if (targetType == 1) {
            unit = Groups.unit.getByID(action.targetId);
            if (unit == null) {
                Log.warn("Skipping replay unit command with missing target unit @", action.targetId);
                return;
            }
        } else if (targetType == 2) {
            building = Vars.world.build(action.targetId);
            if (building == null) {
                Log.warn("Skipping replay unit command with missing target building @", action.targetId);
                return;
            }
        }

        Call.commandUnits(player, action.unitIds, building, unit,
            new Vec2(action.posX, action.posY), queued, true);
    }

    private void applyPayload(Action.PayloadAction action, Player player) {
        if (action.payloadActionType == 2) {
            Call.requestDropPayload(player, action.dropX, action.dropY);
        } else if (action.payloadActionType == 1 && action.targetType == 1) {
            Unit target = Groups.unit.getByID(action.targetId);
            if (target != null) Call.requestUnitPayload(player, target);
        } else if (action.payloadActionType == 1 && action.targetType == 2) {
            Building target = Vars.world.build(action.targetId);
            if (target != null) Call.requestBuildPayload(player, target);
        } else {
            Log.warn("Skipping invalid replay payload action");
        }
    }

    private Object readConfig(byte type, int integer, short x, short y, short itemId, byte[] data) {
        return switch (type) {
            case 0 -> null;
            case 1 -> integer;
            case 2 -> new arc.math.geom.Point2(x, y);
            case 3 -> Vars.content.item(itemId);
            case 4 -> readSerializedConfig(data);
            case 5 -> readJsonConfig(data);
            default -> throw new IllegalArgumentException("Unknown replay config type: " + type);
        };
    }

    private Jval readJsonConfig(byte[] data) {
        if (data == null) throw new IllegalArgumentException("Missing JSON replay config");
        return Jval.read(new String(data, StandardCharsets.UTF_8));
    }

    private Object readSerializedConfig(byte[] data) {
        if (data == null) throw new IllegalArgumentException("Missing serialized replay config");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            Object value = TypeIO.readObject(new Reads(input));
            if (input.available() != 0) {
                throw new IOException("Unexpected trailing data in serialized replay config");
            }
            return value;
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not decode replay config", e);
        }
    }

    private void stopPlayback() throws IOException {
        RepReader oldReader = reader;
        Viewer oldInput = playbackInput;
        boolean wasPaused = playbackPaused;
        reader = null;
        playbackInput = null;
        nextRecord = null;
        lastTick = null;
        playbackPaused = false;
        seekTarget = -1L;
        try {
            if (Vars.player != null) {
                Vars.player.shooting = false;
                Vars.player.boosting = false;
                if (Vars.player.unit() != null) {
                    Vars.player.unit().controlWeapons(false, false);
                }
            }
            if (wasPaused && Vars.state != null && Vars.state.isPaused()) {
                Vars.state.set(GameState.State.playing);
            }
            if (Vars.control != null && Vars.control.input == oldInput && previousInput != null) {
                Vars.control.setInput(previousInput);
            }
            if (previousSkipWaveBinding != null) {
                Binding.skipWave.value = previousSkipWaveBinding;
                previousSkipWaveBinding = null;
            }
            restoreDefaultTimeScale();
        } finally {
            previousInput = null;
            playbackPath = null;
            if (oldReader != null) oldReader.close();
        }
    }

    /** Reloads the embedded save and resets playback before simulating forward. */
    private void restartPlayback() throws IOException {
        if (playbackPath == null) throw new IOException("Replay path is unavailable");
        RepReader restarted = new RepReader(playbackPath);
        try {
            restarted.scanTimeline();
            RepReader oldReader = reader;
            reader = restarted;
            playbackOrigin = restarted.firstTick();
            durationTicks = Math.max(0L, restarted.endTick() - playbackOrigin);
            nextRecord = restarted.readNext();
            lastTick = null;
            currentTick = 0L;
            Vars.state.tick = 0d;
            Vars.state.set(GameState.State.playing);
            if (Vars.control.input != playbackInput) Vars.control.setInput(playbackInput);
            oldReader.close();
            updatePlayback();
        } catch (IOException | RuntimeException e) {
            try {
                restarted.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    /** Applies the standard client delta with the selected replay multiplier. */
    private void installPlaybackTimeScale() {
        Time.setDeltaProvider(() -> {
            float delta = Core.graphics.getDeltaTime() * 60f;
            if (Float.isNaN(delta) || Float.isInfinite(delta)) delta = 1f;
            return Mathf.clamp(delta, 0.0001f, Vars.maxDeltaClient) * playbackSpeed;
        });
    }

    /** Restores Mindustry's normal client delta provider after playback. */
    private void restoreDefaultTimeScale() {
        Time.setDeltaProvider(() -> {
            float delta = Core.graphics.getDeltaTime() * 60f;
            if (Float.isNaN(delta) || Float.isInfinite(delta)) delta = 1f;
            return Mathf.clamp(delta, 0.0001f, Vars.maxDeltaClient);
        });
    }

    private void ensureIdle() {
        if (recordingInput != null || reader != null) {
            throw new IllegalStateException("A replay is already active");
        }
    }

}
