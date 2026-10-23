package com.enwelk.replaymod;

import java.lang.reflect.Field;

import com.enwelk.replaymod.input.RecordDesktopInput;
import com.enwelk.replaymod.rep.RepReader;
import com.enwelk.replaymod.rep.RepWriter;
import arc.Core;
import arc.Graphics;
import arc.input.KeyBind.Axis;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.util.Time;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.core.World;
import mindustry.gen.MechUnit;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.input.Binding;
import mindustry.input.DesktopInput;
import mindustry.input.InputHandler;
import mindustry.type.UnitType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayControllerTests {

    /** Checks default state and observable values for an idle controller. */
    @Test
    void newControllerStartsIdle() {
        ReplayController controller = new ReplayController();

        assertFalse(controller.isActive());
        assertFalse(controller.isRecording());
        assertFalse(controller.isPlaying());
        assertFalse(controller.isSeeking());
        assertFalse(controller.isPlaybackPaused());
        assertEquals(0L, controller.currentTick());
        assertEquals(0L, controller.durationTicks());
        assertEquals(1, controller.playbackSpeed());
    }

    /** Checks that controller state reflects active recording and playback. */
    @Test
    void activityReflectsRecordingAndPlaybackState() throws Exception {
        ReplayController controller = new ReplayController();
        InputHandler recording = mock(InputHandler.class);
        setField(controller, "recordingInput", recording);

        assertTrue(controller.isActive());
        assertTrue(controller.isRecording());
        assertFalse(controller.isPlaying());

        setField(controller, "recordingInput", null);
        setField(controller, "reader", mock(RepReader.class));

        assertTrue(controller.isActive());
        assertFalse(controller.isRecording());
        assertTrue(controller.isPlaying());
    }

    /** Checks lower and upper seek clamping and target updates. */
    @Test
    void seekClampsTargetsAndRecordsProgress() throws Exception {
        ReplayController controller = activeController(0L, 200_000L);

        controller.seek(-10L);
        assertFalse(controller.isSeeking());

        controller.seek(200_001L);
        assertTrue(controller.isSeeking());
        assertEquals(200_000L, getLongField(controller, "seekTarget"));

        setField(controller, "seekTarget", -1L);
        controller.seek(125L);
        assertEquals(125L, getLongField(controller, "seekTarget"));
    }

    /** Checks that seeking without playback is a no-op. */
    @Test
    void seekDoesNothingWithoutPlayback() {
        ReplayController controller = new ReplayController();

        controller.seek(120L);

        assertFalse(controller.isSeeking());
    }

    /** Checks pause-state changes during playback. */
    @Test
    void pauseStateCanBeToggledDuringPlayback() throws Exception {
        ReplayController controller = activeController(0L, 500L);

        controller.setPlaybackPaused(true);
        assertTrue(controller.isPlaybackPaused());

        controller.setPlaybackPaused(false);
        assertFalse(controller.isPlaybackPaused());
    }

    /** Checks that pause requests are ignored without playback. */
    @Test
    void pauseRequestDoesNothingWithoutPlayback() {
        ReplayController controller = new ReplayController();

        controller.setPlaybackPaused(true);

        assertFalse(controller.isPlaybackPaused());
    }

    /** Checks every supported playback speed. */
    @Test
    void playbackSpeedAcceptsSupportedMultipliers() {
        ReplayController controller = new ReplayController();

        for (int speed : new int[]{1, 2, 3, 5, 10}) {
            controller.setPlaybackSpeed(speed);
            assertEquals(speed, controller.playbackSpeed());
        }
    }

    /** Checks unsupported speeds fail without changing the selected speed. */
    @Test
    void playbackSpeedRejectsUnsupportedValuesWithoutChangingSpeed() {
        ReplayController controller = new ReplayController();

        for (int speed : new int[]{0, -1, 4, 6, 20}) {
            assertThrows(IllegalArgumentException.class, () -> controller.setPlaybackSpeed(speed));
            assertEquals(1, controller.playbackSpeed());
        }
    }

    @Test
    void acceleratedPlaybackAdvancesTheReplayClockWithoutExtraLogicUpdates() throws Exception {
        ReplayController controller = activeController(0L, 500L);
        GameState previousState = Vars.state;
        Player previousPlayer = Vars.player;
        Graphics previousGraphics = Core.graphics;
        GameState state = mock(GameState.class);
        when(state.isPlaying()).thenReturn(true);
        when(state.isPaused()).thenReturn(false);
        Vars.state = state;
        Vars.player = mock(Player.class);
        Graphics graphics = mock(Graphics.class);
        when(graphics.getDeltaTime()).thenReturn(1f / 60f);
        Core.graphics = graphics;
        setField(controller, "playbackInput", mock(Viewer.class));
        setField(controller, "nextRecord", new RepReader.EndRecord(500L));
        controller.setPlaybackSpeed(3);

        try {
            controller.syncPlaybackClock();
        } finally {
            Vars.state = previousState;
            Vars.player = previousPlayer;
            Core.graphics = previousGraphics;
            Time.setDeltaProvider(() -> 1f);
        }

        assertEquals(2f, state.tick, 0.0001f);
        assertEquals(2L, controller.currentTick());
    }

    /** Checks playback reapplies held fire to the local unit's weapon mounts. */
    @Test
    void playbackMaintainsHeldShooting() throws Exception {
        ReplayController controller = activeController(0L, 100L);
        Player previousPlayer = Vars.player;
        Player player = mock(Player.class);
        Unit unit = mock(Unit.class);
        when(player.unit()).thenReturn(unit);
        Vars.player = player;
        setField(controller, "lastTick", new RepReader.TickRecord(
            1L, 0f, 0f, 10f, 20f, true, false, (short)-1, (short)-1
        ));

        try {
            controller.maintainPlaybackWeapons();
        } finally {
            Vars.player = previousPlayer;
        }

        assertTrue(player.shooting);
        verify(unit).controlWeapons(true, true);
    }

    /** Checks playback releases weapons when the recorded fire state ends. */
    @Test
    void playbackReleasesWeaponsWhenShootingEnds() throws Exception {
        ReplayController controller = activeController(0L, 100L);
        Player previousPlayer = Vars.player;
        Player player = mock(Player.class);
        Unit unit = mock(Unit.class);
        when(player.unit()).thenReturn(unit);
        Vars.player = player;
        setField(controller, "lastTick", new RepReader.TickRecord(
            2L, 0f, 0f, 10f, 20f, false, false, (short)-1, (short)-1
        ));

        try {
            controller.maintainPlaybackWeapons();
        } finally {
            Vars.player = previousPlayer;
        }

        assertFalse(player.shooting);
        verify(unit).controlWeapons(true, false);
    }

    /** Checks playback preserves Mindustry's no-shooting rule for flying mechs. */
    @Test
    void playbackDisablesShootingForFlyingMechs() throws Exception {
        ReplayController controller = activeController(0L, 100L);
        Player previousPlayer = Vars.player;
        Player player = mock(Player.class);
        MechUnit unit = mock(MechUnit.class);
        when(player.unit()).thenReturn(unit);
        when(unit.isFlying()).thenReturn(true);
        Vars.player = player;
        setField(controller, "lastTick", new RepReader.TickRecord(
            1L, 0f, 0f, 10f, 20f, true, false, (short)-1, (short)-1
        ));

        try {
            controller.maintainPlaybackWeapons();
        } finally {
            Vars.player = previousPlayer;
        }

        assertTrue(player.shooting);
        verify(unit).controlWeapons(true, false);
    }

    /** Checks playback restores the unit's real speed from the recorded direction. */
    @Test
    void playbackMovementScalesRecordedDirectionByUnitSpeed() throws Exception {
        Vec2 movement = capturePlaybackMovement(1f, 0.5f, 3.5f);

        float expectedLength = (float)Math.sqrt(1.25);
        assertEquals(3.5f, movement.len(), 0.0001f);
        assertEquals(3.5f / expectedLength, movement.x, 0.0001f);
        assertEquals(1.75f / expectedLength, movement.y, 0.0001f);
    }

    /** Checks a recorded full-speed diagonal keeps the unit's speed, not twice it. */
    @Test
    void playbackMovementKeepsRecordedDiagonalAtUnitSpeed() throws Exception {
        Vec2 movement = capturePlaybackMovement(1f, 1f, 2f);

        assertEquals(2f, movement.len(), 0.0001f);
    }

    @Test
    void playbackMovementPreservesAnalogMagnitude() throws Exception {
        Vec2 movement = capturePlaybackMovement(0.5f, 0f, 4f);

        assertEquals(2f, movement.x, 0.0001f);
        assertEquals(0f, movement.y, 0.0001f);
    }

    /** Checks playback keeps an idle unit still without producing NaN movement. */
    @Test
    void playbackMovementKeepsIdleUnitStill() throws Exception {
        Vec2 movement = capturePlaybackMovement(0f, 0f, 3.5f);

        assertFalse(movement.isNaN());
        assertEquals(0f, movement.x, 0f);
        assertEquals(0f, movement.y, 0f);
    }

    /** Runs maintainPlaybackMovement for one recorded direction and returns the applied velocity target. */
    private static Vec2 capturePlaybackMovement(float recordedX, float recordedY, float unitSpeed) throws Exception {
        ReplayController controller = activeController(0L, 100L);
        GameState previousState = Vars.state;
        Player previousPlayer = Vars.player;
        World previousWorld = Vars.world;
        try {
            GameState state = mock(GameState.class);
            when(state.isGame()).thenReturn(true);
            Vars.state = state;
            Vars.world = mock(World.class);
            Player player = mock(Player.class);
            Unit unit = mock(Unit.class);
            unit.type = mock(UnitType.class);
            unit.vel = new Vec2();
            when(player.unit()).thenReturn(unit);
            when(unit.speed()).thenReturn(unitSpeed);
            Vars.player = player;

            Viewer viewer = mock(Viewer.class);
            DesktopInput.class.getField("movement").set(viewer, new Vec2());
            setField(controller, "playbackInput", viewer);
            setField(controller, "lastTick", new RepReader.TickRecord(
                1L, recordedX, recordedY, 10f, 20f, false, false, (short)-1, (short)-1
            ));

            controller.maintainPlaybackMovement();

            ArgumentCaptor<Vec2> movement = ArgumentCaptor.forClass(Vec2.class);
            verify(unit).movePref(movement.capture());
            return movement.getValue().cpy();
        } finally {
            Vars.state = previousState;
            Vars.player = previousPlayer;
            Vars.world = previousWorld;
        }
    }

    /** Checks listener replacement and safe clearing. */
    @Test
    void listenersCanBeSetAndCleared() throws Exception {
        ReplayController controller = new ReplayController();
        Runnable finished = () -> {};
        Runnable stopped = () -> {};

        controller.setPlaybackFinishedListener(finished);
        controller.setPlaybackStoppedListener(stopped);

        assertEquals(finished, getField(controller, "playbackFinished"));
        assertEquals(stopped, getField(controller, "playbackStopped"));

        controller.setPlaybackFinishedListener(null);
        controller.setPlaybackStoppedListener(null);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> ((Runnable)getField(controller, "playbackFinished")).run());
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> ((Runnable)getField(controller, "playbackStopped")).run());
    }

    /** Checks update and seek paths safely wait for initialized game state. */
    @Test
    void noPlaybackUpdateOrSeekIsSafeWithoutGameState() throws Exception {
        ReplayController controller = activeController(0L, 100L);
        setField(controller, "seekTarget", 20L);

        controller.advanceSeek();
        controller.syncPlaybackClock();
        controller.updatePlayback();

        assertTrue(controller.isSeeking());
        assertEquals(0L, controller.currentTick());
    }

    /** Checks stopping an idle replay does not notify listeners. */
    @Test
    void manualStopWithoutPlaybackDoesNotInvokeCallback() {
        ReplayController controller = new ReplayController();
        boolean[] stopped = {false};
        controller.setPlaybackStoppedListener(() -> stopped[0] = true);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(controller::stopPlaybackManually);

        assertFalse(stopped[0]);
    }

    /** Checks that a new game stops playback without showing the manual-stop callback. */
    @Test
    void startingNewGameStopsActivePlayback() throws Exception {
        ReplayController controller = activeController(15L, 100L);
        Graphics previousGraphics = Core.graphics;
        Graphics graphics = mock(Graphics.class);
        when(graphics.getDeltaTime()).thenReturn(1f / 60f);
        Core.graphics = graphics;
        boolean[] stopped = {false};
        controller.setPlaybackStoppedListener(() -> stopped[0] = true);

        try {
            controller.stopPlaybackForNewGame();
        } finally {
            Core.graphics = previousGraphics;
            Time.setDeltaProvider(() -> 1f);
        }

        assertFalse(controller.isPlaying());
        assertFalse(controller.isActive());
        assertFalse(stopped[0]);
    }

    /** Checks that a world reset ends playback without the manual-stop callback. */
    @Test
    void worldResetStopsActivePlayback() throws Exception {
        ReplayController controller = activeController(15L, 100L);
        Graphics previousGraphics = Core.graphics;
        Graphics graphics = mock(Graphics.class);
        when(graphics.getDeltaTime()).thenReturn(1f / 60f);
        Core.graphics = graphics;
        boolean[] stopped = {false};
        boolean[] finished = {false};
        controller.setPlaybackStoppedListener(() -> stopped[0] = true);
        controller.setPlaybackFinishedListener(() -> finished[0] = true);

        try {
            controller.stopPlaybackForWorldReset();
        } finally {
            Core.graphics = previousGraphics;
            Time.setDeltaProvider(() -> 1f);
        }

        assertFalse(controller.isPlaying());
        assertFalse(controller.isActive());
        assertFalse(controller.isSeeking());
        assertFalse(stopped[0]);
        assertFalse(finished[0]);
    }

    /** Checks that a world reset while idle is harmless. */
    @Test
    void worldResetDoesNothingWhenPlaybackIsIdle() {
        ReplayController controller = new ReplayController();

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(controller::stopPlaybackForWorldReset);

        assertFalse(controller.isPlaying());
    }

    /** Checks that playback survives the world resets it triggers itself. */
    @Test
    void worldResetDuringSelfLoadKeepsPlayback() throws Exception {
        ReplayController controller = activeController(15L, 100L);
        setField(controller, "loadingReplayWorld", true);

        try {
            controller.stopPlaybackForWorldReset();
        } finally {
            setField(controller, "loadingReplayWorld", false);
        }

        assertTrue(controller.isPlaying());
    }

    /** Checks that a failed stop during a world reset does not propagate into the game. */
    @Test
    void worldResetSwallowsStopFailures() throws Exception {
        ReplayController controller = activeController(15L, 100L);
        RepReader reader = mock(RepReader.class);
        org.mockito.Mockito.doThrow(new java.io.IOException("close failed")).when(reader).close();
        setField(controller, "reader", reader);
        Graphics previousGraphics = Core.graphics;
        Graphics graphics = mock(Graphics.class);
        when(graphics.getDeltaTime()).thenReturn(1f / 60f);
        Core.graphics = graphics;

        try {
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(controller::stopPlaybackForWorldReset);
        } finally {
            Core.graphics = previousGraphics;
            Time.setDeltaProvider(() -> 1f);
        }

        assertFalse(controller.isPlaying());
    }

    @Test
    void playbackFailureShowsErrorCallbackAndRestoresSkipWaveBinding() throws Exception {
        ReplayController controller = activeController(0L, 100L);
        RepReader reader = mock(RepReader.class);
        when(reader.readNext()).thenThrow(new java.io.IOException("broken record"));
        setField(controller, "reader", reader);
        setField(controller, "nextRecord", new RepReader.TickRecord(
            0L, 0f, 0f, 0f, 0f, false, false, (short)-1, (short)-1
        ));
        Axis previousBinding = Binding.skipWave.value;
        Axis restoredBinding = new Axis(KeyCode.f);
        setField(controller, "previousSkipWaveBinding", restoredBinding);
        GameState previousState = Vars.state;
        Player previousPlayer = Vars.player;
        Graphics previousGraphics = Core.graphics;
        Graphics graphics = mock(Graphics.class);
        when(graphics.getDeltaTime()).thenReturn(1f / 60f);
        Core.graphics = graphics;
        GameState state = mock(GameState.class);
        Vars.state = state;
        Vars.player = mock(Player.class);
        String[] error = {null};
        Axis[] bindingAfterStop = {null};
        controller.setPlaybackErrorListener(message -> error[0] = message);

        try {
            controller.updatePlayback();
            bindingAfterStop[0] = Binding.skipWave.value;
        } finally {
            Vars.state = previousState;
            Vars.player = previousPlayer;
            Core.graphics = previousGraphics;
            Binding.skipWave.value = previousBinding;
            Time.setDeltaProvider(() -> 1f);
        }

        assertEquals("broken record", error[0]);
        assertFalse(controller.isPlaying());
        assertEquals(restoredBinding, bindingAfterStop[0]);
        assertNull(getField(controller, "previousSkipWaveBinding"));
    }

    /** Checks that a game-start event is harmless when playback is idle. */
    @Test
    void startingNewGameDoesNothingWhenPlaybackIsIdle() {
        ReplayController controller = new ReplayController();

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(controller::stopPlaybackForNewGame);

        assertFalse(controller.isPlaying());
    }

    /** Checks that stopping an idle controller reports invalid state. */
    @Test
    void stopRequiresAnActiveRecordingOrPlayback() {
        ReplayController controller = new ReplayController();

        assertThrows(IllegalStateException.class, controller::stop);
    }

    /** Checks that starting a second operation is rejected. */
    @Test
    void startingEitherOperationRejectsAnExistingSession() throws Exception {
        ReplayController controller = activeController(0L, 100L);

        assertThrows(IllegalStateException.class,
            () -> controller.startRecording(java.nio.file.Path.of("unused.rep")));
        assertThrows(IllegalStateException.class,
            () -> controller.startPlayback(java.nio.file.Path.of("unused.rep")));
    }

    /** Checks that a seek remembers its initial pause state. */
    @Test
    void seekRemembersPauseStateAndTemporarilyResumesPlayback() throws Exception {
        ReplayController controller = activeController(10L, 100L);
        setField(controller, "playbackPaused", true);

        controller.seek(40L);

        assertTrue(controller.isSeeking());
        assertFalse(controller.isPlaybackPaused());
        assertTrue((boolean)getField(controller, "pauseAfterSeek"));

        controller.seek(50L);
        assertEquals(50L, getLongField(controller, "seekTarget"));
        assertTrue((boolean)getField(controller, "pauseAfterSeek"));
    }

    @Test
    void maintainPlaybackMovementAimsWithAvailableUnitApi() throws Exception {
        Unit unit = runMovementHookWithSaneState(1f, 0f, 3f, mock(UnitType.class));

        if (hasExtendedAim()) {
            Unit verified = verify(unit);
            extendedAimMethod().invoke(verified, 10f, 20f, true);
        } else {
            verify(unit).aim(10f, 20f);
        }
    }

    @Test
    void movementHookFailureStopsPlaybackInsteadOfCrashing() throws Exception {
        ReplayController controller = activeController(0L, 100L);
        String[] error = {null};
        controller.setPlaybackErrorListener(message -> error[0] = message);

        runMovementHookWithSaneState(1f, 0f, 3f, null, controller, true);

        assertFalse(controller.isPlaying());
        org.junit.jupiter.api.Assertions.assertNotNull(error[0]);
    }

    private static Unit runMovementHookWithSaneState(float recordedX, float recordedY, float unitSpeed,
                                                     UnitType type) throws Exception {
        return runMovementHookWithSaneState(recordedX, recordedY, unitSpeed, type,
            activeController(0L, 100L), false);
    }

    private static Unit runMovementHookWithSaneState(float recordedX, float recordedY, float unitSpeed,
                                                     UnitType type, ReplayController controller,
                                                     boolean resetTimeScale) throws Exception {
        GameState previousState = Vars.state;
        Player previousPlayer = Vars.player;
        World previousWorld = Vars.world;
        try {
            GameState state = mock(GameState.class);
            when(state.isGame()).thenReturn(true);
            Vars.state = state;
            Vars.world = mock(World.class);
            Player player = mock(Player.class);
            Unit unit = mock(Unit.class);
            unit.type = type;
            unit.vel = new Vec2();
            when(player.unit()).thenReturn(unit);
            when(unit.speed()).thenReturn(unitSpeed);
            Vars.player = player;

            Viewer viewer = mock(Viewer.class);
            DesktopInput.class.getField("movement").set(viewer, new Vec2());
            setField(controller, "playbackInput", viewer);
            setField(controller, "lastTick", new RepReader.TickRecord(
                1L, recordedX, recordedY, 10f, 20f, false, false, (short)-1, (short)-1
            ));

            controller.maintainPlaybackMovement();
            return unit;
        } finally {
            Vars.state = previousState;
            Vars.player = previousPlayer;
            Vars.world = previousWorld;
            if (resetTimeScale) {
                Time.setDeltaProvider(() -> 1f);
            }
        }
    }

    private static boolean hasExtendedAim() {
        try {
            extendedAimMethod();
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static java.lang.reflect.Method extendedAimMethod() throws NoSuchMethodException {
        return Unit.class.getMethod("aim", float.class, float.class, boolean.class);
    }

    @Test
    void recordRandomStateFlushesRecordedInputFirst() throws Exception {
        GameState previousState = Vars.state;
        try {
            GameState state = mock(GameState.class);
            state.tick = 100.5;
            Vars.state = state;
            ReplayController controller = new ReplayController();
            RecordDesktopInput input = mock(RecordDesktopInput.class);
            RepWriter writer = mock(RepWriter.class);
            setField(controller, "recordingInput", input);
            setField(controller, "writer", writer);

            controller.recordRandomState();

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(input, writer);
            order.verify(input).flushCompletedTicks(100.5);
            order.verify(writer).writeRandomState(100L);
        } finally {
            Vars.state = previousState;
        }
    }

    @Test
    void playbackBrakesOvershootingVelocityTowardRecordedTarget() throws Exception {
        Unit unit = runMovementHookWithVelocity(new Vec2(1f, 0f), 2f, new Vec2(4f, 0f), 0.4f);

        assertEquals(3.6f, unit.vel.x, 0.001f);
        assertEquals(0f, unit.vel.y, 0.001f);
    }

    @Test
    void playbackPullsVelocityUpToRecordedTarget() throws Exception {
        Unit unit = runMovementHookWithVelocity(new Vec2(1f, 0f), 2f, new Vec2(1f, 0f), 0.4f);

        assertEquals(1.4f, unit.vel.x, 0.001f);
        assertEquals(0f, unit.vel.y, 0.001f);
    }

    @Test
    void legacyReplaysCoastWithoutVelocityTracking() throws Exception {
        Unit unit = runMovementHookWithVelocity(new Vec2(1f, 0f), 2f, new Vec2(4f, 0f), 0.4f, false);

        assertEquals(4f, unit.vel.x, 0.001f);
        assertEquals(0f, unit.vel.y, 0.001f);
    }

    @Test
    void playbackBrakesVelocityTowardRecordedStop() throws Exception {
        Unit unit = runMovementHookWithVelocity(new Vec2(0f, 0f), 2f, new Vec2(1.5f, 0f), 0.4f);

        assertEquals(1.1f, unit.vel.x, 0.001f);
        assertEquals(0f, unit.vel.y, 0.001f);
    }

    private static Unit runMovementHookWithVelocity(Vec2 recorded, float speed,
                                                    Vec2 initialVel, float accel) throws Exception {
        return runMovementHookWithVelocity(recorded, speed, initialVel, accel, true);
    }

    private static Unit runMovementHookWithVelocity(Vec2 recorded, float speed,
                                                    Vec2 initialVel, float accel,
                                                    boolean velocitySemantics) throws Exception {
        ReplayController controller = activeController(0L, 100L);
        setField(controller, "playbackVelocitySemantics", velocitySemantics);
        GameState previousState = Vars.state;
        Player previousPlayer = Vars.player;
        World previousWorld = Vars.world;
        try {
            GameState state = mock(GameState.class);
            when(state.isGame()).thenReturn(true);
            Vars.state = state;
            Vars.world = mock(World.class);
            Player player = mock(Player.class);
            Unit unit = mock(Unit.class);
            UnitType type = mock(UnitType.class);
            type.accel = accel;
            unit.type = type;
            unit.vel = initialVel;
            when(player.unit()).thenReturn(unit);
            when(unit.speed()).thenReturn(speed);
            Vars.player = player;

            Viewer viewer = mock(Viewer.class);
            DesktopInput.class.getField("movement").set(viewer, new Vec2());
            setField(controller, "playbackInput", viewer);
            setField(controller, "lastTick", new RepReader.TickRecord(
                1L, recorded.x, recorded.y, 10f, 20f, false, false, (short)-1, (short)-1
            ));

            controller.maintainPlaybackMovement();
            return unit;
        } finally {
            Vars.state = previousState;
            Vars.player = previousPlayer;
            Vars.world = previousWorld;
        }
    }

    /** Creates a playback controller fixture with a mocked reader. */
    private static ReplayController activeController(long currentTick, long duration) throws Exception {
        ReplayController controller = new ReplayController();
        setField(controller, "reader", mock(RepReader.class));
        setField(controller, "currentTick", currentTick);
        setField(controller, "durationTicks", duration);
        return controller;
    }

    /** Reads a private controller field for isolated state assertions. */
    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    /** Reads a private long controller field for seek assertions. */
    private static long getLongField(Object target, String name) throws Exception {
        return (long)getField(target, name);
    }

    /** Sets a private controller field for isolated state setup. */
    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
