package com.example.coppergolem.task;

import com.example.coppergolem.entity.GolemPrimitives;
import com.example.coppergolem.entity.ToolManager;
import com.example.coppergolem.inventory.GolemInventory;
import com.example.coppergolem.mine.Ores;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.List;

/**
 * Strip-mines at an ore's preferred Y level, collecting target ore (and
 * incidental mineable ores) until {@code count} specimens are collected or
 * a step cap is reached.
 *
 * <p>Tool acquisition: calls {@link ToolManager#ensureTool(ToolManager.ToolKind)}
 * to get ANY pickaxe, then checks tier via {@link Ores#canMine}. If the golem
 * cannot mine the target ore with its current pickaxe, the task fails immediately
 * with "need <tier> pickaxe". Tier-aware acquisition is deferred to task C3.
 * TODO(C3): replace ensureTool(PICKAXE) with a tier-aware method that will
 *   acquire an iron+ pickaxe if needed.</p>
 */
public final class OreHuntTask implements TaskHandler {

    /** Maximum number of block-mining steps before giving up. */
    private static final int STEP_CAP = 2000;

    /** Tunnel branch half-length: dig this many blocks forward per branch. */
    private static final int BRANCH_LENGTH = 32;

    /** Spacing between branch tunnels (perpendicular to main axis). */
    private static final int BRANCH_SPACING = 3;

    private final String ore;
    private final int count;
    private final ToolManager tools;

    private boolean paused;
    private boolean started;
    private String failed;

    /** Number of target ore blocks collected so far. */
    private int collected;

    /** Total mining steps taken (block-break attempts). */
    private int steps;

    // Strip-mine state machine
    private int targetY;
    private String oreBlockId;   // canonical ore block id, e.g. "diamond_ore"
    private String activePickId; // plain id, e.g. "iron_pickaxe"

    // Branch layout: main tunnel runs along X, branches along Z.
    private int branchIndex;     // which branch we're on
    private int branchStep;      // how many blocks we've gone on this branch
    private boolean branchForward; // true = going forward (positive Z), false = done

    // Starting position of the current branch (at mainTunnel x offset)
    private BlockPos branchStart;
    // X position along main tunnel
    private int mainX;
    // Origin set on first tick
    private BlockPos origin;

    public OreHuntTask(String ore, int count, ToolManager tools, GolemPrimitives g) {
        this.ore = ore;
        this.count = count;
        this.tools = tools;
        // g is not stored; we receive it each tick
    }

    @Override
    public boolean tick(GolemPrimitives g) {
        if (failed != null) return true;
        if (collected >= count) return true;

        // ---- START: first tick only ----
        if (!started) {
            started = true;

            // Resolve ore
            var infoOpt = Ores.byName(ore);
            if (infoOpt.isEmpty()) {
                failed = "unknown ore: " + ore;
                return true;
            }
            Ores.OreInfo info = infoOpt.get();
            targetY = info.defaultY();
            // Canonical ore block id (strip "deepslate_" variant not needed for lookup)
            oreBlockId = info.blockId();

            // Ensure a pickaxe of at least the tier required by this ore.
            if (!tools.ensurePickaxeOfTier(info.minTier())) {
                failed = "need " + info.minTier().name().toLowerCase() + " pickaxe";
                return true;
            }

            // Record origin and initialise strip-mine state
            origin = g.position();
            branchStart = new BlockPos(origin.getX(), targetY, origin.getZ());
            mainX = 0;
            branchIndex = 0;
            branchStep = 0;
            branchForward = true;
        }

        if (paused) return false;

        // ---- step cap ----
        if (steps >= STEP_CAP) return true;

        // ---- inventory-full: dump to nearest chest ----
        if (g.inventory().isStorageFull()) {
            List<BlockPos> chests = g.findChests(16);
            if (!chests.isEmpty()) {
                dumpAll(g, chests.get(0));
            }
            return false;
        }

        // ---- strip-mine one step ----
        stripMineStep(g);

        // Refresh pick id each tick (tool may have been replaced)
        activePickId = resolveActivePickId(g);
        tools.maybeReplaceBeforeBreak(ToolManager.ToolKind.PICKAXE, 5);

        return collected >= count;
    }

