package com.example.coppergolem.inventory;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * Golem inventory mirroring the player layout:
 * <pre>
 *   Hotbar  [0..8]   — 9 slots  (tool / quick-access)
 *   Main    [9..35]  — 27 slots (general storage)
 *   Armor  [36..39]  — 4 slots  (head/chest/legs/feet)
 *   Offhand [40]     — 1 slot
 *   Total           = 41 slots
 * </pre>
 *
 * <p>"Storage" (for {@link #isStorageFull} and tool search) is hotbar+main = slots [0..35]
 * (36 slots). Armor and offhand are excluded from general storage logic.</p>
 *
 * <p>Tool detection uses MC 26.2 item tags ({@link ItemTags#PICKAXES} /
 * {@link ItemTags#AXES}) rather than class-based detection, because {@code PickaxeItem}
 * does not exist as a separate class in MC 26.2.</p>
 *
 * <p>MC 26.2 API notes (Mojang mappings):
 * <ul>
 *   <li>{@code SimpleContainer} replaces Fabric-mapped {@code SimpleInventory}</li>
 *   <li>{@code getItem(int)} / {@code setItem(int,ItemStack)} replace getStack/setStack</li>
 *   <li>{@code removeItem(int,int)} replaces removeStack(int)</li>
 *   <li>{@code isDamageableItem()} replaces isDamageable()</li>
 *   <li>{@code getDamageValue()} replaces getDamage()</li>
 *   <li>{@code getItem().getDefaultMaxStackSize()} replaces getMaxCount()</li>
 * </ul>
 * </p>
 */
public class GolemInventory extends SimpleContainer {

    // -------------------------------------------------------------------------
    // Layout constants
    // -------------------------------------------------------------------------

    /** Number of hotbar slots (indices 0..8). */
    public static final int HOTBAR     = 9;
    /** Number of main inventory slots (indices 9..35). */
    public static final int MAIN       = 27;
    /** Number of armor slots (indices 36..39). */
    public static final int ARMOR      = 4;
    /** Number of offhand slots (index 40). */
    public static final int OFFHAND    = 1;
    /** Total inventory size. */
    public static final int SIZE       = HOTBAR + MAIN + ARMOR + OFFHAND; // 41

    // Slot-region boundary constants
    /** First hotbar slot index (inclusive). */
    public static final int HOTBAR_START  = 0;
    /** Last hotbar slot index (inclusive). */
    public static final int HOTBAR_END    = HOTBAR - 1;                   // 8
    /** First main-inventory slot index (inclusive). */
    public static final int MAIN_START    = HOTBAR;                       // 9
    /** Last main-inventory slot index (inclusive). */
    public static final int MAIN_END      = HOTBAR + MAIN - 1;            // 35
    /** First armor slot index (inclusive). */
    public static final int ARMOR_START   = HOTBAR + MAIN;                // 36
    /** Last armor slot index (inclusive). */
    public static final int ARMOR_END     = ARMOR_START + ARMOR - 1;      // 39
    /** Offhand slot index. */
    public static final int OFFHAND_SLOT  = ARMOR_START + ARMOR;          // 40

    /**
     * Total "storage" slots available to tasks (hotbar + main, excluding armor/offhand).
     * Slots [0..35].
     */
    public static final int STORAGE_SIZE  = HOTBAR + MAIN;                // 36

    /** Index into the full inventory pointing at the active tool slot. */
    private int activeTool = HOTBAR_START; // default to slot 0 (first hotbar slot)

    public GolemInventory() {
        super(SIZE);
    }

    // -------------------------------------------------------------------------
    // Storage helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true when every storage slot (hotbar + main, slots 0..35) is
     * occupied and at max stack size. Armor and offhand slots are excluded.
     */
    public boolean isStorageFull() {
        for (int i = 0; i < STORAGE_SIZE; i++) {
            ItemStack s = getItem(i);
            if (s.isEmpty() || s.getCount() < s.getItem().getDefaultMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Active-tool management
    // -------------------------------------------------------------------------

    /** Returns the ItemStack in the currently active hotbar slot. */
    public ItemStack activeTool() {
        return getItem(activeTool);
    }

    /**
     * Sets the active tool by hotbar-relative index (0-8 maps directly to slots 0-8).
     *
     * @param hotbarSlot 0-based index within the hotbar region (0..8)
     */
    public void setActiveTool(int hotbarSlot) {
        this.activeTool = HOTBAR_START + hotbarSlot;
    }

    // -------------------------------------------------------------------------
    // Tool-slot finders — scan storage only (hotbar + main, not armor/offhand)
    // -------------------------------------------------------------------------

    /**
     * Returns the index of the first slot containing a pickaxe, or -1 if none.
     * Scans hotbar + main (slots 0..35). Uses {@link ItemTags#PICKAXES} tag.
     */
    public int findPickaxeSlot() {
        return findToolSlot(ItemTags.PICKAXES);
    }

    /**
     * Returns the index of the first slot containing an axe, or -1 if none.
     * Scans hotbar + main (slots 0..35). Uses {@link ItemTags#AXES} tag.
     */
    public int findAxeSlot() {
        return findToolSlot(ItemTags.AXES);
    }

    /**
     * Scans storage slots (hotbar + main, [0..STORAGE_SIZE)) for the first
     * stack whose item belongs to {@code tag}. Armor and offhand are excluded.
     *
     * <p>Detection: {@code stack.is(holder -> holder.is(tag))} — uses
     * {@link ItemStack#is(java.util.function.Predicate)} combined with
     * {@link net.minecraft.core.Holder#is(net.minecraft.tags.TagKey)}, which is
     * the correct MC 26.2 API for tag membership tests on an ItemStack.</p>
     */
    private int findToolSlot(net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag) {
        for (int i = 0; i < STORAGE_SIZE; i++) {
            ItemStack s = getItem(i);
            if (!s.isEmpty() && s.is(holder -> holder.is(tag))) {
                return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------------------
    // Equipping
    // -------------------------------------------------------------------------

    /**
     * Moves the stack at {@code slot} into the currently active hotbar slot.
     * The source slot is cleared (the entire stack is transferred).
     */
    public void equipFromSlot(int slot) {
        ItemStack tool = removeItem(slot, getItem(slot).getCount());
        setItem(activeTool, tool);
    }

    // -------------------------------------------------------------------------
    // Durability check
    // -------------------------------------------------------------------------

    /**
     * Returns true when the active tool's remaining durability is at or below
     * {@code margin} hits.
     *
     * <p>Uses {@code isDamageableItem()} and {@code getDamageValue()} — the
     * MC 26.2 names for what the brief calls {@code isDamageable()} and
     * {@code getDamage()}.</p>
     */
    public boolean activeToolNearBreaking(int margin) {
        ItemStack t = activeTool();
        if (t.isEmpty() || !t.isDamageableItem()) {
            return false;
        }
        return (t.getMaxDamage() - t.getDamageValue()) <= margin;
    }

    // -------------------------------------------------------------------------
    // Armor / offhand accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the four armor slot indices in order: [head, chest, legs, feet]
     * corresponding to slots [36, 37, 38, 39].
     */
    public int[] armorSlots() {
        return new int[]{ ARMOR_START, ARMOR_START + 1, ARMOR_START + 2, ARMOR_START + 3 };
    }

    /**
     * Returns the offhand slot index (40).
     */
    public int offhandSlot() {
        return OFFHAND_SLOT;
    }

    /**
     * Returns the ItemStack in the specified armor slot.
     *
     * @param armorIndex 0=head, 1=chest, 2=legs, 3=feet
     */
    public ItemStack getArmorItem(int armorIndex) {
        return getItem(ARMOR_START + armorIndex);
    }

    /**
     * Sets the ItemStack in the specified armor slot.
     *
     * @param armorIndex 0=head, 1=chest, 2=legs, 3=feet
     * @param stack      the armor stack to place
     */
    public void setArmorItem(int armorIndex, ItemStack stack) {
        setItem(ARMOR_START + armorIndex, stack);
    }

    /**
     * Returns the ItemStack in the offhand slot.
     */
    public ItemStack getOffhandItem() {
        return getItem(OFFHAND_SLOT);
    }

    /**
     * Sets the ItemStack in the offhand slot.
     */
    public void setOffhandItem(ItemStack stack) {
        setItem(OFFHAND_SLOT, stack);
    }

    // -------------------------------------------------------------------------
    // Crafting hook (stub for CraftingHelper / task B6)
    // -------------------------------------------------------------------------

    /**
     * Stub hook for 2x2 crafting. The CraftingHelper (B6) will supply a real
     * implementation; callers can invoke this now so the call-site exists.
     *
     * @param recipe  four-slot input pattern (row-major, null = empty slot)
     * @param output  the expected output stack (informational; B6 will verify)
     * @return true when crafting succeeded (always false in this stub)
     */
    public boolean craft2x2(ItemStack[] recipe, ItemStack output) {
        // Stub — real implementation supplied by CraftingHelper (B6).
        return false;
    }
}
