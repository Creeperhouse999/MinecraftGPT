package com.example.coppergolem.craft;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Static recipe table for the copper golem's crafting helper.
 *
 * <p>Each {@link Recipe} carries:
 * <ul>
 *   <li>{@code outputId}  — namespaced Minecraft item id of the result.</li>
 *   <li>{@code outputCount} — number of items produced per craft.</li>
 *   <li>{@code inputs}    — item-id to required-count map (consumed per craft).</li>
 *   <li>{@code needsTable} — true when the recipe requires a 3×3 crafting table.</li>
 * </ul>
 * </p>
 *
 * <p>MC 26.2 / Mojang-mappings note: this class is intentionally MC-free so it can
 * be read and reviewed without loading the game. {@link CraftingHelper} resolves item
 * ids at runtime via {@code BuiltInRegistries.ITEM}.</p>
 */
public final class Recipes {

    /** Immutable recipe descriptor. */
    public static final class Recipe {
        public final String outputId;
        public final int outputCount;
        public final Map<String, Integer> inputs;
        public final boolean needsTable;

        public Recipe(String outputId, int outputCount, Map<String, Integer> inputs, boolean needsTable) {
            this.outputId     = outputId;
            this.outputCount  = outputCount;
            this.inputs       = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
            this.needsTable   = needsTable;
        }
    }

    // -------------------------------------------------------------------------
    // Recipe table
    // -------------------------------------------------------------------------

    /** All registered recipes keyed by output item id. */
    private static final Map<String, Recipe> TABLE;

    static {
        Map<String, Recipe> t = new LinkedHashMap<>();

        // -- 2×2 recipes (needsTable = false) --

        // planks <- 1 log  (yields 4 planks)
        t.put("minecraft:oak_planks", new Recipe(
                "minecraft:oak_planks", 4,
                Map.of("minecraft:oak_log", 1),
                false));

        // stick <- 2 planks  (yields 4 sticks)
        t.put("minecraft:stick", new Recipe(
                "minecraft:stick", 4,
                Map.of("minecraft:oak_planks", 2),
                false));

        // torch <- 1 coal + 1 stick  (yields 4 torches)
        t.put("minecraft:torch", new Recipe(
                "minecraft:torch", 4,
                Map.of("minecraft:coal", 1, "minecraft:stick", 1),
                false));

        // crafting_table <- 4 planks  (yields 1 table)
        t.put("minecraft:crafting_table", new Recipe(
                "minecraft:crafting_table", 1,
                Map.of("minecraft:oak_planks", 4),
                false));

        // -- 3×3 recipes (needsTable = true) --

        // wooden_pickaxe <- 3 planks + 2 sticks
        t.put("minecraft:wooden_pickaxe", new Recipe(
                "minecraft:wooden_pickaxe", 1,
                Map.of("minecraft:oak_planks", 3, "minecraft:stick", 2),
                true));

        // wooden_axe <- 3 planks + 2 sticks
        t.put("minecraft:wooden_axe", new Recipe(
                "minecraft:wooden_axe", 1,
                Map.of("minecraft:oak_planks", 3, "minecraft:stick", 2),
                true));

        // stone_pickaxe <- 3 cobblestone + 2 sticks
        t.put("minecraft:stone_pickaxe", new Recipe(
                "minecraft:stone_pickaxe", 1,
                Map.of("minecraft:cobblestone", 3, "minecraft:stick", 2),
                true));

        // stone_axe <- 3 cobblestone + 2 sticks
        t.put("minecraft:stone_axe", new Recipe(
                "minecraft:stone_axe", 1,
                Map.of("minecraft:cobblestone", 3, "minecraft:stick", 2),
                true));

        TABLE = Collections.unmodifiableMap(t);
    }

    /** Returns the recipe for {@code outputId}, or {@code null} if unknown. */
    public static Recipe get(String outputId) {
        return TABLE.get(outputId);
    }

    /** Returns an unmodifiable view of all registered recipes. */
    public static Map<String, Recipe> all() {
        return TABLE;
    }

    private Recipes() {}
}
