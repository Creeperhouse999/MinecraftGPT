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

    /**
     * Instant block break (no mining delay/animation), for tool-gathering and
     * internal callers that need to break a block within a single tick.
     * Respects zone protection and player-built block safety.
     *
     * @return true if the block was broken this call
     */
    boolean mineBlockInstant(BlockPos pos);

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

    // -------------------------------------------------------------------------
    // Framed-chest / sign reading (Task D2)
    // -------------------------------------------------------------------------

    /**
     * Scan within {@code radius} blocks and return a map of
     * {@code chestPos → registry-id of the item in the item frame on that chest}
     * for every chest that has at least one {@link net.minecraft.world.entity.decoration.ItemFrame}
     * on one of its six faces. Chests without a frame are omitted (they are not
     * valid sort destinations). If a chest has multiple frames the first non-empty
     * item found wins.
     */
    Map<BlockPos, String> readFramedChests(int radius);

    /**
     * Read the text from the sign placed directly above {@code chest}, or return
     * an empty string if there is no sign there. Only the front text is read;
     * all four lines are joined with a space.
     */
    String readSignAbove(BlockPos chest);

    // -------------------------------------------------------------------------
    // Combat (Task 5c)
    // -------------------------------------------------------------------------

    /**
     * Returns the nearest hostile living entity within {@code radius} blocks,
     * or {@code null} if none are found.
     */
    net.minecraft.world.entity.LivingEntity findNearestHostile(int radius);

    /** Returns all hostile living entities within {@code radius} blocks. */
    java.util.List<net.minecraft.world.entity.LivingEntity> findHostiles(int radius);

    /**
     * Make the golem perform a melee attack on {@code target}.
     * Plays the swing animation and applies damage.
     */
    void attackEntity(net.minecraft.world.entity.LivingEntity target);
}