    /**
     * Advance the strip-mine state machine by one block.
     * Layout: branches spaced BRANCH_SPACING apart along Z, each BRANCH_LENGTH long,
     * spreading away from the origin X.
     */
    private void stripMineStep(GolemPrimitives g) {
        if (branchStep < BRANCH_LENGTH) {
            // Dig forward along Z (positive direction)
            BlockPos target = new BlockPos(
                    branchStart.getX() + mainX,
                    targetY,
                    branchStart.getZ() + (branchForward ? branchStep + 1 : -(branchStep + 1))
            );
            mineAndCollect(g, target);
            branchStep++;
        } else {
            // Branch exhausted; advance to next branch
            branchIndex++;
            mainX = branchIndex * BRANCH_SPACING;
            branchStep = 0;
            branchForward = (branchIndex % 2 == 0); // alternate direction
            // Move to new branch start
            BlockPos newStart = new BlockPos(branchStart.getX() + mainX, targetY, branchStart.getZ());
            g.moveTo(newStart);
        }
    }

    /**
     * Move to {@code pos}, mine it, collect drops, then check 6 neighbors for
     * incidental ore.
     */
    private void mineAndCollect(GolemPrimitives g, BlockPos pos) {
        if (!g.moveTo(pos)) return;

        String blockId = g.getBlockId(pos);
        boolean isTarget = Ores.isOre(blockId) && isTargetOre(blockId);
        boolean mined = g.mineBlock(pos);
        steps++;

        if (mined && isTarget) {
            collected++;
        }

        if (mined) {
            // Collect incidental ores from 6 neighbors
            for (Direction face : Direction.values()) {
                BlockPos neighbor = pos.relative(face);
                String neighborId = g.getBlockId(neighbor);
                if (Ores.isOre(neighborId) && Ores.canMine(neighborId, activePickId)) {
                    boolean nbMined = g.mineBlock(neighbor);
                    steps++;
                    if (nbMined && isTargetOre(neighborId)) {
                        collected++;
                    }
                }
            }
            g.pickupNearbyItems(2);
        }
    }

    /** Returns true if the given block id represents the target ore (normal or deepslate). */
    private boolean isTargetOre(String blockId) {
        if (blockId == null) return false;
        String stripped = blockId.contains(":") ? blockId.substring(blockId.indexOf(":") + 1) : blockId;
        // Accept exact match or deepslate variant
        return stripped.equals(oreBlockId) || stripped.equals("deepslate_" + oreBlockId);
    }

    /** Push all non-empty storage slots (hotbar+main only) into the given chest (MINOR-J). */
    private void dumpAll(GolemPrimitives g, BlockPos chest) {
        var inv = g.inventory();
        // STORAGE_SIZE = 36: hotbar[0..8] + main[9..35]; excludes armor[36-39] + offhand[40].
        for (int i = 0; i < GolemInventory.STORAGE_SIZE; i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                g.pushToChest(chest, stack.getItem(), stack.getCount());
            }
        }
    }

    /** Resolve the active pickaxe's plain registry id (e.g. "iron_pickaxe"). */
    private static String resolveActivePickId(GolemPrimitives g) {
        var activeTool = g.inventory().activeTool();
        if (activeTool.isEmpty()) return "";
        var key = BuiltInRegistries.ITEM.getKey(activeTool.getItem());
        if (key == null) return "";
        String id = key.toString();
        return id.contains(":") ? id.substring(id.indexOf(":") + 1) : id;
    }

    @Override
    public String status() {
        if (failed != null) return "Ore hunt " + ore + " failed: " + failed;
        return "Ore hunt " + ore + ": " + collected + "/" + count;
    }

    @Override
    public void pause()  { paused = true; }

    @Override
    public void resume() { paused = false; }
}
