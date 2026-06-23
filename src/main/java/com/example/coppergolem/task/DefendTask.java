package com.example.coppergolem.task;

import com.example.coppergolem.entity.GolemPrimitives;
import net.minecraft.world.entity.LivingEntity;

/**
 * Defend mode: runs continuously (never returns {@code true}).
 * Each tick, scans for hostile mobs within 8 blocks; if one is found, moves in
 * and attacks it. Returns to watching once the immediate threat is gone.
 *
 * <p>This task must be stopped externally via {@link com.example.coppergolem.entity.GolemController#stop()}.</p>
 */
public final class DefendTask implements TaskHandler {

    private static final int WATCH_RADIUS  = 8;
    private static final double ATTACK_RANGE = 2.5;

    private LivingEntity currentTarget = null;
    private String statusMsg = "defending — watching";

    @Override
    public boolean tick(GolemPrimitives g) {
        // Refresh dead/removed target.
        if (currentTarget != null && (currentTarget.isRemoved() || currentTarget.isDeadOrDying())) {
            currentTarget = null;
        }

        // Look for a (new) threat.
        if (currentTarget == null) {
            currentTarget = g.findNearestHostile(WATCH_RADIUS);
        }

        if (currentTarget == null) {
            statusMsg = "defending — watching";
            return false; // peaceful — keep watching
        }

        String mobType = currentTarget.getType().getDescriptionId();
        statusMsg = "defending — attacking " + mobType;

        if (g.position().closerThan(currentTarget.blockPosition(), ATTACK_RANGE)) {
            g.attackEntity(currentTarget);
        } else {
            g.moveTo(currentTarget.blockPosition());
        }

        return false; // never completes on its own
    }

    @Override
    public String status() {
        return statusMsg;
    }

    @Override
    public void pause() {}

    @Override
    public void resume() {}
}
