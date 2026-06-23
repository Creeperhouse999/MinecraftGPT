package com.example.coppergolem.task;

import com.example.coppergolem.entity.GolemPrimitives;
import net.minecraft.core.BlockPos;

import java.util.function.Supplier;

/**
 * One-shot "come here" task: navigates the golem to within 3 blocks of the owner
 * and then completes (returns {@code true}).
 *
 * <p>Unlike {@link FollowTask}, this task finishes once the golem reaches the
 * owner's vicinity, leaving the golem idle afterward.</p>
 */
public final class ComeTask implements TaskHandler {

    private static final double ARRIVE_THRESHOLD = 3.0;

    private final Supplier<BlockPos> ownerPosSupplier;
    private boolean done = false;

    /**
     * @param ownerPosSupplier supplier resolved each tick to the owner's current block position;
     *                         may return {@code null} if the owner is offline.
     */
    public ComeTask(Supplier<BlockPos> ownerPosSupplier) {
        this.ownerPosSupplier = ownerPosSupplier;
    }

    @Override
    public boolean tick(GolemPrimitives g) {
        if (done) return true;

        BlockPos ownerPos = ownerPosSupplier.get();
        if (ownerPos == null) {
            // Owner offline — complete immediately rather than hang.
            done = true;
            return true;
        }

        // Check if already close enough.
        if (g.position().closerThan(ownerPos, ARRIVE_THRESHOLD)) {
            done = true;
            return true;
        }

        // Walk toward owner (offset 1 block to avoid overlap).
        g.moveTo(ownerPos.offset(1, 0, 0));
        return false;
    }

    @Override
    public String status() {
        return done ? "arrived at owner" : "coming to owner";
    }

    @Override
    public void pause() {}

    @Override
    public void resume() {}
}
