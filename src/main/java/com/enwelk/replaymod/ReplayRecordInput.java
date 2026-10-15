package com.enwelk.replaymod;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import arc.Events;
import arc.func.Cons;
import arc.math.geom.Vec2;
import arc.util.Log;
import arc.util.io.Writes;
import mindustry.Vars;
import mindustry.game.EventType.BuildRotateEvent;
import mindustry.game.EventType.BuildSelectEvent;
import mindustry.game.EventType.ConfigEvent;
import mindustry.game.EventType.PayloadDropEvent;
import mindustry.game.EventType.PickupEvent;
import mindustry.game.EventType.TapEvent;
import mindustry.game.EventType.UnitControlEvent;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.gen.Teamc;
import mindustry.input.DesktopInput;
import mindustry.input.PlaceMode;
import mindustry.io.TypeIO;
import mindustry.type.Item;
import mindustry.world.Tile;

import com.enwelk.replaymod.rep.RepWriter;

public class ReplayRecordInput extends DesktopInput implements AutoCloseable {
    private final RepWriter writer;
    private final Vec2 lastMovement = new Vec2();
    private final List<Action> pendingActions = new ArrayList<>();
    private float lastAimX, lastAimY;
    private boolean lastShooting, lastBoosting, lastBuilding;
    private boolean hasRecordedInput;
    private long lastInputTick = Long.MIN_VALUE;
    private long pendingActionTick = Long.MIN_VALUE;
    private Tile lastMineTile;
    private PlaceMode lastMode = PlaceMode.none;

    private final Cons<BuildSelectEvent> buildListener = this::recordBuild;
    private final Cons<ConfigEvent> configListener = this::recordConfig;
    private final Cons<UnitControlEvent> controlListener = this::recordUnitControl;
    private final Cons<BuildRotateEvent> rotateListener = this::recordRotation;
    private final Cons<TapEvent> tapListener = this::recordTap;
    private final Cons<PickupEvent> pickupListener = this::recordPickup;
    private final Cons<PayloadDropEvent> dropListener = this::recordDrop;

    public ReplayRecordInput(RepWriter writer) {
        this.writer = writer;
        registerEventListeners();
    }

    @Override
    protected void updateMovement(mindustry.gen.Unit unit) {
        super.updateMovement(unit);
        recordMovementIfChanged(unit);
    }

    @Override
    public void update() {
        long tick = (long)Math.floor(Vars.state.tick);
        if (!pendingActions.isEmpty() && pendingActionTick < tick) {
            flushActions();
        }

        super.update();

        if (mode != null && mode != lastMode) {
            Action.ModeChange action = new Action.ModeChange();
            action.mode = (byte)mode.ordinal();
            record(action);
            lastMode = mode;
        }

        if (isBuilding != lastBuilding) {
            Action.BuildPause action = new Action.BuildPause();
            action.isBuilding = (byte)(isBuilding ? 1 : 0);
            record(action);
            lastBuilding = isBuilding;
        }

        flushActions();
    }

    @Override
    public void commandTap(float screenX, float screenY, boolean queue) {
        if (!commandMode || selectedUnits.isEmpty() || Vars.player == null) {
            super.commandTap(screenX, screenY, queue);
            return;
        }

        Vec2 targetPosition = arc.Core.input.mouseWorld(screenX, screenY).cpy();
        Teamc target = Vars.world.buildWorld(targetPosition.x, targetPosition.y);
        if (target == null || target.team() == Vars.player.team()) {
            target = selectedEnemyUnit(targetPosition.x, targetPosition.y);
        }

        Action.Command action = new Action.Command();
        action.posX = targetPosition.x;
        action.posY = targetPosition.y;
        action.unitIds = new int[selectedUnits.size];
        for (int i = 0; i < selectedUnits.size; i++) {
            action.unitIds[i] = selectedUnits.get(i).id();
        }
        if (target instanceof mindustry.gen.Unit unit) {
            action.cmdType = (byte)(1 | (queue ? 4 : 0));
            action.targetId = unit.id();
        } else if (target instanceof Building building) {
            action.cmdType = (byte)(2 | (queue ? 4 : 0));
            action.targetId = building.tile.pos();
        } else {
            action.cmdType = (byte)(queue ? 4 : 0);
            action.targetId = -1;
        }

        super.commandTap(screenX, screenY, queue);
        record(action);
    }

