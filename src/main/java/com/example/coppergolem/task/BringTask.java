package com.example.coppergolem.task;

import com.example.coppergolem.entity.GolemPrimitives;
import com.example.coppergolem.inventory.GolemInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Brings items to owner. Priority: find in chests → craft → ore_hunt/mine → fail.
 * Once item secured, walks to owner and transfers from golem inv to player inv.
 */
public class BringTask implements TaskHandler {

    private enum Phase { FIND_CHEST, CRAFT, MINE, WALK_TO_OWNER, GIVE, FAIL, DONE }

    private final String itemId;   // e.g. "minecraft:iron_ingot"
    private final int count;
    private final Supplier<BlockPos> ownerPos;
    private final MinecraftServer server;
    private final UUID ownerId;

    private Phase phase = Phase.FIND_CHEST;
    private String statusMsg = "searching chests...";
    private int mineTicks = 0;
    private boolean paused = false;

    public BringTask(String itemId, int count, Supplier<BlockPos> ownerPos,
                     MinecraftServer server, UUID ownerId) {
        this.itemId = normalizeId(itemId);
        this.count = count;
        this.ownerPos = ownerPos;
        this.server = server;
        this.ownerId = ownerId;
    }

    @Override
    public boolean tick(GolemPrimitives g) {
        if (paused) return false;

        switch (phase) {

            case FIND_CHEST -> {
                // Search nearby chests for the item
                List<BlockPos> chests = g.findChests(32);
                Item target = resolveItem();
                if (target == null) {
                    phase = Phase.FAIL;
                    statusMsg = "failed: unknown item " + itemId;
                    return false;
                }
                boolean found = false;
                for (BlockPos chest : chests) {
                    Map<Item, Integer> contents = g.readChest(chest);
                    if (contents.getOrDefault(target, 0) >= count) {
                        // Walk to chest then pull
                        if (g.moveTo(chest)) {
                            int pulled = g.pullFromChest(chest, target, count);
                            if (pulled >= count) {
                                found = true;
                                break;
                            }
                        }
                        return false; // still walking
                    }
                }
                if (found) {
                    phase = Phase.WALK_TO_OWNER;
                    statusMsg = "found in chest, bringing to you...";
                } else {
                    phase = Phase.CRAFT;
                    statusMsg = "not in chests, trying to craft...";
                }
            }

            case CRAFT -> {
                Item target = resolveItem();
                if (target != null && hasItem(g, target)) {
                    phase = Phase.WALK_TO_OWNER;
                    statusMsg = "already have it, bringing to you...";
                    return false;
                }
                // Try crafting via CraftingHelper (golem primitives don't expose craft directly,
                // so we check hasCraftMaterials then craftTool as proxy)
                String craftId = itemId;
                boolean crafted = g.craftTool(craftId); // works for tools; falls through for others
                if (crafted) {
                    phase = Phase.WALK_TO_OWNER;
                    statusMsg = "crafted, bringing to you...";
                } else {
                    phase = Phase.MINE;
                    statusMsg = "can't craft, searching world...";
                }
            }

            case MINE -> {
                // Use ore hunt approach: scan for the block near golem
                Item target = resolveItem();
                if (target != null && hasItem(g, target)) {
                    phase = Phase.WALK_TO_OWNER;
                    statusMsg = "got it, bringing to you...";
                    return false;
                }
                mineTicks++;
                if (mineTicks > 400) { // 20s timeout
                    phase = Phase.FAIL;
                    statusMsg = "failed: could not find or mine " + itemId;
                }
                statusMsg = "searching world for " + itemId + "...";
                // Mine task will handle actual ore hunting; here we just wait if ore_hunt was chained
            }

            case WALK_TO_OWNER -> {
                BlockPos dest = ownerPos.get();
                if (dest == null) {
                    statusMsg = "waiting for owner...";
                    return false;
                }
                if (g.moveTo(dest)) {
                    phase = Phase.GIVE;
                    statusMsg = "giving items to you...";
                }
            }

            case GIVE -> {
                if (server == null) { phase = Phase.DONE; return false; }
                ServerPlayer player = server.getPlayerList().getPlayer(ownerId);
                if (player == null) {
                    statusMsg = "owner not found";
                    phase = Phase.FAIL;
                    return false;
                }
                Item target = resolveItem();
                if (target == null) { phase = Phase.DONE; return true; }
                GolemInventory inv = g.inventory();
                int transferred = 0;
                for (int i = 0; i < GolemInventory.SIZE && transferred < count; i++) {
                    ItemStack stack = inv.getItem(i);
                    if (stack.getItem() == target) {
                        int take = Math.min(stack.getCount(), count - transferred);
                        ItemStack give = stack.copyWithCount(take);
                        // Give to player — use player inventory addItem
                        if (!player.getInventory().add(give)) {
                            // Drop at player feet if inventory full
                            player.drop(give, false);
                        }
                        stack.shrink(take);
                        if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
                        transferred += take;
                    }
                }
                statusMsg = "delivered " + transferred + "x " + itemId;
                phase = Phase.DONE;
                return true;
            }

            case FAIL -> { return true; } // done (failed)
            case DONE -> { return true; }
        }
        return false;
    }

    @Override
    public String status() { return statusMsg; }
    @Override public void pause() { paused = true; }
    @Override public void resume() { paused = false; }

    private Item resolveItem() {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getValue(net.minecraft.resources.Identifier.parse(itemId));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasItem(GolemPrimitives g, Item target) {
        GolemInventory inv = g.inventory();
        int found = 0;
        for (int i = 0; i < GolemInventory.SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() == target) found += s.getCount();
        }
        return found >= count;
    }

    private static String normalizeId(String id) {
        if (id == null || id.isEmpty()) return "minecraft:air";
        if (id.contains(":")) return id;
        return "minecraft:" + id.toLowerCase().replace(' ', '_');
    }
}
