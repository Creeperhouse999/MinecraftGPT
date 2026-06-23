package com.example.coppergolem.task;

import com.example.coppergolem.craft.CraftingHelper;
import com.example.coppergolem.entity.GolemPrimitives;
import com.example.coppergolem.entity.ToolManager;
import com.example.coppergolem.inventory.GolemInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
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

    private final String itemId;
    private final int count;
    private final Supplier<BlockPos> ownerPos;
    private final MinecraftServer server;
    private final UUID ownerId;
    private final CraftingHelper crafts;
    private final ToolManager tools;

    private Phase phase = Phase.FIND_CHEST;
    private String statusMsg = "searching chests...";
    private boolean paused = false;

    // Sub-task for mine phase
    private OreHuntTask oreHunt = null;

    public BringTask(String itemId, int count, Supplier<BlockPos> ownerPos,
                     MinecraftServer server, UUID ownerId,
                     CraftingHelper crafts, ToolManager tools) {
        this.itemId = normalizeId(itemId);
        this.count = count;
        this.ownerPos = ownerPos;
        this.server = server;
        this.ownerId = ownerId;
        this.crafts = crafts;
        this.tools = tools;
    }

    @Override
    public boolean tick(GolemPrimitives g) {
        if (paused) return false;

        switch (phase) {

            case FIND_CHEST -> {
                Item target = resolveItem();
                if (target == null) {
                    phase = Phase.FAIL;
                    statusMsg = "failed: unknown item " + itemId;
                    return false;
                }
                // Already have enough?
                if (hasItem(g, target)) {
                    phase = Phase.WALK_TO_OWNER;
                    statusMsg = "already have it, bringing to you...";
                    return false;
                }
                List<BlockPos> chests = g.findChests(32);
                for (BlockPos chest : chests) {
                    Map<Item, Integer> contents = g.readChest(chest);
                    if (contents.getOrDefault(target, 0) > 0) {
                        if (!g.moveTo(chest)) return false; // still walking
                        int pulled = g.pullFromChest(chest, target, count);
                        if (pulled > 0 && hasItem(g, target)) {
                            phase = Phase.WALK_TO_OWNER;
                            statusMsg = "found in chest, bringing to you...";
                            return false;
                        }
                    }
                }
                // Nothing in chests
                phase = Phase.CRAFT;
                statusMsg = "not in chests, trying to craft...";
            }

            case CRAFT -> {
                Item target = resolveItem();
                if (target != null && hasItem(g, target)) {
                    phase = Phase.WALK_TO_OWNER;
                    statusMsg = "crafted, bringing to you...";
                    return false;
                }
                if (crafts != null && crafts.craft(itemId, count)) {
                    phase = Phase.WALK_TO_OWNER;
                    statusMsg = "crafted, bringing to you...";
                } else {
                    phase = Phase.MINE;
                    statusMsg = "can't craft, mining/hunting...";
                    // Build ore name from item id (e.g. "minecraft:iron_ingot" → "iron")
                    String oreName = oreNameFromItemId(itemId);
                    oreHunt = new OreHuntTask(oreName, count, tools, g);
                }
            }

            case MINE -> {
                Item target = resolveItem();
                if (target != null && hasItem(g, target)) {
                    phase = Phase.WALK_TO_OWNER;
                    statusMsg = "got it, bringing to you...";
                    oreHunt = null;
                    return false;
                }
                if (oreHunt == null) {
                    phase = Phase.FAIL;
                    statusMsg = "failed: nothing to mine";
                    return false;
                }
                boolean done = oreHunt.tick(g);
                statusMsg = "mining: " + oreHunt.status();
                if (done) {
                    if (target != null && hasItem(g, target)) {
                        phase = Phase.WALK_TO_OWNER;
                        statusMsg = "mined it, bringing to you...";
                    } else {
                        phase = Phase.FAIL;
                        statusMsg = "failed: couldn't obtain " + itemId;
                    }
                    oreHunt = null;
                }
            }

            case WALK_TO_OWNER -> {
                BlockPos dest = ownerPos.get();
                if (dest == null) { statusMsg = "waiting for owner..."; return false; }
                if (g.moveTo(dest)) {
                    phase = Phase.GIVE;
                    statusMsg = "giving items to you...";
                }
            }

            case GIVE -> {
                if (server == null) { phase = Phase.DONE; return true; }
                ServerPlayer player = server.getPlayerList().getPlayer(ownerId);
                if (player == null) {
                    statusMsg = "owner offline";
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
                        if (!player.getInventory().add(give)) {
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

            case FAIL -> { return true; }
            case DONE -> { return true; }
        }
        return false;
    }

    @Override public String status() { return statusMsg; }
    @Override public void pause() { paused = true; if (oreHunt != null) oreHunt.pause(); }
    @Override public void resume() { paused = false; if (oreHunt != null) oreHunt.resume(); }

    private Item resolveItem() {
        try {
            return BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
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

    /** Maps item id to ore name for OreHuntTask. e.g. "minecraft:iron_ingot" → "iron" */
    private static String oreNameFromItemId(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        // Strip common suffixes
        for (String suffix : new String[]{"_ingot", "_ore", "_gem", "_dust", "_nugget"}) {
            if (path.endsWith(suffix)) return path.substring(0, path.length() - suffix.length());
        }
        return path; // fallback: use as-is (OreHuntTask will handle unknown gracefully)
    }

    private static String normalizeId(String id) {
        if (id == null || id.isEmpty()) return "minecraft:air";
        if (id.contains(":")) return id;
        return "minecraft:" + id.toLowerCase().replace(' ', '_');
    }
}
