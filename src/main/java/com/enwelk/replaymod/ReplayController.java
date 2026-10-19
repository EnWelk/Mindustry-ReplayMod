package com.enwelk.replaymod;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Consumer;

import com.enwelk.replaymod.input.RecordDesktopInput;
import com.enwelk.replaymod.input.RecordMobileInput;
import com.enwelk.replaymod.rep.RepReader;
import com.enwelk.replaymod.rep.RepWriter;

import arc.Core;
import arc.input.KeyBind.Axis;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.util.Log;
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
import mindustry.input.Binding;
import mindustry.input.DesktopInput;
import mindustry.input.InputHandler;
import mindustry.input.MobileInput;
import mindustry.input.PlaceMode;
import mindustry.io.TypeIO;
import mindustry.world.Block;
import mindustry.world.Tile;

public class ReplayController {
    private static final long MAX_SEEK_STEP = 300L;

    private static Method extendedAim = findExtendedAim();

    private static Method findExtendedAim() {
        try {
            return Unit.class.getMethod("aim", float.class, float.class, boolean.class);
        } catch (NoSuchMethodException | RuntimeException e) {
            Log.info("ReplayMod: aim(x, y, clearTarget) is unavailable on this game build; using aim(x, y)");
            return null;
        }
    }

    private static void aimPlaybackUnit(Unit unit, float x, float y) {
        Method aim = extendedAim;
        if (aim != null) {
            try {
                aim.invoke(unit, x, y, true);
                return;
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException | LinkageError e) {
                Log.err("ReplayMod: extended unit aim failed; falling back to aim(x, y)", e);
                extendedAim = null;
            }
        }
        unit.aim(x, y);
    }

    private InputHandler previousInput;
    private RepWriter writer;
    private InputHandler recordingInput;
    private Runnable recordingCloser;
    private RepReader reader;
    private Viewer playbackInput;
    private Path playbackPath;
    private RepReader.ReplayRecord nextRecord;
    private RepReader.TickRecord lastTick;
    private RepReader.RandomRecord lastRandomState;
    private long currentTick;
    private long playbackOrigin;
    private long durationTicks;
    private long seekTarget = -1L;
    private boolean playbackPaused;
    private boolean pauseAfterSeek;
    private boolean playbackVelocitySemantics;
    private int playbackSpeed = 1;
    private Axis previousSkipWaveBinding;
    private Runnable playbackFinished = () -> {};
    private Runnable playbackStopped = () -> {};
    private Consumer<String> playbackError = ignored -> {};

