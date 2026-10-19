package com.enwelk.replaymod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.enwelk.replaymod.util.VarInt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActionTests {

    /** Checks binary round-trips for every recorded action type. */
    @Test
    void everyActionTypeRoundTripsItsSerializedData() {
        List<Action> actions = new ArrayList<>();
        actions.add(buildPlan((byte)0));
        actions.add(buildPlan((byte)1));
        actions.add(buildPlan((byte)2));
        actions.add(buildPlan((byte)3));
        actions.add(buildPlan((byte)4));
        actions.add(buildPlan((byte)5));
        actions.add(new Action.BreakPlan() {{ x = 123; y = -45; }});
        actions.add(config((byte)0));
        actions.add(config((byte)1));
        actions.add(config((byte)2));
        actions.add(config((byte)3));
        actions.add(config((byte)4));
        actions.add(config((byte)5));
        actions.add(new Action.UnitControl() {{ unitId = 54321; }});
        actions.add(new Action.UnitClear());
        actions.add(new Action.RotateBlock() {{ buildId = 991; direction = 1; }});
        actions.add(new Action.Command() {{
            cmdType = 6;
            targetId = 205;
            posX = 17.5f;
            posY = -3.25f;
            unitIds = new int[]{4, 8, 15};
        }});
        actions.add(new Action.PayloadAction() {{
            payloadActionType = 2;
            targetType = 3;
            targetId = 89;
            dropX = 2.5f;
            dropY = 9.25f;
        }});
        actions.add(new Action.TileTap() {{ x = 41; y = 72; }});
        actions.add(new Action.BuildPause() {{ isBuilding = 1; }});
        actions.add(new Action.ModeChange() {{ mode = 2; }});

        for (Action action : actions) {
            ByteBuffer encoded = Action.createAction(action);
            byte[] expected = new byte[encoded.remaining()];
            encoded.get(expected);

            Action decoded = Action.read(ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN));

            assertEquals(action.actionType, decoded.actionType);
            assertArrayEquals(expected, bytes(Action.createAction(decoded)));
        }
    }

    /** Checks that serialized config bytes survive an action round-trip. */
    @Test
    void configActionsPreserveTheirTypedPayloads() {
        Action.Config config = config((byte)4);
        Action.Config decoded = assertInstanceOf(Action.Config.class,
            Action.read(Action.createAction(config)));

        assertArrayEquals(config.configData, decoded.configData);
        assertEquals(config.buildId, decoded.buildId);
    }

    /** Checks that unknown action and config IDs fail explicitly. */
    @Test
    void unknownActionAndConfigTypesAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> Action.read(ByteBuffer.wrap(new byte[]{99})));

        ByteBuffer invalidConfig = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
        invalidConfig.put((byte)3);
        VarInt.writeSigned(invalidConfig, 1);
        invalidConfig.put((byte)99).flip();
        assertThrows(IllegalArgumentException.class, () -> Action.read(invalidConfig));
    }

    /** Checks that invalid command and config lengths are rejected. */
    @Test
    void malformedCommandAndConfigLengthsAreRejected() {
        ByteBuffer invalidCommand = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        invalidCommand.put((byte)7).put((byte)0);
        VarInt.writeSigned(invalidCommand, 1);
        invalidCommand.putFloat(0).putFloat(0);
        VarInt.write(invalidCommand, -1);
        invalidCommand.flip();
        assertThrows(IllegalArgumentException.class, () -> Action.read(invalidCommand));

        ByteBuffer invalidConfig = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        invalidConfig.put((byte)3);
        VarInt.writeSigned(invalidConfig, 1);
        invalidConfig.put((byte)4);
        VarInt.write(invalidConfig, 8);
        invalidConfig.flip();
        assertThrows(IllegalArgumentException.class, () -> Action.read(invalidConfig));
    }

    /** Checks that command payloads reject unit counts above the format limit. */
    @Test
    void commandRejectsTooManyUnitIdsBeforeWriting() {
        Action.Command command = new Action.Command();
        command.unitIds = new int[Short.MAX_VALUE + 1];

        assertThrows(IllegalArgumentException.class, command::getBufferSize);
    }

    /** Creates a build-plan fixture with the requested config representation. */
    private static Action.BuildPlan buildPlan(byte configType) {
        Action.BuildPlan plan = new Action.BuildPlan();
        plan.x = 10;
        plan.y = -20;
        plan.rotation = 3;
        plan.blockId = 111;
        plan.configType = configType;
        plan.configInt = 7654;
        plan.configShortX = 12;
        plan.configShortY = -13;
        plan.configItemId = 77;
        plan.configData = configType == 4 || configType == 5
            ? new byte[]{0, 1, -1, 42}
            : null;
        return plan;
    }

    /** Creates a building-config fixture with the requested config representation. */
    private static Action.Config config(byte configType) {
        Action.Config config = new Action.Config();
        config.buildId = 8001;
        config.configType = configType;
        config.configInt = 123456;
        config.configShortX = 34;
        config.configShortY = -56;
        config.configItemId = 7;
        config.configData = configType == 4 || configType == 5
            ? new byte[]{9, 8, 7, 6}
            : null;
        return config;
    }

    /** Copies the remaining serialized bytes from a buffer. */
    private static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }
}


