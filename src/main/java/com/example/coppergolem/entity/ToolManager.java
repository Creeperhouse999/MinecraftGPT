package com.example.coppergolem.entity;

import com.example.coppergolem.craft.CraftingHelper;
import com.example.coppergolem.craft.Recipes;
import com.example.coppergolem.mine.Ores;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** Block registry paths that gatherCobble is permitted to mine. */
    private static final Set<String> STONE_BLOCK_IDS = Set.of(
            "stone",
            "cobblestone",
            "deepslate",
            "cobbled_deepslate"
    );

    private final GolemPrimitives g;
    private final ApprovalGate gate;
    private final CraftingHelper crafts;

    /**
     * Construct ToolManager sharing the controller's existing {@link CraftingHelper}.
     * This ensures gate routing is consistent (IMPORTANT-G).
     */
    public ToolManager(GolemPrimitives g, ApprovalGate gate, CraftingHelper crafts) {
        this.g = g;
        this.gate = gate;
        this.crafts = crafts;
    }

    /**
     * Convenience constructor that creates a new {@link CraftingHelper}.
     * Prefer the three-arg constructor from the controller to avoid duplicate helpers.
     */
    public ToolManager(GolemPrimitives g, ApprovalGate gate) {
        this(g, gate, new CraftingHelper(g, gate));
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
        if (g.hasCraftMaterials(toolId) && gate.request("craft " + toolId) && g.craftTool(toolId)) {
            return equipFromInventory(kind);
        }

        // Step 4 — gather materials then craft
        if (gatherMaterials(kind) && g.hasCraftMaterials(toolId) && gate.request("craft " + toolId) && g.craftTool(toolId)) {
            return equipFromInventory(kind);
        }

        // Step 5 — give up
        return false;
    }

    /**
     * Ensure the golem has a pickaxe of at least {@code minTier} as its active tool.
     *
     * <p>Sourcing order:
     * <ol>
     *   <li>Already in inventory — find any pickaxe whose tier >= minTier and equip it.</li>
     *   <li>Pull from nearby chests — scan for a pickaxe of adequate tier.</li>
     *   <li>Craft — try pickaxes from highest tier down to minTier if materials exist
     *       (uses {@link CraftingHelper} with gate approval).</li>
     *   <li>Return false — caller should fail the task.</li>
     * </ol>
     * </p>
     *
     * @param minTier the minimum {@link Ores.Tier} required
     * @return true once a pickaxe of adequate tier is equipped; false if unreachable
     */
    public boolean ensurePickaxeOfTier(Ores.Tier minTier) {
        // Step 1 — already in inventory
        if (equipPickaxeOfTierFromInventory(minTier)) {
            return true;
        }

        // Step 2 — search nearby chests
        if (pullPickaxeOfTierFromChests(minTier)) {
            return true;
        }

        // Step 3 — craft: attempt from best to worst tier starting at minTier
        String[] candidates;
        if (minTier.ordinal() <= Ores.Tier.IRON.ordinal()) {
            candidates = new String[]{"minecraft:diamond_pickaxe", "minecraft:iron_pickaxe"};
        } else if (minTier == Ores.Tier.DIAMOND) {
            candidates = new String[]{"minecraft:diamond_pickaxe"};
        } else {
            // NETHERITE — we can't craft netherite here, fall through
            candidates = new String[]{};
        }
        for (String pickaxeId : candidates) {
            Ores.Tier pickTier = Ores.tierOf(pickaxeId.contains(":") ? pickaxeId.substring(pickaxeId.indexOf(":") + 1) : pickaxeId);
            if (pickTier.ordinal() < minTier.ordinal()) continue;
            if (crafts.canCraft(pickaxeId) && crafts.craft(pickaxeId, 1)) {
                return equipPickaxeOfTierFromInventory(minTier);
            }
        }

        // Step 4 — give up
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
            if (g.hasCraftMaterials(toolId) && gate.request("craft " + toolId) && g.craftTool(toolId)) {
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
                    if (!gate.request("take " + toolId)) {
                        return false; // owner denied taking this tool
                    }
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
        // Only mine stone/cobblestone variants — never grief builds, chests, or ores.
        BlockPos base = g.position();
        int gathered = 0;
        for (int dx = -3; dx <= 3 && gathered < GATHER_ATTEMPTS; dx++) {
            for (int dy = -1; dy <= 1 && gathered < GATHER_ATTEMPTS; dy++) {
                for (int dz = -3; dz <= 3 && gathered < GATHER_ATTEMPTS; dz++) {
                    BlockPos candidate = base.offset(dx, dy, dz);
                    String blockId = g.getBlockId(candidate);
                    if (STONE_BLOCK_IDS.contains(blockId) && g.mineBlock(candidate)) {
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

    /**
     * Scan the inventory for a pickaxe with tier >= {@code minTier} and equip it.
     * Returns true on success.
     */
    private boolean equipPickaxeOfTierFromInventory(Ores.Tier minTier) {
        var inv = g.inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key == null) continue;
            String path = key.getPath();
            if (!path.endsWith("_pickaxe")) continue;
            Ores.Tier t = Ores.tierOf(path);
            if (t.ordinal() >= minTier.ordinal()) {
                inv.equipFromSlot(i);
                return g.equipTool(inv.activeTool());
            }
        }
        return false;
    }

    /**
     * Search nearby chests for a pickaxe with tier >= {@code minTier} and pull it.
     * Returns true on success.
     */
    private boolean pullPickaxeOfTierFromChests(Ores.Tier minTier) {
        List<BlockPos> chests = g.findChests(CHEST_SEARCH_RADIUS);
        for (BlockPos chest : chests) {
            Map<Item, Integer> contents = g.readChest(chest);
            for (Map.Entry<Item, Integer> entry : contents.entrySet()) {
                Identifier key = BuiltInRegistries.ITEM.getKey(entry.getKey());
                if (key == null) continue;
                String path = key.getPath();
                if (!path.endsWith("_pickaxe")) continue;
                Ores.Tier t = Ores.tierOf(path);
                if (t.ordinal() >= minTier.ordinal()) {
                    if (!gate.request("take " + path)) continue;
                    int pulled = g.pullFromChest(chest, entry.getKey(), 1);
                    if (pulled > 0 && equipPickaxeOfTierFromInventory(minTier)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
