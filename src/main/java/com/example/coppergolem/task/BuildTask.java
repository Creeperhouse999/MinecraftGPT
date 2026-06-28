package com.example.coppergolem.task;

import com.example.coppergolem.entity.GolemPrimitives;
import com.example.coppergolem.inventory.GolemInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/**
 * Places blocks to build a simple shape from a chosen material.
 *
 * <p>Shapes:
 * <ul>
 *   <li>cube — solid WxHxL box (e.g. 3x3x3) at the build origin</li>
 *   <li>wall — flat W wide x H tall wall facing the given direction</li>
 *   <li>surround — a 1-block-thick ring of stone around the owner (pillar box)</li>
 * </ul>
 * </p>
 *
 * <p>The golem must HAVE the material blocks in inventory. If it runs out, the
 * task fails clearly. Placement respects zone protection (handled in placeBlock).</p>
 */
public final class BuildTask implements TaskHandler {

    private final String itemId;       // material item id, e.g. "minecraft:stone"
    private final Deque<BlockPos> cells = new ArrayDeque<>();
    private final int total;
    private String statusMsg;
    private String failed;
    private boolean paused;

    /**
     * @param shape    "cube", "wall", or "surround"
     * @param material item id of the block material
     * @param w/h/l    dimensions (interpreted per shape)
     * @param origin   build anchor (golem position or owner position)
     * @param ownerPos supplier of owner position (for surround); may be null
     */
    public BuildTask(String shape, String material, int w, int h, int l,
                     BlockPos origin, Supplier<BlockPos> ownerPos) {
        this.itemId = normalizeId(material);

        switch (shape == null ? "cube" : shape.toLowerCase()) {
            case "surround" -> {
                BlockPos c = ownerPos != null && ownerPos.get() != null ? ownerPos.get() : origin;
                // 3x3 ring (hollow) around owner, 2 high — a quick stone box.
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dz == 0) continue; // leave center (owner) open
                            cells.add(c.offset(dx, dy, dz));
                        }
                    }
                }
            }
            case "wall" -> {
                // W wide (along X), H tall (Y), 1 thick, starting in front of origin.
                for (int wi = 0; wi < Math.max(1, w); wi++) {
                    for (int hi = 0; hi < Math.max(1, h); hi++) {
                        cells.add(origin.offset(wi, hi, 1));
                    }
                }
            }
            default -> { // cube
                int W = Math.max(1, w), H = Math.max(1, h), L = Math.max(1, l);
                for (int xi = 0; xi < W; xi++)
                    for (int yi = 0; yi < H; yi++)
                        for (int zi = 0; zi < L; zi++)
                            cells.add(origin.offset(xi, yi, zi));
            }
        }
        this.total = cells.size();
        this.statusMsg = "building (" + total + " blocks)";
    }

    @Override
    public boolean tick(GolemPrimitives g) {
        if (failed != null) return true;
        if (paused || cells.isEmpty()) return cells.isEmpty();

        Item material = resolveItem();
        if (material == null) {
            failed = "unknown material " + itemId;
            statusMsg = "failed: " + failed;
            return true;
        }
        if (!hasMaterial(g, material)) {
            failed = "out of " + itemId;
            statusMsg = "failed: " + failed;
            return true;
        }

        BlockPos cell = cells.peek();
        // Walk close enough to place (reuse moveTo arrival semantics).
        if (!g.moveTo(cell)) return false;

        boolean placed = g.placeBlock(cell, material);
        cells.poll(); // advance regardless (skip blocked/occupied cells)
        statusMsg = "building: " + (total - cells.size()) + "/" + total;
        return cells.isEmpty();
    }

    private boolean hasMaterial(GolemPrimitives g, Item material) {
        GolemInventory inv = g.inventory();
        for (int i = 0; i < GolemInventory.STORAGE_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() == material && !s.isEmpty()) return true;
        }
        return false;
    }

    private Item resolveItem() {
        try {
            return BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeId(String id) {
        if (id == null || id.isEmpty()) return "minecraft:stone";
        if (id.contains(":")) return id;
        return "minecraft:" + id.toLowerCase().replace(' ', '_');
    }

    @Override public String status() { return statusMsg; }
    @Override public void pause() { paused = true; }
    @Override public void resume() { paused = false; }
}
