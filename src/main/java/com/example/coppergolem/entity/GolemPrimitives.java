package com.example.coppergolem.entity;

import com.example.coppergolem.inventory.GolemInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * Abstraction layer for golem capabilities. Task handlers depend on this interface;
 * concrete implementations (Plan A vanilla entity, Plan B bodiless) land in Task 11.
 *
 * <p>MC 26.2 Mojang-mapping note: BlockPos is {@code net.minecraft.core.BlockPos},
 * Item is {@code net.minecraft.world.item.Item}, ItemStack is
 * {@code net.minecraft.world.item.ItemStack}.</p>
 */
public interface GolemPrimitives {

    // -------------------------------------------------------------------------
    // Movement / world interaction
    // -------------------------------------------------------------------------

    /** Pathfind/teleport-step toward pos; returns true when adjacent to or at it. */
    boolean moveTo(BlockPos pos);

    /**
     * Break the block at pos, dropping its loot into the golem inventory.
     * The active tool (set via {@link #equipTool}) is used and takes durability
     * damage; if no appropriate tool is equipped the block still breaks but
     * callers should equip one via {@code ToolManager} first.
     *
     * @return false if the position is unreachable
     */
    boolean mineBlock(BlockPos pos);

    /** Place {@code item} from the golem inventory at {@code pos}. */
    boolean placeBlock(BlockPos pos, Item item);

    /** Sweep the area around the golem and pull nearby item entities into inventory. */
    void pickupNearbyItems(int radius);

    // -------------------------------------------------------------------------
    // Chest / storage
    // -------------------------------------------------------------------------

    /** Return positions of all chests within {@code radius} blocks of the golem. */
    List<BlockPos> findChests(int radius);

    /** Read the contents of the chest at {@code chest}; returns item → count map. */
    Map<Item, Integer> readChest(BlockPos chest);

    /**
     * Pull up to {@code max} of {@code item} from the chest into the golem inventory.
     *
     * @return actual number of items moved
     */
    int pullFromChest(BlockPos chest, Item item, int max);

    /**
     * Push up to {@code max} of {@code item} from the golem inventory into the chest.
     *
     * @return actual number of items moved
     */
    int pushToChest(BlockPos chest, Item item, int max);

    // -------------------------------------------------------------------------
    // Self-query
    // -------------------------------------------------------------------------

    /** Current block position of this golem. */
    BlockPos position();

    /**
     * Return the registry path of the block at {@code pos} (e.g. {@code "stone"},
     * {@code "cobblestone"}), or an empty string if the position is unloaded.
     * Used by ToolManager to filter which blocks may be mined during gather passes.
     */
    String getBlockId(BlockPos pos);

    /** The golem's inventory instance. */
    GolemInventory inventory();

    // -------------------------------------------------------------------------
    // Tool management (Addendum A / Task 13)
    // -------------------------------------------------------------------------

    /**
     * Set {@code tool} as the golem's active tool (placed in the active hotbar slot).
     *
     * @return true if the tool was successfully equipped
     */
    boolean equipTool(ItemStack tool);

    /**
     * Craft the tool identified by {@code toolId} using materials already in inventory.
     *
     * @return true on success; false if materials are missing or crafting failed
     */
    boolean craftTool(String toolId);

    /**
     * Scan within {@code radius} blocks and return the base (ground-level) positions
     * of all trees found.
     */
    List<BlockPos> findTreeBases(int radius);

    /**
     * Return true when the golem inventory contains the raw materials needed to
     * craft the tool identified by {@code toolId} (e.g. planks+sticks for a wooden
     * pickaxe, cobblestone+sticks for a stone one).
     */
    boolean hasCraftMaterials(String toolId);
}
