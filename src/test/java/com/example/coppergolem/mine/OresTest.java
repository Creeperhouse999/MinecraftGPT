package com.example.coppergolem.mine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OresTest {
    @Test
    void tierGatingAndLookup() {
        assertTrue(Ores.byName("diamond").isPresent());
        assertEquals(Ores.Tier.IRON, Ores.byName("diamond_ore").orElseThrow().minTier());
        assertTrue(Ores.isOre("minecraft:coal_ore"));
        assertTrue(Ores.isOre("minecraft:deepslate_diamond_ore"));
        assertFalse(Ores.isOre("minecraft:stone"));
        // diamond needs iron+; stone pick can't, iron pick can
        assertFalse(Ores.canMine("minecraft:diamond_ore", "stone_pickaxe"));
        assertTrue(Ores.canMine("minecraft:diamond_ore", "iron_pickaxe"));
        assertTrue(Ores.canMine("minecraft:coal_ore", "wooden_pickaxe"));
    }
}
