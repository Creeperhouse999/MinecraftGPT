package com.example.coppergolem.task;

import com.example.coppergolem.entity.GolemPrimitives;
import net.minecraft.world.entity.LivingEntity;

/**
 * Attacks the nearest hostile mob within 16 blocks until no more hostiles remain
 * in range. Returns {@code true} (complete) when the area is clear.
 *
 * <p>Each tick: find nearest hostile → move toward it → when within 2 blocks, attack.
 * If no hostile is found, the task completes.</p>
 */
public final class AttackTask implements TaskHandler {

    private static final int SEARCH_RADIUS  = 16;
    private static final double ATTACK_RANGE = 2.5;

    private LivingEntity currentTarget = null;
    private String statusMsg = "searching for hostiles";

    @Override
    public boolean tick(GolemPrimitives g) {
        // Refresh or find a target.
        if (currentTarget == null || currentTarget.isRemoved() || currentTarget.isDeadOrDying()) {
            currentTarget = g.findNearestHostile(SEARCH_RADIUS);
        }

        if (currentTarget == null || currentTarget.isRemoved() || currentTarget.isDeadOrDying()) {
            statusMsg = "no hostiles nearby";
            return true; // area is clear — done
        }

        String mobType = currentTarget.getType().getDescriptionId();
        statusMsg = "attacking " + mobType;

        // Move toward the target.
        if (g.position().closerThan(currentTarget.blockPosition(), ATTACK_RANGE)) {
            // In range — swing!
            g.attackEntity(currentTarget);
        } else {
            g.moveTo(currentTarget.blockPosition());
        }
        return false; // still fighting
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
