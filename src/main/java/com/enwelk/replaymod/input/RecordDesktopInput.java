package com.enwelk.replaymod.input;

import com.enwelk.replaymod.Action;
import com.enwelk.replaymod.rep.RepWriter;

import arc.math.geom.Vec2;
import mindustry.Vars;
import mindustry.gen.Building;
import mindustry.gen.Teamc;
import mindustry.input.DesktopInput;

public class RecordDesktopInput extends DesktopInput implements AutoCloseable {
    private final RecordInput recorder;

    public RecordDesktopInput(RepWriter writer) {
        this.recorder = new RecordInput(writer);
    }

    @Override
    protected void updateMovement(mindustry.gen.Unit unit) {
        super.updateMovement(unit);
        var player = Vars.player;
        recorder.recordMovementIfChanged(
            unit, 
            unit.vel, 
            unit.aimX(), 
            unit.aimY(), 
            player != null && player.shooting, 
            player != null && player.boosting, 
            unit.mineTile
        );
    }

    @Override
    public void update() {
        recorder.preUpdate();
        super.update();
        recorder.postUpdate(this.mode, this.isBuilding);
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
        recorder.record(action);
    }

    public void flushCompletedTicks(double now) {
        recorder.flushCompletedTicks(now);
    }

    @Override
    public void close() {
        recorder.close();
    }
}


