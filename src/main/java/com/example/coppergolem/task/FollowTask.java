package com.example.coppergolem.task;

import com.example.coppergolem.entity.GolemPrimitives;
import net.minecraft.core.BlockPos;

import java.util.function.Supplier;

/**
 * Continuously follows the owner player until stopped externally.
 * Never returns {@code true} from {@link #tick} — runs until {@link GolemController#stopFollow()}
 * is called (which invokes {@link GolemController#stop()}).
 *
 * <p>Each tick resolves the owner's current position via {@code ownerPosSupplier} and
 * navigates the golem to a position 2 blocks offset from the owner so they don't overlap.</p>
 */
public final class FollowTask implements TaskHandler {

    private final Supplier<BlockPos> ownerPosSupplier;

    /**
     * @param ownerPosSupplier supplier resolved each tick to the owner's current block position;
     *                         may return {@code null} if the owner is offline.
     */
    public FollowTask(Supplier<BlockPos> ownerPosSupplier) {
        this.ownerPosSupplier = ownerPosSupplier;
    }

    @Override
    public boolean tick(GolemPrimitives g) {
        BlockPos ownerPos = ownerPosSupplier.get();
        if (ownerPos == null) {
            // Owner offline — stay put, keep running.
            return false;
        }
        // Walk to 2 blocks east of the owner to avoid overlap.
        g.moveTo(ownerPos.offset(2, 0, 0));
        // Never complete — keep running until stopped externally.
        return false;
    }

    @Override
    public String status() {
        return "following owner";
    }

    @Override
    public void pause() {}

    @Override
    public void resume() {}
}
