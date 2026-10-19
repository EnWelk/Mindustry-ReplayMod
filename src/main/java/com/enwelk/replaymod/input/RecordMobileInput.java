package com.enwelk.replaymod.input;

import mindustry.Vars;
import mindustry.input.MobileInput;
import com.enwelk.replaymod.rep.RepWriter;

public class RecordMobileInput extends MobileInput implements AutoCloseable {
    private final RecordInput recorder;

    public RecordMobileInput(RepWriter writer) {
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

    public void flushCompletedTicks(double now) {
        recorder.flushCompletedTicks(now);
    }

    @Override
    public void close() {
        recorder.close();
    }
}


