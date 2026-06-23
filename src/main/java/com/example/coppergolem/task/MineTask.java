package com.example.coppergolem.task;

import com.example.coppergolem.entity.GolemPrimitives;
import com.example.coppergolem.entity.ToolManager;
import com.example.coppergolem.mine.Ores;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public final class MineTask implements TaskHandler {

    private final Task.Mine spec;
    private final ToolManager toolManager;
    private final Deque<BlockPos> cells = new ArrayDeque<>();
    private final int total;
    private boolean paused;
    private boolean started;
    private String failed;

    public MineTask(Task.Mine spec, BlockPos origin, ToolManager toolManager) {
        this.spec = spec;
        this.toolManager = toolManager;

        Direction d = switch (spec.dir().toLowerCase()) {
            case "south" -> Direction.SOUTH;
            case "east"  -> Direction.EAST;
            case "west"  -> Direction.WEST;
            default      -> Direction.NORTH;
        };

        // Build cell list: length planes of w x h blocks along travel direction.
        // Width (w) is spread perpendicular to dir; height (h) goes upward.
        // offW centres the width around origin: offW in [-w/2 .. w-1-w/2].
        for (int l = 1; l <= spec.length(); l++) {
            for (int hi = 0; hi < spec.h(); hi++) {
                for (int wi = 0; wi < spec.w(); wi++) {
                    int offW = wi - spec.w() / 2;
                    // Step 'l' blocks along travel dir, 'hi' blocks up
                    BlockPos base = origin.relative(d, l).above(hi);
                    // Spread perpendicular: Z-axis travel → spread east/west;
                    //                       X-axis travel → spread north/south
                    BlockPos cell = (d.getAxis() == Direction.Axis.Z)
                            ? base.east(offW)
                            : base.south(offW);
                    cells.add(cell);
                }
            }
        }
        this.total = cells.size();
    }

    @Override
    public boolean tick(GolemPrimitives g) {
        if (failed != null) return true; // done-failed

        // --- Task START: first tick only ---
        if (!started) {
            started = true;
            if (!toolManager.ensureTool(ToolManager.ToolKind.PICKAXE)) {
                failed = "no pickaxe and no materials";
                return true;
            }
            long estBlocks = (long) spec.w() * spec.h() * spec.length();
            toolManager.stockSpares(ToolManager.ToolKind.PICKAXE,
                    (int) Math.max(0, estBlocks / 120));
        }

        if (paused || cells.isEmpty()) return cells.isEmpty();

        // Inventory-full handling: dump to nearest chest then continue next tick
        if (g.inventory().isStorageFull()) {
            List<BlockPos> chests = g.findChests(16);
            if (!chests.isEmpty()) {
                dumpAll(g, chests.get(0));
            }
            return false;
        }

        BlockPos cell = cells.peek();
        if (!g.moveTo(cell)) return false; // still walking

        boolean mined = g.mineBlock(cell); // filter handled in primitive impl
        cells.poll();

        if (mined) {
            // Check 6 neighbors for incidental ore collection.
            // Determine active pickaxe id from the golem's active tool.
            String activePickId = "";
            var activeTool = g.inventory().activeTool();
            if (!activeTool.isEmpty()) {
                var key = BuiltInRegistries.ITEM.getKey(activeTool.getItem());
                if (key != null) {
                    activePickId = key.toString();
                    // Strip namespace to get plain id for Ores.tierOf
                    if (activePickId.contains(":")) {
                        activePickId = activePickId.substring(activePickId.indexOf(":") + 1);
                    }
                }
            }
            for (Direction face : Direction.values()) {
                BlockPos neighbor = cell.relative(face);
                String neighborId = g.getBlockId(neighbor);
                if (Ores.isOre(neighborId) && Ores.canMine(neighborId, activePickId)) {
                    // mineBlock already refuses protected zones internally
                    g.mineBlock(neighbor);
                }
            }
            g.pickupNearbyItems(2);
            toolManager.maybeReplaceBeforeBreak(ToolManager.ToolKind.PICKAXE, 5);
        } else {
            g.pickupNearbyItems(2);
        }

        return cells.isEmpty();
    }

    /** Push all non-empty storage slots into the given chest. */
    private void dumpAll(GolemPrimitives g, BlockPos chest) {
        var inv = g.inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                g.pushToChest(chest, stack.getItem(), stack.getCount());
            }
        }
    }

    @Override
    public String status() {
        if (failed != null) return "Mining failed: " + failed;
        return "Mining: " + (total - cells.size()) + "/" + total;
    }

    @Override
    public void pause()  { paused = true; }

    @Override
    public void resume() { paused = false; }
}
