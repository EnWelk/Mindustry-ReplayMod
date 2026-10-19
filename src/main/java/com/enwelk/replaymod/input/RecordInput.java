package com.enwelk.replaymod.input;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.enwelk.replaymod.Action;
import com.enwelk.replaymod.rep.RepWriter;

import arc.Events;
import arc.func.Cons;
import arc.math.geom.Vec2;
import arc.util.Log;
import arc.util.io.Writes;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.BuildRotateEvent;
import mindustry.game.EventType.BuildSelectEvent;
import mindustry.game.EventType.ConfigEvent;
import mindustry.game.EventType.PayloadDropEvent;
import mindustry.game.EventType.PickupEvent;
import mindustry.game.EventType.TapEvent;
import mindustry.game.EventType.UnitControlEvent;
import mindustry.input.PlaceMode;
import mindustry.io.TypeIO;
import mindustry.type.Item;
import mindustry.world.Tile;

public class RecordInput implements AutoCloseable {
    private final RepWriter writer;
    private final Vec2 lastMovement = new Vec2();
    private final Vec2 normalizedMovement = new Vec2();
    private final List<Action> pendingActions = new ArrayList<>();
    private static final float TELEPORT_RECORD_DISTANCE = 8f;

    private float lastUnitX = Float.NaN;
    private float lastUnitY = Float.NaN;
    private float lastAimX, lastAimY;
    private boolean lastShooting, lastBoosting, lastBuilding;
    private boolean hasRecordedInput;
    private long lastInputTick = Long.MIN_VALUE;
    private long pendingActionTick = Long.MIN_VALUE;
    private Tile lastMineTile;
    private PlaceMode lastMode = PlaceMode.none;

    private boolean hasPendingSample;
    private float pendingMoveX, pendingMoveY, pendingAimX, pendingAimY;
    private boolean pendingShooting, pendingBoosting;
    private Tile pendingMineTile;
    private double pendingFromTick;

    private long accTick = Long.MIN_VALUE;
    private double accWeight;
    private double accMoveX, accMoveY, accAimX, accAimY;
    private double accShootWeight, accBoostWeight;
    private Tile accMineTile;

    private final Cons<BuildSelectEvent> buildListener = this::recordBuild;
    private final Cons<ConfigEvent> configListener = this::recordConfig;
    private final Cons<UnitControlEvent> controlListener = this::recordUnitControl;
    private final Cons<BuildRotateEvent> rotateListener = this::recordRotation;
    private final Cons<TapEvent> tapListener = this::recordTap;
    private final Cons<PickupEvent> pickupListener = this::recordPickup;
    private final Cons<PayloadDropEvent> dropListener = this::recordDrop;

    public RecordInput(RepWriter writer) {
        this.writer = writer;
        registerEventListeners();
    }

    public void preUpdate() {
        double now = Vars.state.tick;
        long tick = (long)Math.floor(now);
        if (!pendingActions.isEmpty() && pendingActionTick < tick) {
            flushActions();
        }
        closePendingSample(now);
        while (accTick != Long.MIN_VALUE && accTick < tick) {
            finalizeTick();
        }
    }

    public void postUpdate(PlaceMode currentMode, boolean currentIsBuilding) {
        if (currentMode != null && currentMode != lastMode) {
            Action.ModeChange action = new Action.ModeChange();
            action.mode = (byte)currentMode.ordinal();
            record(action);
            lastMode = currentMode;
        }

        if (currentIsBuilding != lastBuilding) {
            Action.BuildPause action = new Action.BuildPause();
            action.isBuilding = (byte)(currentIsBuilding ? 1 : 0);
            record(action);
            lastBuilding = currentIsBuilding;
        }

        flushActions();
    }