    /** Begins recording the current offline game to a replay file. */
    public void startRecording(Path path) throws IOException {
        ensureIdle();
        if (Vars.net.active()) {
            throw new IllegalStateException("Full multiplayer replay recording is not supported yet");
        }
        if (Vars.control == null || Vars.player == null || Vars.state == null || !Vars.state.isGame()) {
            throw new IllegalStateException("Recording can only start during a game");
        }
        InputHandler currentInput = Vars.control.input;
        if (currentInput == null) {
            throw new IllegalStateException("No active input handler");
        }
        RepWriter newWriter = new RepWriter(path);
        try {
            previousInput = currentInput;
            writer = newWriter;
            if (currentInput instanceof DesktopInput) {
                RecordDesktopInput input = new RecordDesktopInput(newWriter);
                recordingInput = input;
                recordingCloser = input::close;
            } else if (currentInput instanceof MobileInput) {
                RecordMobileInput input = new RecordMobileInput(newWriter);
                recordingInput = input;
                recordingCloser = input::close;
            } else {
                throw new IllegalStateException("Replay recording is not supported for this input method");
            }
            Vars.control.setInput(recordingInput);
        } catch (RuntimeException | LinkageError e) {
            if (recordingCloser != null) {
                try {
                    recordingCloser.run();
                } catch (RuntimeException | LinkageError closeError) {
                    e.addSuppressed(closeError);
                }
            }
            recordingInput = null;
            recordingCloser = null;
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

    /** Returns whether a replay is being recorded or played. */
    public boolean isActive() {
        return recordingInput != null || reader != null;
    }

    /** Returns whether replay recording is active. */
    public boolean isRecording() {
        return recordingInput != null;
    }

    /** Returns whether replay playback is active. */
    public boolean isPlaying() {
        return reader != null;
    }

    /** Restores the recorded RNG state immediately before a playback simulation update. */
    void applyPlaybackRandomState() {
        if (reader == null || lastRandomState == null) return;
        Mathf.rand.setState(lastRandomState.seed0(), lastRandomState.seed1());
    }

    /** Records the global RNG state immediately before each recording simulation update. */
    void recordRandomState() {
        if (recordingInput == null || writer == null || Vars.state == null) return;
        try {
            if (recordingInput instanceof RecordDesktopInput desktop) {
                desktop.flushCompletedTicks(Vars.state.tick);
            } else if (recordingInput instanceof RecordMobileInput mobile) {
                mobile.flushCompletedTicks(Vars.state.tick);
            }
            writer.writeRandomState((long)Math.floor(Vars.state.tick));
        } catch (IOException e) {
            Log.err("Failed to write replay random state", e);
        }
    }

    /** Returns the current replay-relative tick. */
    public long currentTick() {
        return currentTick;
    }

    /** Returns the total replay duration in ticks. */
    public long durationTicks() {
        return durationTicks;
    }

    /** Returns the active playback speed multiplier. */
    public int playbackSpeed() {
        return playbackSpeed;
    }

    /** Sets the callback invoked when playback reaches its end. */
    public void setPlaybackFinishedListener(Runnable listener) {
        playbackFinished = listener == null ? () -> {} : listener;
    }

    /** Sets the callback invoked when playback is manually stopped. */
    public void setPlaybackStoppedListener(Runnable listener) {
        playbackStopped = listener == null ? () -> {} : listener;
    }

    /** Sets the callback invoked when playback terminates because of a replay error. */
    public void setPlaybackErrorListener(Consumer<String> listener) {
        playbackError = listener == null ? ignored -> {} : listener;
    }

    /** Stops playback manually and notifies the stopped listener. */
    public void stopPlaybackManually() throws IOException {
        if (reader == null) return;
        stopPlayback();
        playbackStopped.run();
    }

    /** Stops replay playback before a newly started game can inherit its input handler. */
    void stopPlaybackForNewGame() throws IOException {
        if (reader != null) {
            stopPlayback();
        }
    }

    /** Sets the simulation multiplier used while playback is running. */
    public void setPlaybackSpeed(int speed) {
        if (speed != 1 && speed != 2 && speed != 3 && speed != 5 && speed != 10) {
            throw new IllegalArgumentException("Unsupported replay speed: " + speed);
        }
        playbackSpeed = speed;
    }

    /** Requests a seek; backward seeks restore the embedded map before simulating forward. */
    public void seek(long targetTick) {
        if (reader == null) return;
        long target = Math.max(0L, Math.min(targetTick, durationTicks));
        if (seekTarget < 0L) {
            pauseAfterSeek = isPlaybackPaused();
        }
        if (target == currentTick && seekTarget < 0L) return;
        if (target < currentTick) {
            try {
                restartPlayback();
            } catch (IOException | RuntimeException | LinkageError e) {
                failPlayback("Could not restart replay for backward seek", e);
                return;
            }
            if (reader == null) return;
        }
        seekTarget = target;
        playbackPaused = false;
        if (Vars.state != null) {
            Vars.state.set(GameState.State.playing);
        }
    }

    /** Advances a pending seek while respecting the per-frame simulation limit. */
    void advanceSeek() {
        if (reader == null || seekTarget < 0L || Vars.state == null) return;
        long target = seekTarget;
        try {
            long replayTick = playbackOrigin + currentTick;
            if (nextRecord != null && nextRecord.tick() <= replayTick) {
                updatePlayback();
            }
            if (reader != null && currentTick < target) {
                replayTick = playbackOrigin + currentTick;
                long step = Math.min(MAX_SEEK_STEP, target - currentTick);
                if (nextRecord != null) {
                    long untilRecord = nextRecord.tick() - replayTick;
                    if (untilRecord > 0L) {
                        step = Math.min(step, untilRecord);
                    }
                }
                double before = Vars.state.tick;
                long simulationStep = Math.max(1L, step);
                Time.setDeltaProvider(() -> simulationStep);
                Vars.logic.update();
                if (reader == null) return;
                Vars.state.tick = before + simulationStep;
                currentTick = (long)Math.floor(Vars.state.tick);
                updatePlayback();
            }
        } catch (RuntimeException | LinkageError e) {
            failPlayback("Replay seek failed", e);
        } finally {
            if (reader != null) {
                if (currentTick >= target) completeSeek();
                else installPlaybackTimeScale();
            }
        }
    }

    /** Completes a seek and restores its requested pause state. */
    private void completeSeek() {
        seekTarget = -1L;
        installPlaybackTimeScale();
        playbackPaused = pauseAfterSeek;
        if (playbackPaused && Vars.state != null) {
            Vars.state.set(GameState.State.paused);
        }
    }

    /** Advances replay records between ordinary simulation steps at the selected speed. */
    void syncPlaybackClock() {
        if (reader == null || playbackInput == null || isPlaybackPaused() ||
            seekTarget >= 0L || Vars.state == null || !Vars.state.isPlaying()) return;
        if (playbackSpeed > 1) {
            float frameDelta = Core.graphics.getDeltaTime() * 60f;
            if (Float.isFinite(frameDelta) && frameDelta > 0f) {
                Vars.state.tick += frameDelta * (playbackSpeed - 1);
            }
        }
        updatePlayback();
    }

    /** Returns whether playback or the game state is paused. */
    public boolean isPlaybackPaused() {
        return playbackPaused || (Vars.state != null && Vars.state.isPaused());
    }

    /** Returns whether playback is currently advancing toward a seek target. */
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

    /** Reloads the embedded save and starts the current replay from its first record. */
    public void restartFromBeginning() throws IOException {
        if (reader == null) return;
        restartPlayback();
        seekTarget = -1L;
        playbackPaused = false;
        installPlaybackTimeScale();
        if (Vars.state != null) Vars.state.set(GameState.State.playing);
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
            restoreReplayRandomState(newReader);
            playbackVelocitySemantics = newReader.velocityRecorded();
            previousInput = input;
            reader = newReader;
            playbackPath = path;
            playbackOrigin = newReader.firstTick();
            durationTicks = Math.max(0L, newReader.endTick() - playbackOrigin);
            playbackInput = new Viewer(this);
            nextRecord = reader.readNext();
            lastTick = null;
            lastRandomState = null;
            currentTick = 0L;
            playbackPaused = false;
            Vars.state.tick = 0d;
            Vars.control.setInput(playbackInput);
            previousSkipWaveBinding = Binding.skipWave.value;
            Binding.skipWave.value = new Axis(KeyCode.unset);
            installPlaybackTimeScale();
            Vars.state.set(GameState.State.playing);
            updatePlayback();
        } catch (IOException | RuntimeException | LinkageError e) {
            if (Vars.control != null && Vars.control.input == playbackInput) {
                try {
                    Vars.control.setInput(input);
                } catch (RuntimeException | LinkageError restoreError) {
                    e.addSuppressed(restoreError);
                }
            }
            reader = null;
            playbackInput = null;
            nextRecord = null;
            lastTick = null;
            lastRandomState = null;
            playbackPaused = false;
            playbackVelocitySemantics = false;
            seekTarget = -1L;
            previousInput = null;
            playbackPath = null;
            if (previousSkipWaveBinding != null) {
                Binding.skipWave.value = previousSkipWaveBinding;
                previousSkipWaveBinding = null;
            }
            restoreDefaultTimeScale();
            try {
                newReader.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    /** Stops the active recording or playback session. */
    public void stop() throws IOException {
        if (recordingInput != null) {
            InputHandler oldInput = recordingInput;
            Runnable oldCloser = recordingCloser;
            RepWriter oldWriter = writer;
            recordingInput = null;
            recordingCloser = null;
            writer = null;
            try {
                if (oldCloser != null) {
                    oldCloser.run();
                }
            } finally {
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
                        oldWriter.close();
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
        String failure = null;
        try {
            while (nextRecord != null && nextRecord.tick() <= replayTick) {
                if (nextRecord instanceof RepReader.TickRecord tickRecord) {
                    lastTick = tickRecord;
                } else if (nextRecord instanceof RepReader.ActionRecord actionRecord) {
                    for (Action action : actionRecord.actions()) {
                        apply(action);
                    }
                } else if (nextRecord instanceof RepReader.RandomRecord randomRecord) {
                    lastRandomState = randomRecord;
                } else if (nextRecord instanceof RepReader.EndRecord) {
                    finished = true;
                    nextRecord = null;
                    break;
                }
                nextRecord = reader.readNext();
            }
            if (nextRecord == null && !finished) {
                throw new IOException("Replay is missing its end record");
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            Log.err("Replay playback stopped because a replay record could not be read", e);
            finished = true;
            failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }

        if (finished) {
            try {
                stopPlayback();
            } catch (IOException | RuntimeException | LinkageError e) {
                Log.err("Failed to close replay after playback", e);
                String closeFailure = e.getMessage() == null
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
                failure = failure == null ? closeFailure : failure + "\n" + closeFailure;
            }
            if (failure == null) playbackFinished.run();
            else playbackError.accept(failure);
        }
    }

    /** Applies the most recent recorded movement, aim, and player state before a game update. */
    void maintainPlaybackMovement() {
        if (reader == null || lastTick == null || playbackInput == null ||
            Vars.state == null || !Vars.state.isGame() || Vars.world == null ||
            Vars.player == null || Vars.player.unit() == null) {
            return;
        }
        try {
            Player player = Vars.player;
            Unit unit = player.unit();
            player.shooting = lastTick.shooting();
            player.boosting = lastTick.boosting();
            playbackInput.movement
                .set(lastTick.movementX(), lastTick.movementY())
                .limit(1f)
                .scl(unit.speed());
            unit.movePref(playbackInput.movement);
            if (playbackVelocitySemantics) {
                float tracking = unit.speed() * unit.type.accel / 2f +
                    unit.speed() * Math.max(unit.drag(), 0f) * Time.delta;
                unit.vel.approachDelta(playbackInput.movement, tracking);
            }
            boolean boosted = unit instanceof Mechc && unit.isFlying();
            boolean aimAtTarget = unit.type.omniMovement && player.shooting &&
                unit.type.hasWeapons() && unit.type.faceTarget && !boosted;
            unit.lookAt(aimAtTarget
                ? unit.angleTo(lastTick.aimX(), lastTick.aimY())
                : unit.prefRotation());
            aimPlaybackUnit(unit, lastTick.aimX(), lastTick.aimY());
            if (lastTick.mineX() < 0 || lastTick.mineY() < 0) {
                unit.mineTile = null;
            } else {
                unit.mineTile = Vars.world.tile(lastTick.mineX(), lastTick.mineY());
            }
            player.mouseX = lastTick.aimX();
            player.mouseY = lastTick.aimY();
        } catch (RuntimeException | LinkageError e) {
            failPlayback("Replay movement update failed", e);
        }
    }

    /** Reapplies recorded weapon flags throughout each simulated game update. */
    void maintainPlaybackWeapons() {
        if (reader == null || lastTick == null ||
            Vars.player == null || Vars.player.unit() == null) return;
        try {
            Player player = Vars.player;
            Unit unit = player.unit();
            player.shooting = lastTick.shooting();
            player.boosting = lastTick.boosting();
            boolean boosted = unit instanceof Mechc && unit.isFlying();
            unit.controlWeapons(true, lastTick.shooting() && !boosted);
        } catch (RuntimeException | LinkageError e) {
            failPlayback("Replay weapon update failed", e);
        }
    }

    /** Applies one recorded gameplay action to the active replay world. */
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
        } else if (action instanceof Action.Teleport teleport) {
            if (player.unit() != null && Float.isFinite(teleport.x) && Float.isFinite(teleport.y)) {
                player.unit().set(teleport.x, teleport.y);
                player.unit().vel.setZero();
            }
        } else if (action instanceof Action.Command command) {
            applyCommand(command, player);
        }
    }

    /** Applies a recorded unit command to its target and selected units. */
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

    /** Applies a recorded payload pickup or drop request. */
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

    /** Decodes a config value according to its recorded type. */
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

    /** Parses a serialized config value encoded as JSON. */
    private Jval readJsonConfig(byte[] data) {
        if (data == null) throw new IllegalArgumentException("Missing JSON replay config");
        return Jval.read(new String(data, StandardCharsets.UTF_8));
    }

    /** Reads a config value encoded by Mindustry's TypeIO serializer. */
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

    /** Closes playback and restores the input handler and game time scale. */
    private void stopPlayback() throws IOException {
        RepReader oldReader = reader;
        Viewer oldInput = playbackInput;
        boolean wasPaused = isPlaybackPaused();
        reader = null;
        playbackInput = null;
        nextRecord = null;
        lastTick = null;
        lastRandomState = null;
        playbackPaused = false;
        playbackVelocitySemantics = false;
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
        } finally {
            previousInput = null;
            playbackPath = null;
            try {
                if (previousSkipWaveBinding != null) {
                    Binding.skipWave.value = previousSkipWaveBinding;
                    previousSkipWaveBinding = null;
                }
            } finally {
                try {
                    restoreDefaultTimeScale();
                } finally {
                    if (oldReader != null) oldReader.close();
                }
            }
        }
    }

    /** Reloads the embedded save and resets playback before simulating forward. */
    private void restartPlayback() throws IOException {
        if (playbackPath == null) throw new IOException("Replay path is unavailable");
        RepReader restarted = new RepReader(playbackPath);
        try {
            restarted.scanTimeline();
            restoreReplayRandomState(restarted);
            RepReader oldReader = reader;
            reader = restarted;
            playbackVelocitySemantics = restarted.velocityRecorded();
            playbackOrigin = restarted.firstTick();
            durationTicks = Math.max(0L, restarted.endTick() - playbackOrigin);
            nextRecord = restarted.readNext();
            lastTick = null;
            lastRandomState = null;
            currentTick = 0L;
            Vars.state.tick = 0d;
            Vars.state.set(GameState.State.playing);
            if (Vars.control.input != playbackInput) Vars.control.setInput(playbackInput);
            oldReader.close();
            updatePlayback();
        } catch (IOException | RuntimeException | LinkageError e) {
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

    /** Restores the recorded global random generator state after the embedded save loads. */
    private void restoreReplayRandomState(RepReader replay) {
        if (replay.hasRandomState()) {
            Mathf.rand.setState(replay.randomSeed0(), replay.randomSeed1());
        }
    }

    private void failPlayback(String context, Throwable failure) {
        Log.err(context, failure);
        String message = failure.getMessage() == null
            ? failure.getClass().getSimpleName()
            : failure.getMessage();
        if (reader != null) {
            try {
                stopPlayback();
            } catch (IOException | RuntimeException | LinkageError closeFailure) {
                Log.err("Failed to close replay after an error", closeFailure);
                String closeMessage = closeFailure.getMessage() == null
                    ? closeFailure.getClass().getSimpleName()
                    : closeFailure.getMessage();
                message += "\n" + closeMessage;
            }
        }
        playbackError.accept(message);
    }

    /** Rejects a new replay operation while another session is active. */
    private void ensureIdle() {
        if (recordingInput != null || reader != null) {
            throw new IllegalStateException("A replay is already active");
        }
    }

}


