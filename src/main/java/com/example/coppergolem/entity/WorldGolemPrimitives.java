package com.example.coppergolem.entity;

import com.example.coppergolem.craft.CraftingHelper;
import com.example.coppergolem.inventory.GolemInventory;
import com.example.coppergolem.zone.ZoneManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan A implementation of {@link GolemPrimitives}: wraps a real vanilla
 * {@link CopperGolem} entity living in a {@link ServerLevel}, with an injected
 * {@link ZoneManager} (build protection) and a {@link GolemInventory} (from the
 * Fabric AttachmentType, see {@code GolemAttachments}).
 *
 * <p>Zone enforcement: {@link #mineBlock} and {@link #placeBlock} refuse (return
 * false) when {@code zones.isProtected(x, z)} is true. Chest transfers
 * ({@link #pullFromChest}/{@link #pushToChest}) are always allowed.</p>
 *
 * <p>Auto-torch: {@link #mineBlock} calls {@link #placeTorchIfNeeded(BlockPos)}
 * after a successful break — if the golem holds torches and either the light is
 * low or {@value #TORCH_EVERY_N} blocks have been mined since the last torch, it
 * places one (no gate, the golem owns the torches).</p>
 *
 * <p>All Minecraft API names below were read from the 26.2 Mojmap
 * {@code minecraft-common} jar (see task-0b-report.md): navigation via
 * {@code Mob.getNavigation().moveTo(x,y,z,speed)}; world edits via
 * {@code ServerLevel.destroyBlock/setBlockAndUpdate/getBlockState/getBlockEntity};
 * registry via {@code BuiltInRegistries}; chests via the {@code Container}
 * interface; item pickup via {@code getEntitiesOfClass(ItemEntity, AABB)}.</p>
 */
public final class WorldGolemPrimitives implements GolemPrimitives {

    /** Pathfinding speed multiplier passed to the navigation. */
    private static final double MOVE_SPEED = 1.0D;
    /** Considered "arrived" when within this many blocks of the target. */
    private static final double ARRIVE_DIST = 2.0D;
    /** Place a torch at most every N successful block breaks. */
    private static final int TORCH_EVERY_N = 8;
    /** Light level at/below which a torch is placed regardless of the counter. */
    private static final int LOW_LIGHT = 7;

    private final CopperGolem golem;
    private final ServerLevel level;
    private final ZoneManager zones;
    private final GolemInventory inventory;

    /**
     * Optional crafting delegate (wired post-construction by GolemController to
     * break the primitives&lt;-&gt;CraftingHelper construction cycle); null = minimal
     * mode (craftTool/hasCraftMaterials return false).
     */
    private CraftingHelper crafts;

    /** Mined-block counter feeding the auto-torch cadence. */
    private int blocksSinceTorch = 0;

    public WorldGolemPrimitives(CopperGolem golem,
                                ServerLevel level,
                                ZoneManager zones,
                                GolemInventory inventory) {
        this.golem = golem;
        this.level = level;
        this.zones = zones;
        this.inventory = inventory;
    }

    /** Wire the crafting delegate after construction (cycle-breaking). */
    public void setCraftingHelper(CraftingHelper crafts) {
        this.crafts = crafts;
    }

    // -------------------------------------------------------------------------
    // Movement / world interaction
    // -------------------------------------------------------------------------

    @Override
    public boolean moveTo(BlockPos pos) {
        // Already close enough?
        if (golem.blockPosition().closerThan(pos, ARRIVE_DIST)) {
            return true;
        }
        // PathNavigation.moveTo(double x, double y, double z, double speed) -> boolean
        golem.getNavigation().moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, MOVE_SPEED);
        // Report arrival only when within range this tick; the caller re-ticks until true.
        return golem.blockPosition().closerThan(pos, ARRIVE_DIST);
    }

    @Override
    public boolean mineBlock(BlockPos pos) {
        if (zones.isProtected(pos.getX(), pos.getZ())) {
            return false; // build protection: refuse to mine inside a zone
        }

        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }

        // Capture drops into the golem inventory: break WITHOUT world drops, then
        // synthesize the loot ourselves so it lands in the attachment inventory
        // rather than on the ground. (destroyBlock(pos, false) = no item drops.)
        ItemStack drop = new ItemStack(state.getBlock().asItem());
        boolean broken = level.destroyBlock(pos, false);
        if (!broken) {
            return false;
        }
        if (!drop.isEmpty()) {
            // addItem returns the leftover that did not fit; we discard the
            // leftover (inventory full) rather than spilling it.
            inventory.addItem(drop);
        }
        // Also sweep any item entities the break may have produced.
        pickupNearbyItems(2);

        placeTorchIfNeeded(pos);
        return true;
    }

    @Override
    public boolean placeBlock(BlockPos pos, Item item) {
        if (zones.isProtected(pos.getX(), pos.getZ())) {
            return false; // build protection: refuse to place inside a zone
        }
        if (item == null) {
            return false;
        }
        Block block = Block.byItem(item);
        if (block == null) {
            return false;
        }
        // Consume one of the item from inventory if present.
        consumeOne(item);
        return level.setBlockAndUpdate(pos, block.defaultBlockState());
    }

    /**
     * Auto-torch helper. Places a torch from the golem's inventory at {@code pos}
     * (the just-mined position) when light is low or every {@value #TORCH_EVERY_N}
     * blocks. No approval gate — the golem owns its torches.
     *
     * <p>Public so the mine flow / executor can also call it directly.</p>
     */
    public void placeTorchIfNeeded(BlockPos pos) {
        blocksSinceTorch++;

        int torchSlot = findItemSlot("minecraft:torch");
        if (torchSlot < 0) {
            return; // no torches held
        }

        boolean lowLight = level.getMaxLocalRawBrightness(pos) <= LOW_LIGHT;
        if (!lowLight && blocksSinceTorch < TORCH_EVERY_N) {
            return;
        }
        if (zones.isProtected(pos.getX(), pos.getZ())) {
            return; // don't decorate inside a protected zone
        }

        Block torch = Block.byItem(inventory.getItem(torchSlot).getItem());
        if (torch == null) {
            return;
        }
        if (level.getBlockState(pos).isAir()
                && level.setBlockAndUpdate(pos, torch.defaultBlockState())) {
            inventory.removeItem(torchSlot, 1);
            blocksSinceTorch = 0;
        }
    }

    @Override
    public void pickupNearbyItems(int radius) {
        AABB box = golem.getBoundingBox().inflate(radius);
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, box);
        for (ItemEntity ie : drops) {
            ItemStack stack = ie.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack leftover = inventory.addItem(stack.copy());
            if (leftover.isEmpty()) {
                ie.discard();
            } else {
                ie.setItem(leftover);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Chest / storage
    // -------------------------------------------------------------------------

    @Override
    public List<BlockPos> findChests(int radius) {
        List<BlockPos> found = new ArrayList<>();
        BlockPos origin = golem.blockPosition();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (level.getBlockEntity(p) instanceof Container) {
                        found.add(p.immutable());
                    }
                }
            }
        }
        return found;
    }

    @Override
    public Map<Item, Integer> readChest(BlockPos chest) {
        Map<Item, Integer> out = new HashMap<>();
        if (!(level.getBlockEntity(chest) instanceof Container c)) {
            return out;
        }
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (!s.isEmpty()) {
                out.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        return out;
    }

    @Override
    public int pullFromChest(BlockPos chest, Item item, int max) {
        if (!(level.getBlockEntity(chest) instanceof Container c)) {
            return 0;
        }
        int moved = 0;
        for (int i = 0; i < c.getContainerSize() && moved < max; i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || s.getItem() != item) {
                continue;
            }
            int take = Math.min(s.getCount(), max - moved);
            ItemStack toAdd = s.copy();
            toAdd.setCount(take);
            ItemStack leftover = inventory.addItem(toAdd);
            int accepted = take - leftover.getCount();
            if (accepted <= 0) {
                break; // golem inventory full
            }
            s.shrink(accepted);
            c.setItem(i, s.isEmpty() ? ItemStack.EMPTY : s);
            moved += accepted;
        }
        if (moved > 0) {
            c.setChanged();
        }
        return moved;
    }

    @Override
    public int pushToChest(BlockPos chest, Item item, int max) {
        if (!(level.getBlockEntity(chest) instanceof Container c)) {
            return 0;
        }
        int moved = 0;
        // Pull the matching items out of the golem inventory, up to max.
        for (int slot = 0; slot < inventory.getContainerSize() && moved < max; slot++) {
            ItemStack s = inventory.getItem(slot);
            if (s.isEmpty() || s.getItem() != item) {
                continue;
            }
            int want = Math.min(s.getCount(), max - moved);
            ItemStack toInsert = s.copy();
            toInsert.setCount(want);
            int inserted = insertIntoContainer(c, toInsert);
            if (inserted <= 0) {
                continue; // chest had no room for this stack
            }
            inventory.removeItem(slot, inserted);
            moved += inserted;
        }
        if (moved > 0) {
            c.setChanged();
        }
        return moved;
    }

    /** Insert a stack into a Container, returning the count actually inserted. */
    private int insertIntoContainer(Container c, ItemStack stack) {
        int remaining = stack.getCount();
        int max = stack.getMaxStackSize();

        // First pass: top up matching stacks.
        for (int i = 0; i < c.getContainerSize() && remaining > 0; i++) {
            ItemStack dst = c.getItem(i);
            if (dst.isEmpty() || dst.getItem() != stack.getItem()) {
                continue;
            }
            int space = max - dst.getCount();
            if (space <= 0) {
                continue;
            }
            int add = Math.min(space, remaining);
            dst.grow(add);
            c.setItem(i, dst);
            remaining -= add;
        }
        // Second pass: empty slots.
        for (int i = 0; i < c.getContainerSize() && remaining > 0; i++) {
            if (!c.getItem(i).isEmpty()) {
                continue;
            }
            int add = Math.min(max, remaining);
            ItemStack put = stack.copy();
            put.setCount(add);
            c.setItem(i, put);
            remaining -= add;
        }
        return stack.getCount() - remaining;
    }

    // -------------------------------------------------------------------------
    // Self-query
    // -------------------------------------------------------------------------

    @Override
    public BlockPos position() {
        return golem.blockPosition();
    }

    @Override
    public String getBlockId(BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return "";
        }
        Block block = level.getBlockState(pos).getBlock();
        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? "" : id.getPath();
    }

    @Override
    public GolemInventory inventory() {
        return inventory;
    }

    // -------------------------------------------------------------------------
    // Tool management
    // -------------------------------------------------------------------------

    @Override
    public boolean equipTool(ItemStack tool) {
        if (tool == null || tool.isEmpty()) {
            return false;
        }
        // The active tool already lives in the golem inventory's active hotbar slot
        // (GolemInventory.equipFromSlot moved it there). Also set it in the entity's
        // main hand so unmodded clients can see the golem holding it.
        // Uses MC 26.2 LivingEntity API: setItemInHand(InteractionHand, ItemStack).
        golem.setItemInHand(InteractionHand.MAIN_HAND, tool);
        return !inventory.activeTool().isEmpty();
    }

    /**
     * Sync the golem's main-hand item to whatever is currently the active tool in
     * {@link GolemInventory}. Call this after any inventory change that may have
     * swapped the active slot without going through {@link #equipTool}.
     */
    public void syncHeldItem() {
        ItemStack active = inventory.activeTool();
        golem.setItemInHand(InteractionHand.MAIN_HAND, active.isEmpty() ? ItemStack.EMPTY : active);
    }

    @Override
    public boolean craftTool(String toolId) {
        if (crafts == null) {
            return false; // minimal mode: no crafting wired
        }
        return crafts.craft(normalizeId(toolId), 1);
    }

    @Override
    public boolean hasCraftMaterials(String toolId) {
        if (crafts == null) {
            return false;
        }
        return crafts.canCraft(normalizeId(toolId));
    }

    @Override
    public List<BlockPos> findTreeBases(int radius) {
        List<BlockPos> bases = new ArrayList<>();
        BlockPos origin = golem.blockPosition();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                // Scan a vertical window for the lowest log of a column.
                for (int dy = -2; dy <= radius; dy++) {
                    BlockPos p = origin.offset(dx, dy, dz);
                    if (isLog(p) && !isLog(p.below())) {
                        bases.add(p.immutable());
                        break; // one base per column
                    }
                }
            }
        }
        return bases;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private boolean isLog(BlockPos p) {
        String id = getBlockId(p);
        return id.endsWith("_log") || id.endsWith("_stem")
                || id.endsWith("_wood") || id.endsWith("_hyphae");
    }

    /** Find the first storage slot holding {@code itemId} (full or path id), or -1. */
    private int findItemSlot(String itemId) {
        for (int i = 0; i < GolemInventory.STORAGE_SIZE; i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            Identifier key = BuiltInRegistries.ITEM.getKey(s.getItem());
            if (key != null && (key.toString().equals(itemId) || key.getPath().equals(itemId))) {
                return i;
            }
        }
        return -1;
    }

    /** Remove one of {@code item} from inventory storage if present. */
    private void consumeOne(Item item) {
        for (int i = 0; i < GolemInventory.STORAGE_SIZE; i++) {
            ItemStack s = inventory.getItem(i);
            if (!s.isEmpty() && s.getItem() == item) {
                inventory.removeItem(i, 1);
                return;
            }
        }
    }

    /** Ensure a "minecraft:"-prefixed id for registry lookups used by CraftingHelper. */
    private static String normalizeId(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }
}