    private void recordMovementIfChanged(mindustry.gen.Unit unit) {
        var player = Vars.player;
        if (unit == null || writer == null || player == null) {
            return;
        }

        Tile mineTile = unit.mineTile;
        long tick = (long)Math.floor(Vars.state.tick);
        if (tick != lastInputTick && (!hasRecordedInput ||
            !movement.epsilonEquals(lastMovement, 0.001f) ||
            Math.abs(unit.aimX() - lastAimX) > 0.1f ||
            Math.abs(unit.aimY() - lastAimY) > 0.1f ||
            player.shooting != lastShooting ||
            player.boosting != lastBoosting ||
            mineTile != lastMineTile)) {

            try {
                writer.writeTickPacket(
                    tick,
                    movement.x, movement.y,
                    unit.aimX(), unit.aimY(),
                    player.shooting,
                    player.boosting,
                    mineTile
                );
            } catch (IOException e) {
                Log.err("Failed to write tick packet to replay", e);
            }

            lastMovement.set(movement);
            lastAimX = unit.aimX();
            lastAimY = unit.aimY();
            lastShooting = player.shooting;
            lastBoosting = player.boosting;
            lastMineTile = mineTile;
            hasRecordedInput = true;
            lastInputTick = tick;
        }
    }

    private void registerEventListeners() {
        Events.on(BuildSelectEvent.class, buildListener);
        Events.on(ConfigEvent.class, configListener);
        Events.on(UnitControlEvent.class, controlListener);
        Events.on(BuildRotateEvent.class, rotateListener);
        Events.on(TapEvent.class, tapListener);
        Events.on(PickupEvent.class, pickupListener);
        Events.on(PayloadDropEvent.class, dropListener);
    }

    private void recordBuild(BuildSelectEvent event) {
        if (event.tile == null || event.builder == null || !isLocalUnit(event.builder)) return;

        BuildPlan current = null;
        for (BuildPlan plan : event.builder.plans) {
            if (plan.x == event.tile.x && plan.y == event.tile.y) {
                current = plan;
                break;
            }
        }
        if (event.breaking) {
            Action.BreakPlan action = new Action.BreakPlan();
            action.x = (short)event.tile.x;
            action.y = (short)event.tile.y;
            record(action);
        } else if (current != null && current.block != null) {
            var block = current.block;
            Action.BuildPlan action = new Action.BuildPlan();
            action.x = (short)current.x;
            action.y = (short)current.y;
            action.rotation = (byte)current.rotation;
            action.blockId = block.id;
            if (setConfig(action, current.config)) record(action);
        }
    }

    private void recordConfig(ConfigEvent event) {
        var player = event.player;
        if (event.tile == null || player == null || !player.isLocal()) return;

        Action.Config action = new Action.Config();
        action.buildId = event.tile.tile.pos();
        if (setConfig(action, event.value)) record(action);
    }

    private void recordUnitControl(UnitControlEvent event) {
        var player = event.player;
        if (player == null || !player.isLocal()) return;
        var unit = event.unit;
        if (unit == null) {
            record(new Action.UnitClear());
        } else {
            Action.UnitControl action = new Action.UnitControl();
            action.unitId = unit.id();
            record(action);
        }
    }

    private void recordRotation(BuildRotateEvent event) {
        if (event.unit == null || !isLocalUnit(event.unit)) return;

        Action.RotateBlock action = new Action.RotateBlock();
        action.buildId = event.build.tile.pos();
        action.direction = (byte)(((event.build.rotation - event.previous + 4) % 4) == 1 ? 1 : 0);
        record(action);
    }

    private void recordTap(TapEvent event) {
        if (event.player == null || !event.player.isLocal() || event.tile == null) return;

        Action.TileTap action = new Action.TileTap();
        action.x = (short)event.tile.x;
        action.y = (short)event.tile.y;
        record(action);
    }

