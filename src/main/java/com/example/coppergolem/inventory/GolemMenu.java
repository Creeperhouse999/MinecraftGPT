package com.example.coppergolem.inventory;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Server-side container for the golem's 41-slot inventory.
 *
 * <p>Slot layout (indices into {@code this.slots}):
 * <ul>
 *   <li>0–8   : golem hotbar</li>
 *   <li>9–35  : golem main (3 rows × 9)</li>
 *   <li>36–39 : golem armor</li>
 *   <li>40    : golem offhand</li>
 *   <li>41–67 : player main inventory</li>
 *   <li>68–76 : player hotbar</li>
 * </ul>
 * </p>
 *
 * <p>MC 26.2 notes:
 * <ul>
 *   <li>{@code MenuType} constructor is made accessible by the fabric-menu-api-v1
 *       classtweaker, so {@code new MenuType<>(supplier, flags)} compiles fine.</li>
 *   <li>{@code TYPE} is registered via {@link net.minecraft.core.Registry#register}
 *       into {@link net.minecraft.core.registries.BuiltInRegistries#MENU}.</li>
 * </ul>
 * </p>
 */
public class GolemMenu extends AbstractContainerMenu {

    /** Registered in {@code CopperGolemMod.onInitialize()}. */
    public static MenuType<GolemMenu> TYPE;

    private final GolemInventory golemInv;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Server-side constructor: uses the real {@link GolemInventory}.
     */
    public GolemMenu(int syncId, Inventory playerInv, GolemInventory golemInv) {
        super(TYPE, syncId);
        this.golemInv = golemInv;

        // Golem hotbar: slots 0-8
        for (int i = 0; i < 9; i++) {
            addSlot(new Slot(golemInv, i, 8 + i * 18, 18));
        }
        // Golem main: slots 9-35 (3 rows × 9)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(golemInv, 9 + row * 9 + col, 8 + col * 18, 54 + row * 18));
            }
        }
        // Golem armor: slots 36-39
        for (int i = 0; i < 4; i++) {
            addSlot(new Slot(golemInv, 36 + i, 8 + i * 18, 126));
        }
        // Golem offhand: slot 40
        addSlot(new Slot(golemInv, 40, 8, 144));

        // Player main inventory: 27 slots (rows 0-2, cols 0-8)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInv, col + row * 9 + 9, 8 + col * 18, 174 + row * 18));
            }
        }
        // Player hotbar: 9 slots
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInv, col, 8 + col * 18, 232));
        }
    }

    /**
     * Client-side factory constructor: uses a dummy {@link GolemInventory}.
     * Registered as the {@link MenuType} factory.
     */
    public GolemMenu(int syncId, Inventory playerInv) {
        this(syncId, playerInv, new GolemInventory());
    }

    // -------------------------------------------------------------------------
    // AbstractContainerMenu contract
    // -------------------------------------------------------------------------

    /**
     * Shift-click logic: golem slots 0-40 shift to player; player slots shift to
     * golem (preferring main 9-35, then hotbar 0-8).
     */
    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();
            if (index < 41) {
                // Golem slot → try player inventory
                if (!moveItemStackTo(stack, 41, slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // Player slot → try golem inventory (hotbar first, then main)
                if (!moveItemStackTo(stack, 0, 41, false)) {
                    return ItemStack.EMPTY;
                }
            }
            if (stack.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }
}
