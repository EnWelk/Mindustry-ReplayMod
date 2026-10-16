package com.enwelk.replaymod;

import java.lang.reflect.Field;

import com.enwelk.replaymod.rep.RepReader;
import arc.Core;
import arc.Graphics;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.MechUnit;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        ReplayRecordInput recording = mock(ReplayRecordInput.class);
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

    /** Checks held shooting and boost are recorded for each new game tick. */
    @Test
    void continuousInputsAreRecordedOnEveryNewTick() {
        assertTrue(ReplayRecordInput.shouldRecordContinuousInput(12L, 11L, true, false));
        assertTrue(ReplayRecordInput.shouldRecordContinuousInput(12L, 11L, false, true));
        assertFalse(ReplayRecordInput.shouldRecordContinuousInput(12L, 11L, false, false));
        assertFalse(ReplayRecordInput.shouldRecordContinuousInput(12L, 12L, true, false));
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
            1L, 0f, 0f, 10f, 20f, true, false, (short)-1, (short)-1,
            Float.NaN, Float.NaN, Float.NaN
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
            2L, 0f, 0f, 10f, 20f, false, false, (short)-1, (short)-1,
            Float.NaN, Float.NaN, Float.NaN
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
            1L, 0f, 0f, 10f, 20f, true, false, (short)-1, (short)-1,
            Float.NaN, Float.NaN, Float.NaN
        ));

        try {
            controller.maintainPlaybackWeapons();
        } finally {
            Vars.player = previousPlayer;
        }

        assertTrue(player.shooting);
        verify(unit).controlWeapons(true, false);
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
