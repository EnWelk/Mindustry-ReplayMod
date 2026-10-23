package com.enwelk.replaymod.input;

import java.util.ArrayList;
import java.util.List;

import com.enwelk.replaymod.rep.RepWriter;

import arc.math.geom.Vec2;
import mindustry.input.PlaceMode;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecordInputTests {

    @Test
    void continuousInputsAreRecordedOnEveryNewTick() {
        assertTrue(RecordInput.shouldRecordContinuousInput(12L, 11L, true, false));
        assertTrue(RecordInput.shouldRecordContinuousInput(12L, 11L, false, true));
        assertFalse(RecordInput.shouldRecordContinuousInput(12L, 11L, false, false));
        assertFalse(RecordInput.shouldRecordContinuousInput(12L, 12L, true, false));
    }

    @Test
    void recordedMovementKeepsAnalogMagnitude() {
        Vec2 out = RecordInput.normalizeRecordedMovement(new Vec2(), 5.25f, 0f, 10.5f);

        assertEquals(0.5f, out.x, 0.0001f);
        assertEquals(0f, out.y, 0.0001f);
    }

    @Test
    void recordedMovementKeepsDiagonalProportions() {
        float component = 7f / (float)Math.sqrt(2.0);
        Vec2 out = RecordInput.normalizeRecordedMovement(new Vec2(), component, component, 7f);

        assertEquals(0.7071f, out.x, 0.001f);
        assertEquals(0.7071f, out.y, 0.001f);
        assertEquals(1f, out.len(), 0.001f);
    }

    @Test
    void recordedMovementIsClampedToUnitCircle() {
        Vec2 out = RecordInput.normalizeRecordedMovement(new Vec2(), 20f, 20f, 10f);

        assertEquals(1f, out.len(), 0.0001f);
    }

    @Test
    void recordedMovementFallsBackToZero() {
        assertTrue(RecordInput.normalizeRecordedMovement(new Vec2(), 5f, 5f, 0f).isZero());
        assertTrue(RecordInput.normalizeRecordedMovement(new Vec2(), Float.NaN, 1f, 5f).isZero());
        assertTrue(RecordInput.normalizeRecordedMovement(new Vec2(), 1f, Float.POSITIVE_INFINITY, 5f).isZero());
    }

    @Test
    void partialTickPressIsAveragedNotStretched() throws Exception {
        GameState previousState = Vars.state;
        Player previousPlayer = Vars.player;
        try {
            GameState state = mock(GameState.class);
            Vars.state = state;
            Vars.player = mock(Player.class);
            RepWriter writer = mock(RepWriter.class);
            RecordInput input = new RecordInput(writer);
            try {
                Unit unit = mock(Unit.class);
                when(unit.speed()).thenReturn(2f);

                state.tick = 100.0;
                input.preUpdate();
                input.recordMovementIfChanged(unit, new Vec2(2f, 0f), 10f, 20f, false, false, null);

                state.tick = 100.5;
                input.preUpdate();
                input.recordMovementIfChanged(unit, new Vec2(0f, 0f), 10f, 20f, false, false, null);

                state.tick = 101.0;
                input.preUpdate();

                ArgumentCaptor<Float> mx = ArgumentCaptor.forClass(Float.class);
                verify(writer, atLeastOnce()).writeTickPacket(eq(100L), mx.capture(), eq(0f),
                    anyFloat(), anyFloat(), anyBoolean(), anyBoolean(), any());
                assertEquals(0.5f, mx.getValue(), 0.01f);
            } finally {
                input.close();
            }
        } finally {
            Vars.state = previousState;
            Vars.player = previousPlayer;
        }
    }

    @Test
    void fullTickPressKeepsFullMagnitude() throws Exception {
        GameState previousState = Vars.state;
        Player previousPlayer = Vars.player;
        try {
            GameState state = mock(GameState.class);
            Vars.state = state;
            Vars.player = mock(Player.class);
            RepWriter writer = mock(RepWriter.class);
            RecordInput input = new RecordInput(writer);
            try {
                Unit unit = mock(Unit.class);
                when(unit.speed()).thenReturn(2f);

                state.tick = 100.0;
                input.preUpdate();
                input.recordMovementIfChanged(unit, new Vec2(2f, 0f), 10f, 20f, false, false, null);

                state.tick = 101.0;
                input.preUpdate();

                verify(writer).writeTickPacket(eq(100L), eq(1f), eq(0f),
                    anyFloat(), anyFloat(), anyBoolean(), anyBoolean(), any());
            } finally {
                input.close();
            }
        } finally {
            Vars.state = previousState;
            Vars.player = previousPlayer;
        }
    }

    @Test
    void unchangedInputAcrossTicksWritesOnePacket() throws Exception {
        GameState previousState = Vars.state;
        Player previousPlayer = Vars.player;
        try {
            GameState state = mock(GameState.class);
            Vars.state = state;
            Vars.player = mock(Player.class);
            RepWriter writer = mock(RepWriter.class);
            RecordInput input = new RecordInput(writer);
            try {
                Unit unit = mock(Unit.class);
                when(unit.speed()).thenReturn(2f);

                for (int frame = 0; frame < 6; frame++) {
                    state.tick = 100.0 + frame * 0.5;
                    input.preUpdate();
                    input.recordMovementIfChanged(unit, new Vec2(2f, 0f), 10f, 20f, false, false, null);
                }
                state.tick = 103.0;
                input.preUpdate();

                verify(writer, times(1)).writeTickPacket(anyLong(), anyFloat(), anyFloat(),
                    anyFloat(), anyFloat(), anyBoolean(), anyBoolean(), any());
            } finally {
                input.close();
            }
        } finally {
            Vars.state = previousState;
            Vars.player = previousPlayer;
        }
    }

    @Test
    void shootingMajorityWithinTickIsRecorded() throws Exception {
        GameState previousState = Vars.state;
        Player previousPlayer = Vars.player;
        try {
            GameState state = mock(GameState.class);
            Vars.state = state;
            Vars.player = mock(Player.class);
            RepWriter writer = mock(RepWriter.class);
            RecordInput input = new RecordInput(writer);
            try {
                Unit unit = mock(Unit.class);
                when(unit.speed()).thenReturn(2f);

                state.tick = 100.0;
                input.preUpdate();
                input.recordMovementIfChanged(unit, new Vec2(0f, 0f), 10f, 20f, true, false, null);

                state.tick = 100.6;
                input.preUpdate();
                input.recordMovementIfChanged(unit, new Vec2(0f, 0f), 10f, 20f, false, false, null);

                state.tick = 101.0;
                input.preUpdate();

                verify(writer, atLeastOnce()).writeTickPacket(eq(100L), anyFloat(), anyFloat(),
                    anyFloat(), anyFloat(), eq(true), eq(false), any());
            } finally {
                input.close();
            }
        } finally {
            Vars.state = previousState;
            Vars.player = previousPlayer;
        }
    }

    @Test
    void closeFlushesTheLastPartialTick() throws Exception {
        GameState previousState = Vars.state;
        Player previousPlayer = Vars.player;
        try {
            GameState state = mock(GameState.class);
            Vars.state = state;
            Vars.player = mock(Player.class);
            RepWriter writer = mock(RepWriter.class);
            RecordInput input = new RecordInput(writer);

            Unit unit = mock(Unit.class);
            when(unit.speed()).thenReturn(2f);

            state.tick = 100.0;
            input.preUpdate();
            input.recordMovementIfChanged(unit, new Vec2(2f, 0f), 10f, 20f, false, false, null);

            state.tick = 100.5;
            input.close();

            verify(writer).writeTickPacket(eq(100L), eq(1f), eq(0f),
                anyFloat(), anyFloat(), anyBoolean(), anyBoolean(), any());
        } finally {
            Vars.state = previousState;
            Vars.player = previousPlayer;
        }
    }

    @Test
    void movementPacketsSurviveRandomStateInterleaving() throws Exception {
        GameState previousState = Vars.state;
        Player previousPlayer = Vars.player;
        try {
            GameState state = mock(GameState.class);
            Vars.state = state;
            Vars.player = mock(Player.class);

            List<Long> written = new ArrayList<>();
            org.mockito.stubbing.Answer<Object> orderGuard = inv -> {
                long t = inv.getArgument(0);
                if (!written.isEmpty() && t < written.get(written.size() - 1)) {
                    throw new AssertionError("nondecreasing tick violation: " + t +
                        " after " + written.get(written.size() - 1));
                }
                written.add(t);
                return null;
            };
            RepWriter writer = mock(RepWriter.class);
            doAnswer(orderGuard).when(writer).writeTickPacket(anyLong(), anyFloat(), anyFloat(),
                anyFloat(), anyFloat(), anyBoolean(), anyBoolean(), any());
            doAnswer(orderGuard).when(writer).writeRandomState(anyLong());
            doAnswer(orderGuard).when(writer).writeActions(anyLong(), any());

            RecordInput input = new RecordInput(writer);
            try {
                Unit unit = mock(Unit.class);
                when(unit.speed()).thenReturn(2f);

                double frame = 100.0;
                for (int i = 0; i < 10; i++) {
                    state.tick = frame;
                    input.preUpdate();
                    input.recordMovementIfChanged(unit, new Vec2(2f, 0f), 10f, 20f, false, false, null);
                    input.postUpdate(PlaceMode.none, false);

                    double next = frame + 0.42;
                    state.tick = next;
                    input.flushCompletedTicks(next);
                    writer.writeRandomState((long)Math.floor(next));
                    frame = next;
                }
            } finally {
                input.close();
            }

            verify(writer, atLeastOnce()).writeTickPacket(eq(100L), eq(1f), eq(0f),
                anyFloat(), anyFloat(), anyBoolean(), anyBoolean(), any());
        } finally {
            Vars.state = previousState;
            Vars.player = previousPlayer;
        }
    }
}
