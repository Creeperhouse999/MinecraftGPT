package com.example.coppergolem.craft;

import com.example.coppergolem.entity.ApprovalGate;
import com.example.coppergolem.entity.GolemPrimitives;
import com.example.coppergolem.inventory.GolemInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Crafting facade for the copper golem.
 *
 * <p>Supports 2×2 inventory crafting (no table needed) and 3×3 crafting-table
 * recipes. For 3×3 recipes the helper first looks for a nearby crafting_table
 * block (scan radius {@value #TABLE_SEARCH_RADIUS}); if none is found it crafts
 * and places one from inventory materials.</p>
 *
 * <p>Gate policy (where {@link ApprovalGate#request} is called):
 * <ul>
 *   <li>Tool recipes (any output id ending in {@code _pickaxe} or {@code _axe}).</li>
 *   <li>Placing a crafting table from inventory.</li>
 * </ul>
 * Planks, sticks, torches, and the crafting_table item itself (when crafted as
 * intermediate inputs) do NOT require gate approval.</p>
 *
 * <p>MC 26.2 / Mojang-mappings: item lookup uses
 * {@code BuiltInRegistries.ITEM.getKey(item)} → {@link Identifier},
 * and {@code BuiltInRegistries.ITEM.getValue(Identifier.parse(str))} for the
 * reverse direction.</p>
 */
public final class CraftingHelper {

    /** Block radius to scan for an existing crafting table block. */
    private static final int TABLE_SEARCH_RADIUS = 8;

    private final GolemPrimitives g;
    private final ApprovalGate gate;

    public CraftingHelper(GolemPrimitives g, ApprovalGate gate) {
        this.g    = g;
        this.gate = gate;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns true when the golem's inventory contains all inputs required by
     * the recipe for {@code outputId}.
     *
     * @param outputId namespaced item id, e.g. {@code "minecraft:wooden_pickaxe"}
     */
    public boolean canCraft(String outputId) {
        Recipes.Recipe recipe = Recipes.get(outputId);
        if (recipe == null) return false;
        return hasInputs(recipe.inputs);
    }

    /**
     * Attempt to craft {@code count} units of {@code outputId}.
     *
     * <p>Steps:
     * <ol>
     *   <li>Look up recipe; fail fast if unknown.</li>
     *   <li>Craft intermediate ingredients that are missing (planks, sticks) if
     *       the sub-recipe exists and we have the raw materials for it.</li>
     *   <li>If the recipe needs a table, locate or place one.</li>
     *   <li>Ask {@link ApprovalGate} for tool/table-place operations.</li>
     *   <li>Consume inputs from inventory; inject outputs.</li>
     * </ol>
     * </p>
     *
     * @param outputId namespaced item id
     * @param count    number of items desired (rounded up to whole craft-batches)
     * @return true on success
     */
    public boolean craft(String outputId, int count) {
        Recipes.Recipe recipe = Recipes.get(outputId);
        if (recipe == null) return false;

        // How many craft operations do we need?
        int batchesNeeded = (count + recipe.outputCount - 1) / recipe.outputCount;

        for (int batch = 0; batch < batchesNeeded; batch++) {
            if (!craftOnce(recipe)) return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Execute a single craft of {@code recipe}. */
    private boolean craftOnce(Recipes.Recipe recipe) {
        // Ensure we have all inputs (craft intermediates if needed)
        if (!ensureInputs(recipe.inputs)) return false;

        // Gate check for tool and table-place operations
        if (requiresGate(recipe.outputId)) {
            if (!gate.request("craft " + recipe.outputId)) return false;
        }

        // For 3x3 recipes we need a crafting table
        if (recipe.needsTable) {
            if (!ensureCraftingTable()) return false;
        }

        // Consume inputs
        if (!consumeInputs(recipe.inputs)) return false;

        // Produce output
        addToInventory(recipe.outputId, recipe.outputCount);
        return true;
    }

    /**
     * Try to satisfy all required inputs. For missing items that have a known
     * sub-recipe (planks from logs, sticks from planks), recursively craft them.
     */
    private boolean ensureInputs(Map<String, Integer> inputs) {
        for (Map.Entry<String, Integer> entry : inputs.entrySet()) {
            String inputId = entry.getKey();
            int needed = entry.getValue();
            int have = countInInventory(inputId);
            if (have >= needed) continue;

            // Try to craft the missing input
            int deficit = needed - have;
            Recipes.Recipe subRecipe = Recipes.get(inputId);
            if (subRecipe == null) return false; // no sub-recipe and not in inventory

            // Craft enough batches to cover the deficit
            int batches = (deficit + subRecipe.outputCount - 1) / subRecipe.outputCount;
            for (int i = 0; i < batches; i++) {
                if (!craftOnce(subRecipe)) return false;
            }

            // Check again after crafting
            if (countInInventory(inputId) < needed) return false;
        }
        return true;
    }

    /**
     * Returns true when a crafting_table block is accessible within
     * {@value #TABLE_SEARCH_RADIUS} blocks. If not found, attempts to craft and
     * place one, asking the gate first.
     */
    private boolean ensureCraftingTable() {
        // Search for an existing crafting_table in the world
        BlockPos tablePos = findCraftingTableNearby();
        if (tablePos != null) {
            return true; // existing table found — no placement needed
        }

        // Need to place our own — check we can make one (or already have one)
        if (countInInventory("minecraft:crafting_table") == 0) {
            // Try to craft a crafting_table first (2×2 recipe, no gate)
            Recipes.Recipe tableRecipe = Recipes.get("minecraft:crafting_table");
            if (tableRecipe == null || !craftOnce(tableRecipe)) return false;
        }

        // Ask gate before placing
        if (!gate.request("place crafting_table")) return false;

        // Try all 4 cardinal neighbors for an air spot; fail only if none work (IMPORTANT-H).
        Item tableItem = itemForId("minecraft:crafting_table");
        if (tableItem == null) return false;

        BlockPos origin = g.position();
        for (Direction dir : new Direction[]{ Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST }) {
            BlockPos candidate = origin.relative(dir);
            String blockId = g.getBlockId(candidate);
            // Only place on air (empty) blocks to avoid griefing.
            if ("air".equals(blockId) || "minecraft:air".equals(blockId) || blockId == null || blockId.isEmpty()) {
                if (g.placeBlock(candidate, tableItem)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Scan a cube around the golem's position for a {@code crafting_table} block.
     *
     * @return the position of the first crafting_table found, or {@code null}
     */
    private BlockPos findCraftingTableNearby() {
        BlockPos origin = g.position();
        int r = TABLE_SEARCH_RADIUS;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -2; dy <= 4; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos candidate = origin.offset(dx, dy, dz);
                    String blockId = g.getBlockId(candidate);
                    // getBlockId returns the registry path only (e.g. "crafting_table")
                    if ("crafting_table".equals(blockId) || "minecraft:crafting_table".equals(blockId)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /** Returns true when the recipe output is a tool (requires gate). */
    private static boolean requiresGate(String outputId) {
        return outputId.endsWith("_pickaxe")
            || outputId.endsWith("_axe")
            || outputId.endsWith("_sword")
            || outputId.endsWith("_shovel")
            || outputId.endsWith("_hoe");
    }

    // -------------------------------------------------------------------------
    // Inventory helpers
    // -------------------------------------------------------------------------

    /** Count how many of {@code itemId} are in the golem's storage slots. */
    private int countInInventory(String itemId) {
        GolemInventory inv = g.inventory();
        int total = 0;
        for (int i = 0; i < GolemInventory.STORAGE_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key != null && (key.toString().equals(itemId) || key.getPath().equals(itemId))) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Check that inventory contains all required inputs in sufficient quantity. */
    private boolean hasInputs(Map<String, Integer> inputs) {
        for (Map.Entry<String, Integer> entry : inputs.entrySet()) {
            if (countInInventory(entry.getKey()) < entry.getValue()) return false;
        }
        return true;
    }

    /**
     * Remove the required input quantities from the golem inventory.
     * Returns true if all were successfully consumed; false if an item was missing
     * (indicates a logic error — caller should have checked first).
     */
    private boolean consumeInputs(Map<String, Integer> inputs) {
        GolemInventory inv = g.inventory();
        // Collect what to remove: build a working copy of required amounts
        Map<String, Integer> remaining = new HashMap<>(inputs);

        for (int i = 0; i < GolemInventory.STORAGE_SIZE && !remaining.isEmpty(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;

            Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key == null) continue;

            String id = key.toString();
            String path = key.getPath();

            // Match by full id or path
            Integer needed = remaining.get(id);
            if (needed == null) needed = remaining.get(path);
            if (needed == null) continue;

            int consume = Math.min(needed, stack.getCount());
            inv.removeItem(i, consume);
            int leftover = needed - consume;
            if (leftover <= 0) {
                remaining.remove(id);
                remaining.remove(path);
            } else {
                // Update whichever key was present
                if (remaining.containsKey(id)) remaining.put(id, leftover);
                else remaining.put(path, leftover);
            }
        }
        return remaining.isEmpty();
    }

    /**
     * Add {@code count} of {@code itemId} to the golem inventory by finding an
     * empty or partially-filled slot.
     */
    private void addToInventory(String itemId, int count) {
        Item item = itemForId(itemId);
        if (item == null) return;

        GolemInventory inv = g.inventory();
        int leftover = count;

        // First pass: top up existing stacks
        for (int i = 0; i < GolemInventory.STORAGE_SIZE && leftover > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key == null) continue;
            if (!key.toString().equals(itemId) && !key.getPath().equals(itemId)) continue;
            int space = stack.getItem().getDefaultMaxStackSize() - stack.getCount();
            if (space <= 0) continue;
            int add = Math.min(space, leftover);
            stack.grow(add);
            leftover -= add;
        }

        // Second pass: use empty slots
        for (int i = 0; i < GolemInventory.STORAGE_SIZE && leftover > 0; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) continue;
            int add = Math.min(item.getDefaultMaxStackSize(), leftover);
            inv.setItem(i, new ItemStack(item, add));
            leftover -= add;
        }
    }

    /** Resolve a namespaced item id to its {@link Item} instance. */
    private static Item itemForId(String itemId) {
        try {
            return BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        } catch (Exception e) {
            return null;
        }
    }
}
