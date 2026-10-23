package com.enwelk.replaymod;

import arc.input.KeyCode;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplayHudTests {

    /** Checks the guard stops wave-skip touches before the HUD button sees them. */
    @Test
    void waveSkipGuardStopsTouchesDuringPlayback() {
        ReplayController controller = mock(ReplayController.class);
        when(controller.isPlaying()).thenReturn(true);
        InputEvent event = new InputEvent();

        InputListener guard = ReplayHud.waveSkipGuard(controller);

        assertTrue(guard.touchDown(event, 0f, 0f, 0, KeyCode.mouseLeft));
        assertTrue(event.stopped);
    }

    /** Checks the guard leaves touches alone outside playback. */
    @Test
    void waveSkipGuardIgnoresTouchesOutsidePlayback() {
        ReplayController controller = mock(ReplayController.class);
        when(controller.isPlaying()).thenReturn(false);
        InputEvent event = new InputEvent();

        InputListener guard = ReplayHud.waveSkipGuard(controller);

        assertFalse(guard.touchDown(event, 0f, 0f, 0, KeyCode.mouseLeft));
        assertFalse(event.stopped);
    }

    /** Checks the composed condition disables the button during playback. */
    @Test
    void waveSkipDisabledBlocksDuringPlayback() {
        ReplayController controller = mock(ReplayController.class);
        when(controller.isPlaying()).thenReturn(true);

        assertTrue(ReplayHud.waveSkipDisabled(controller, () -> false).get());
    }

    /** Checks the composed condition defers to the HUD outside playback. */
    @Test
    void waveSkipDisabledFollowsHudConditionOutsidePlayback() {
        ReplayController controller = mock(ReplayController.class);
        when(controller.isPlaying()).thenReturn(false);

        assertTrue(ReplayHud.waveSkipDisabled(controller, () -> true).get());
        assertFalse(ReplayHud.waveSkipDisabled(controller, () -> false).get());
    }
}
