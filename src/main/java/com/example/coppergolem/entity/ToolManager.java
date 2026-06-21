package com.example.coppergolem.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Map;

/**
 * Tool-sourcing logic for the copper golem.
 *
 * <p>Sourcing order (ensureTool):
 * <ol>
 *   <li>Already have a matching tool in inventory → equip it.</li>
 *   <li>Find the tool in nearby chests → pull and equip.</li>
 *   <li>Have craft materials in inventory → craft and equip.</li>
 *   <li>Gather materials (mine cobble or chop tree) then craft.</li>
 *   <li>Return false — caller fails the task.</li>
 * </ol>
 * </p>
 */
public class ToolManager {

    public enum ToolKind { PICKAXE, AXE }

    private static final int CHEST_SEARCH_RADIUS = 16;
    private static final int TREE_SEARCH_RADIUS  = 12;
    private static final int GATHER_ATTEMPTS      = 3;

    private final GolemPrimitives g;

    public ToolManager(GolemPrimitives g) {
        this.g = g;
    }

    // -------------------------------------------------------------------------
    // Primary API
    // -------------------------------------------------------------------------

    /**
     * Ensure the golem has a working tool of {@code kind} as its active tool.
     *
     * @return true once a usable tool is equipped; false if all sourcing steps fail
     */
    public boolean ensureTool(ToolKind kind) {
        // Step 1 — already in inventory
        if (equipFromInventory(kind)) {
            return true;
        }

        // Step 2 — search nearby chests
        if (pullFromChests(kind)) {
            return true;
        }

        // Step 3 — craft from existing materials
        String toolId = idFor(kind);
        if (g.hasCraftMaterials(toolId) && g.craftTool(toolId)) {
            return equipFromInventory(kind);
        }

        // Step 4 — gather materials then craft
        if (gatherMaterials(kind) && g.hasCraftMaterials(toolId) && g.craftTool(toolId)) {
            return equipFromInventory(kind);
        }

        // Step 5 — give up
        return false;
    }

    /**
     * If the active tool is near breaking, proactively source a replacement.
     *
     * @param kind   the tool type to replace with
     * @param margin remaining-durability threshold that triggers replacement
     */
    public void maybeReplaceBeforeBreak(ToolKind kind, int margin) {
        if (g.inventory().activeToolNearBreaking(margin)) {
            ensureTool(kind);
        }
    }

    /**
     * Find or craft up to {@code count} spare tools of {@code kind} and push
     * them into the nearest available chest for later use.
     *
     * @param kind  tool type to stock
     * @param count target number of spares
     */
    public void stockSpares(ToolKind kind, int count) {
        String toolId = idFor(kind);
        List<BlockPos> chests = g.findChests(CHEST_SEARCH_RADIUS);
        if (chests.isEmpty()) {
            return;
        }
        BlockPos storage = chests.get(0);

        for (int i = 0; i < count; i++) {
            // Try to craft one spare at a time
            if (!g.hasCraftMaterials(toolId)) {
                // Attempt a single gather pass for one more spare
                gatherMaterials(kind);
            }
            if (g.hasCraftMaterials(toolId) && g.craftTool(toolId)) {
                // Freshly crafted tool lands in inventory; push it to storage.
                // We locate it via inventory search and push the item type.
                int slot = findToolSlot(kind);
                if (slot >= 0) {
                    Item toolItem = g.inventory().getItem(slot).getItem();
                    g.pushToChest(storage, toolItem, 1);
                }
            } else {
                break; // No materials available — stop early
            }
        }
    }

    // -------------------------------------------------------------------------
    // Tool-ID helper
    // -------------------------------------------------------------------------

    /**
     * Returns the Minecraft item ID for the preferred tier of {@code kind}.
     * Defaults to wooden tools. Stone tier requires detection logic that is
     * deferred; callers may override by substituting a smarter implementation.
     *
     * @return namespaced item id without "minecraft:" prefix
     */
    public static String idFor(ToolKind kind) {
        return switch (kind) {
            case PICKAXE -> "wooden_pickaxe";
            case AXE     -> "wooden_axe";
        };
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Step 1: equip a matching tool already sitting in the inventory. */
    private boolean equipFromInventory(ToolKind kind) {
        int slot = findToolSlot(kind);
        if (slot < 0) {
            return false;
        }
        g.inventory().equipFromSlot(slot);
        return g.equipTool(g.inventory().activeTool());
    }

    /** Step 2: search nearby chests and pull the first matching tool. */
    private boolean pullFromChests(ToolKind kind) {
        String toolId = idFor(kind);
        List<BlockPos> chests = g.findChests(CHEST_SEARCH_RADIUS);
        for (BlockPos chest : chests) {
            Map<Item, Integer> contents = g.readChest(chest);
            for (Map.Entry<Item, Integer> entry : contents.entrySet()) {
                // Match by item registry name suffix (e.g. "wooden_pickaxe")
                String itemName = entry.getKey().getDescriptionId();
                if (itemName.endsWith(toolId)) {
                    int pulled = g.pullFromChest(chest, entry.getKey(), 1);
                    if (pulled > 0) {
                        return equipFromInventory(kind);
                    }
                }
            }
        }
        return false;
    }

    /**
     * Step 4: gather raw materials — mine cobble (pickaxe) or chop a tree (axe).
     * Performs up to {@code GATHER_ATTEMPTS} block breaks.
     */
    private boolean gatherMaterials(ToolKind kind) {
        if (kind == ToolKind.PICKAXE) {
            return gatherCobble();
        } else {
            return gatherWood();
        }
    }

    private boolean gatherCobble() {
        // Try to find and mine stone blocks near the golem
        BlockPos base = g.position();
        int gathered = 0;
        for (int dx = -3; dx <= 3 && gathered < GATHER_ATTEMPTS; dx++) {
            for (int dy = -1; dy <= 1 && gathered < GATHER_ATTEMPTS; dy++) {
                for (int dz = -3; dz <= 3 && gathered < GATHER_ATTEMPTS; dz++) {
                    BlockPos candidate = base.offset(dx, dy, dz);
                    if (g.mineBlock(candidate)) {
                        gathered++;
                        g.pickupNearbyItems(3);
                    }
                }
            }
        }
        return gathered > 0;
    }

    private boolean gatherWood() {
        List<BlockPos> trees = g.findTreeBases(TREE_SEARCH_RADIUS);
        int gathered = 0;
        for (BlockPos tree : trees) {
            if (gathered >= GATHER_ATTEMPTS) break;
            // Mine the base log block of each tree
            if (g.moveTo(tree) && g.mineBlock(tree)) {
                g.pickupNearbyItems(3);
                gathered++;
            }
        }
        return gathered > 0;
    }

    /** Returns the inventory slot index of the first matching tool, or -1. */
    private int findToolSlot(ToolKind kind) {
        return switch (kind) {
            case PICKAXE -> g.inventory().findPickaxeSlot();
            case AXE     -> g.inventory().findAxeSlot();
        };
    }
}