    public void recordMovementIfChanged(mindustry.gen.Unit unit, Vec2 currentVelocity,
                                    float currentAimX, float currentAimY,
                                    boolean currentShooting, boolean currentBoosting,
                                    Tile currentMineTile) {
        float unitX = unit.x;
        float unitY = unit.y;
        if (Float.isFinite(unitX) && Float.isFinite(unitY)) {
            if (Float.isFinite(lastUnitX) && Float.isFinite(lastUnitY)) {
                float dx = unitX - lastUnitX;
                float dy = unitY - lastUnitY;

                if (dx * dx + dy * dy > TELEPORT_RECORD_DISTANCE * TELEPORT_RECORD_DISTANCE) {
                    Action.Teleport teleport = new Action.Teleport();
                    teleport.x = unitX;
                    teleport.y = unitY;
                    record(teleport);
                }
            }
            lastUnitX = unitX;
            lastUnitY = unitY;
        }
        var player = Vars.player;
        if (unit == null || writer == null || player == null || Vars.state == null) return;
        float movementX = currentVelocity == null ? 0f : currentVelocity.x;
        float movementY = currentVelocity == null ? 0f : currentVelocity.y;
        normalizeRecordedMovement(normalizedMovement, movementX, movementY, unit.speed());
        float aimX = Float.isFinite(currentAimX) ? currentAimX : lastAimX;
        float aimY = Float.isFinite(currentAimY) ? currentAimY : lastAimY;

        double now = Vars.state.tick;
        closePendingSample(now);

        hasPendingSample = true;
        pendingMoveX = normalizedMovement.x;
        pendingMoveY = normalizedMovement.y;
        pendingAimX = aimX;
        pendingAimY = aimY;
        pendingShooting = currentShooting;
        pendingBoosting = currentBoosting;
        pendingMineTile = currentMineTile;
        pendingFromTick = now;
    }

    private void closePendingSample(double now) {
        if (!hasPendingSample) return;
        double boundary = Math.floor(pendingFromTick) + 1.0;
        double weight = Math.min(Math.max(0.0, now - pendingFromTick), boundary - pendingFromTick);
        hasPendingSample = false;
        accumulateSample((long)Math.floor(pendingFromTick), weight,
            pendingMoveX, pendingMoveY, pendingAimX, pendingAimY,
            pendingShooting, pendingBoosting, pendingMineTile);
    }

    private void accumulateSample(long sampleTick, double weight, float movementX, float movementY,
                                  float aimX, float aimY, boolean shooting, boolean boosting, Tile mineTile) {
        if (accTick != Long.MIN_VALUE && sampleTick != accTick) {
            finalizeTick();
        }
        if (accTick == Long.MIN_VALUE) {
            accTick = sampleTick;
        }
        if (weight <= 0.0) return;
        accWeight += weight;
        accMoveX += movementX * weight;
        accMoveY += movementY * weight;
        accAimX += aimX * weight;
        accAimY += aimY * weight;
        if (shooting) accShootWeight += weight;
        if (boosting) accBoostWeight += weight;
        accMineTile = mineTile;
    }

    void flushRecordedInput(double now) {
        closePendingSample(now);
        hasPendingSample = false;
        finalizeTick();
    }

    public void flushCompletedTicks(double now) {
        if (hasPendingSample) {
            double boundary = Math.floor(pendingFromTick) + 1.0;
            if (now >= boundary) {
                accumulateSample((long)Math.floor(pendingFromTick), boundary - pendingFromTick,
                    pendingMoveX, pendingMoveY, pendingAimX, pendingAimY,
                    pendingShooting, pendingBoosting, pendingMineTile);
                pendingFromTick = boundary;
            }
        }
        while (accTick != Long.MIN_VALUE && accTick < (long)Math.floor(now)) {
            finalizeTick();
        }
    }