    private void recordPickup(PickupEvent event) {
        var player = Vars.player;
        if (player == null || player.unit() != event.carrier) return;
        Action.PayloadAction action = payloadAction((byte)1, event.unit, event.build, event.carrier.x, event.carrier.y);
        if (action != null) record(action);
    }

    private void recordDrop(PayloadDropEvent event) {
        var player = Vars.player;
        if (player == null || player.unit() != event.carrier) return;
        Action.PayloadAction action = payloadAction((byte)2, event.unit, event.build, event.carrier.x, event.carrier.y);
        if (action != null) record(action);
    }

    private Action.PayloadAction payloadAction(byte actionType, mindustry.gen.Unit unit, mindustry.gen.Building build, float x, float y) {
        if (unit == null && build == null) return null;

        Action.PayloadAction action = new Action.PayloadAction();
        action.payloadActionType = actionType;
        if (unit != null) {
            action.targetType = 1;
            action.targetId = unit.id();
        } else {
            action.targetType = 2;
            action.targetId = build.tile.pos();
        }
        action.dropX = x;
        action.dropY = y;
        return action;
    }

    private boolean setConfig(Action action, Object value) {
        if (value == null) return true;

        if (value instanceof Integer integer) {
            if (action instanceof Action.Config configAction) {
                configAction.configType = 1;
                configAction.configInt = integer;
            } else if (action instanceof Action.BuildPlan plan) {
                plan.configType = 1;
                plan.configInt = integer;
            }
            return true;
        } else if (value instanceof arc.math.geom.Point2 point) {
            if (action instanceof Action.Config configAction) {
                configAction.configType = 2;
                configAction.configShortX = (short)point.x;
                configAction.configShortY = (short)point.y;
            } else if (action instanceof Action.BuildPlan plan) {
                plan.configType = 2;
                plan.configShortX = (short)point.x;
                plan.configShortY = (short)point.y;
            }
            return true;
        } else if (value instanceof Item item) {
            if (action instanceof Action.Config configAction) {
                configAction.configType = 3;
                configAction.configItemId = item.id;
            } else if (action instanceof Action.BuildPlan plan) {
                plan.configType = 3;
                plan.configItemId = item.id;
            }
            return true;
        } else {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream output = new DataOutputStream(bytes)) {
                    TypeIO.writeObject(new Writes(output), value);
                }
                byte[] data = bytes.toByteArray();
                if (action instanceof Action.Config configAction) {
                    configAction.configType = 4;
                    configAction.configData = data;
                } else if (action instanceof Action.BuildPlan plan) {
                    plan.configType = 4;
                    plan.configData = data;
                }
                return true;
            } catch (RuntimeException | IOException e) {
                Log.err("Failed to serialize replay config value of type @", value.getClass().getName());
                Log.err(e);
                return false;
            }
        }
    }

    private void record(Action action) {
        long tick = (long)Math.floor(Vars.state.tick);
        if (!pendingActions.isEmpty() && tick != pendingActionTick) {
            flushActions();
        }
        if (pendingActions.isEmpty()) {
            pendingActionTick = tick;
        }
        pendingActions.add(action);
    }

    private void flushActions() {
        if (pendingActions.isEmpty()) return;
        try {
            writer.writeActions(pendingActionTick, pendingActions);
        } catch (IOException | RuntimeException e) {
            Log.err("Failed to write action to replay", e);
        } finally {
            pendingActions.clear();
            pendingActionTick = Long.MIN_VALUE;
        }
    }

    private boolean isLocalUnit(mindustry.gen.Unit unit) {
        var player = Vars.player;
        return player != null && player.unit() == unit;
    }

    @Override
    public void close() {
        flushActions();
        Events.remove(BuildSelectEvent.class, buildListener);
        Events.remove(ConfigEvent.class, configListener);
        Events.remove(UnitControlEvent.class, controlListener);
        Events.remove(BuildRotateEvent.class, rotateListener);
        Events.remove(TapEvent.class, tapListener);
        Events.remove(PickupEvent.class, pickupListener);
        Events.remove(PayloadDropEvent.class, dropListener);
    }
}
