package com.example.coppergolem.mine;

import java.util.Map;
import java.util.Optional;

public class Ores {
    public enum Tier {
        WOOD, STONE, IRON, DIAMOND, NETHERITE
    }

    public record OreInfo(String blockId, Tier minTier, int defaultY) {}

    private static final Map<String, OreInfo> ORE_TABLE = Map.ofEntries(
        Map.entry("coal_ore", new OreInfo("coal_ore", Tier.WOOD, 50)),
        Map.entry("deepslate_coal_ore", new OreInfo("deepslate_coal_ore", Tier.WOOD, 50)),

        Map.entry("copper_ore", new OreInfo("copper_ore", Tier.STONE, 48)),
        Map.entry("deepslate_copper_ore", new OreInfo("deepslate_copper_ore", Tier.STONE, 48)),

        Map.entry("iron_ore", new OreInfo("iron_ore", Tier.STONE, 16)),
        Map.entry("deepslate_iron_ore", new OreInfo("deepslate_iron_ore", Tier.STONE, 16)),

        Map.entry("lapis_ore", new OreInfo("lapis_ore", Tier.STONE, 0)),
        Map.entry("deepslate_lapis_ore", new OreInfo("deepslate_lapis_ore", Tier.STONE, 0)),

        Map.entry("redstone_ore", new OreInfo("redstone_ore", Tier.IRON, -58)),
        Map.entry("deepslate_redstone_ore", new OreInfo("deepslate_redstone_ore", Tier.IRON, -58)),

        Map.entry("gold_ore", new OreInfo("gold_ore", Tier.IRON, -16)),
        Map.entry("deepslate_gold_ore", new OreInfo("deepslate_gold_ore", Tier.IRON, -16)),

        Map.entry("diamond_ore", new OreInfo("diamond_ore", Tier.IRON, -58)),
        Map.entry("deepslate_diamond_ore", new OreInfo("deepslate_diamond_ore", Tier.IRON, -58)),

        Map.entry("emerald_ore", new OreInfo("emerald_ore", Tier.IRON, 230)),
        Map.entry("deepslate_emerald_ore", new OreInfo("deepslate_emerald_ore", Tier.IRON, 230))
    );

    /**
     * Lookup ore by name, accepting "diamond", "diamond_ore", "minecraft:diamond_ore".
     */
    public static Optional<OreInfo> byName(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }

        // Strip namespace if present (minecraft:)
        String stripped = name;
        if (stripped.contains(":")) {
            stripped = stripped.substring(stripped.indexOf(":") + 1);
        }

        // If it doesn't end with _ore, append it
        if (!stripped.endsWith("_ore")) {
            stripped = stripped + "_ore";
        }

        return Optional.ofNullable(ORE_TABLE.get(stripped));
    }

    /**
     * Check if a block ID is a known ore (including deepslate variants).
     */
    public static boolean isOre(String blockId) {
        if (blockId == null || blockId.isEmpty()) {
            return false;
        }

        // Strip namespace if present
        String stripped = blockId;
        if (stripped.contains(":")) {
            stripped = stripped.substring(stripped.indexOf(":") + 1);
        }

        return ORE_TABLE.containsKey(stripped);
    }

    /**
     * Get the tier required to use a pickaxe.
     */
    public static Tier tierOf(String pickaxeId) {
        if (pickaxeId == null || pickaxeId.isEmpty()) {
            return Tier.WOOD;
        }

        return switch (pickaxeId) {
            case "wooden_pickaxe" -> Tier.WOOD;
            case "stone_pickaxe" -> Tier.STONE;
            case "iron_pickaxe" -> Tier.IRON;
            case "diamond_pickaxe" -> Tier.DIAMOND;
            case "netherite_pickaxe" -> Tier.NETHERITE;
            default -> Tier.WOOD;
        };
    }

    /**
     * Check if a pickaxe can mine an ore (tier comparison).
     */
    public static boolean canMine(String oreBlockId, String pickaxeId) {
        Optional<OreInfo> oreInfo = byName(oreBlockId);
        if (oreInfo.isEmpty()) {
            return false;
        }

        Tier pickaxeTier = tierOf(pickaxeId);
        Tier minTier = oreInfo.get().minTier();

        return pickaxeTier.ordinal() >= minTier.ordinal();
    }
}