    private void finalizeTick() {
        if (accTick == Long.MIN_VALUE) return;
        long tick = accTick;
        double weight = accWeight;
        float movementX = 0f, movementY = 0f;
        float aimX = lastAimX, aimY = lastAimY;
        boolean shooting = false, boosting = false;
        Tile mineTile = accMineTile;
        if (weight > 0.0) {
            movementX = (float)(accMoveX / weight);
            movementY = (float)(accMoveY / weight);
            aimX = (float)(accAimX / weight);
            aimY = (float)(accAimY / weight);
            shooting = accShootWeight * 2.0 >= weight;
            boosting = accBoostWeight * 2.0 >= weight;
            float length = (float)Math.sqrt(movementX * movementX + movementY * movementY);
            if (length > 1f) {
                movementX /= length;
                movementY /= length;
            }
        }
        accTick = Long.MIN_VALUE;
        accWeight = 0.0;
        accMoveX = 0.0;
        accMoveY = 0.0;
        accAimX = 0.0;
        accAimY = 0.0;
        accShootWeight = 0.0;
        accBoostWeight = 0.0;
        accMineTile = null;

        if (weight <= 0.0) return;

        if (shouldRecordContinuousInput(tick, lastInputTick, shooting, boosting)
                || !hasRecordedInput
                || Math.abs(movementX - lastMovement.x) > 0.01f
                || Math.abs(movementY - lastMovement.y) > 0.01f
                || Math.abs(aimX - lastAimX) > 0.1f
                || Math.abs(aimY - lastAimY) > 0.1f
                || shooting != lastShooting
                || boosting != lastBoosting
                || mineTile != lastMineTile) {
            try {
                writer.writeTickPacket(
                    tick,
                    movementX,
                    movementY,
                    aimX,
                    aimY,
                    shooting,
                    boosting,
                    mineTile
                );
            } catch (IOException e) {
                Log.err("Failed to write tick packet to replay", e);
            }
            lastMovement.set(movementX, movementY);
            lastAimX = aimX;
            lastAimY = aimY;
            lastShooting = shooting;
            lastBoosting = boosting;
            lastMineTile = mineTile;
            hasRecordedInput = true;
            lastInputTick = tick;
        }
    }

    static boolean shouldRecordContinuousInput(long tick, long lastInputTick, boolean shooting, boolean boosting) {
        return tick != lastInputTick && (shooting || boosting);
    }

    static Vec2 normalizeRecordedMovement(Vec2 out, float movementX, float movementY, float unitSpeed) {
        if (!Float.isFinite(movementX) || !Float.isFinite(movementY) ||
            !Float.isFinite(unitSpeed) || unitSpeed <= 0.00001f) {
            return out.setZero();
        }
        float x = movementX / unitSpeed;
        float y = movementY / unitSpeed;
        float length = (float)Math.sqrt(x * x + y * y);
        if (length > 1f) {
            x /= length;
            y /= length;
        }
        return out.set(x, y);
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

    /** Records a local player's build or break plan. */
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

    /** Records a local player's building configuration change. */
    private void recordConfig(ConfigEvent event) {
        var player = event.player;
        if (event.tile == null || player == null || !player.isLocal()) return;

        Action.Config action = new Action.Config();
        action.buildId = event.tile.tile.pos();
        if (setConfig(action, event.value)) record(action);
    }

    /** Records entering or leaving direct unit control. */
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

    /** Records a local player's building rotation. */
    private void recordRotation(BuildRotateEvent event) {
        if (event.unit == null || !isLocalUnit(event.unit)) return;

        Action.RotateBlock action = new Action.RotateBlock();
        action.buildId = event.build.tile.pos();
        action.direction = (byte)(((event.build.rotation - event.previous + 4) % 4) == 1 ? 1 : 0);
        record(action);
    }

    /** Records a local player's tile tap. */
    private void recordTap(TapEvent event) {
        if (event.player == null || !event.player.isLocal() || event.tile == null) return;

        Action.TileTap action = new Action.TileTap();
        action.x = (short)event.tile.x;
        action.y = (short)event.tile.y;
        record(action);
    }

    /** Records a payload pickup made by the local player. */
    private void recordPickup(PickupEvent event) {
        var player = Vars.player;
        if (player == null || player.unit() != event.carrier) return;
        Action.PayloadAction action = payloadAction((byte)1, event.unit, event.build, event.carrier.x, event.carrier.y);
        if (action != null) record(action);
    }

    /** Records a payload drop made by the local player. */
    private void recordDrop(PayloadDropEvent event) {
        var player = Vars.player;
        if (player == null || player.unit() != event.carrier) return;
        Action.PayloadAction action = payloadAction((byte)2, event.unit, event.build, event.carrier.x, event.carrier.y);
        if (action != null) record(action);
    }

    /** Creates a replay action describing a payload interaction. */
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

    /** Encodes a supported building config value into an action. */
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
        } else if (value instanceof Jval json) {
            byte[] data = json.toString().getBytes(StandardCharsets.UTF_8);
            if (action instanceof Action.Config configAction) {
                configAction.configType = 5;
                configAction.configData = data;
            } else if (action instanceof Action.BuildPlan plan) {
                plan.configType = 5;
                plan.configData = data;
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

    public void record(Action action) {
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
        if (writer != null && Vars.state != null) {
            flushRecordedInput(Vars.state.tick);
        }
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


