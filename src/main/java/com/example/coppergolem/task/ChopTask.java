package com.example.coppergolem.task;

import com.example.coppergolem.entity.GolemPrimitives;
import com.example.coppergolem.entity.ToolManager;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;

public final class ChopTask implements TaskHandler {

    private final Task.Chop spec;
    private final Supplier<List<BlockPos>> findTreeBases;
    private final ToolManager toolManager;
    private final Deque<BlockPos> currentTrunk = new ArrayDeque<>();
    private int chopped;
    private boolean paused;
    private boolean started;
    private String failed;

    public ChopTask(Task.Chop spec, Supplier<List<BlockPos>> findTreeBases, ToolManager toolManager) {
        this.spec = spec;
        this.findTreeBases = findTreeBases;
        this.toolManager = toolManager;
    }

    @Override
    public boolean tick(GolemPrimitives g) {
        if (failed != null) return true; // done-failed

        // --- Task START: first tick only ---
        if (!started) {
            started = true;
            if (!toolManager.ensureTool(ToolManager.ToolKind.AXE)) {
                failed = "no axe and no materials";
                return true;
            }
        }

        if (paused) return false;

        if (currentTrunk.isEmpty()) {
            List<BlockPos> bases = findTreeBases.get();
            if (bases.isEmpty()) return true; // no trees left -> done
            BlockPos base = bases.get(0);
            for (int y = 0; y < 12; y++) currentTrunk.add(base.above(y)); // up to 12 logs
        }

        BlockPos log = currentTrunk.peek();
        if (!g.moveTo(log)) return false;

        boolean mined = g.mineBlock(log);
        if (mined) {
            chopped++;
            toolManager.maybeReplaceBeforeBreak(ToolManager.ToolKind.AXE, 5);
        }
        g.pickupNearbyItems(3);
        currentTrunk.poll();
        return false;
    }

    @Override
    public String status() {
        if (failed != null) return "Chopping failed: " + failed;
        return "Chopping: " + chopped + " logs";
    }

    @Override
    public void pause()  { paused = true; }

    @Override
    public void resume() { paused = false; }
}
