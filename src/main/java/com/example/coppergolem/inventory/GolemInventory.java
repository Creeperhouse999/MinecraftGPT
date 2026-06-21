package com.example.coppergolem.inventory;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * Golem inventory: 27 general storage slots + 4 hotbar/tool slots (slots 27-30).
 *
 * <p>Tool detection uses MC 26.2 item tags ({@link ItemTags#PICKAXES} /
 * {@link ItemTags#AXES}) rather than the class-based {@code PickaxeItem} /
 * {@code AxeItem} approach specified in the brief, because {@code PickaxeItem}
 * does not exist as a separate class in MC 26.2. {@code AxeItem} does exist but
 * tags are more robust and consistent across tool tiers.</p>
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

    public static final int STORAGE = 27;
    public static final int HOTBAR  = 4;           // tool slots
    public static final int SIZE    = STORAGE + HOTBAR; // 31 total; [27..30] = hotbar

    /** Index into the full inventory pointing at the active tool slot. */
    private int activeTool = STORAGE; // default to first hotbar slot

    public GolemInventory() {
        super(SIZE);
    }

    // -------------------------------------------------------------------------
    // Storage helpers
    // -------------------------------------------------------------------------

    /** Returns true when every storage slot is occupied and at max stack size. */
    public boolean isStorageFull() {
        for (int i = 0; i < STORAGE; i++) {
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
     * Sets the active tool by hotbar-relative index (0-3 maps to slots 27-30).
     *
     * @param hotbarSlot 0-based index within the hotbar region
     */
    public void setActiveTool(int hotbarSlot) {
        this.activeTool = STORAGE + hotbarSlot;
    }

    // -------------------------------------------------------------------------
    // Tool-slot finders — scan all slots (storage + hotbar)
    // -------------------------------------------------------------------------

    /**
     * Returns the index of the first slot containing a pickaxe, or -1 if none.
     * Uses {@link ItemTags#PICKAXES} tag (MC 26.2 has no PickaxeItem class).
     */
    public int findPickaxeSlot() {
        return findToolSlot(ItemTags.PICKAXES);
    }

    /**
     * Returns the index of the first slot containing an axe, or -1 if none.
     * Uses {@link ItemTags#AXES} tag.
     */
    public int findAxeSlot() {
        return findToolSlot(ItemTags.AXES);
    }

    /**
     * Scans all slots for the first stack whose item belongs to {@code tag}.
     *
     * <p>Detection: {@code stack.is(holder -> holder.is(tag))} — this uses
     * {@link ItemStack#is(java.util.function.Predicate)} combined with
     * {@link net.minecraft.core.Holder#is(net.minecraft.tags.TagKey)}, which is
     * the correct MC 26.2 API for tag membership tests on an ItemStack.</p>
     */
    private int findToolSlot(net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag) {
        for (int i = 0; i < getContainerSize(); i++) {
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
}
