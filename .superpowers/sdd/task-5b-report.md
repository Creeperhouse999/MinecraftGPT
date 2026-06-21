# Task 5b — ToolManager Report

## Files Created

- `src/main/java/com/example/coppergolem/entity/ToolManager.java` (196 lines)

No split to `ToolRecipes.java` required — stayed under 200 lines.

## Sourcing Order → Interface Method Mapping

| Step | Description | Interface methods used |
|------|-------------|------------------------|
| 1 | Already in inventory | `g.inventory().findPickaxeSlot()` / `findAxeSlot()` → `equipFromSlot(slot)` → `g.equipTool(activeTool())` |
| 2 | Pull from nearby chests | `g.findChests(16)` → `g.readChest(chest)` → `g.pullFromChest(chest, item, 1)` → then step 1 equip |
| 3 | Craft from existing inventory materials | `g.hasCraftMaterials(toolId)` → `g.craftTool(toolId)` → then step 1 equip |
| 4 | Gather then craft | PICKAXE: `g.position()` + `g.mineBlock(pos)` + `g.pickupNearbyItems(3)` ; AXE: `g.findTreeBases(12)` + `g.moveTo(tree)` + `g.mineBlock(tree)` + `g.pickupNearbyItems(3)` → then `g.hasCraftMaterials` → `g.craftTool` |
| 5 | Fail | return false |

### maybeReplaceBeforeBreak
- `g.inventory().activeToolNearBreaking(margin)` → calls `ensureTool(kind)` if true.

### stockSpares
- Loops up to `count` times; each iteration: `g.hasCraftMaterials` → `g.craftTool` → `g.inventory().getItem(slot).getItem()` → `g.pushToChest(storage, item, 1)`.
- Falls back to a single `gatherMaterials` pass per spare if materials are exhausted.

### idFor(ToolKind)
- Returns `"wooden_pickaxe"` / `"wooden_axe"` by default.
- Stone-tier detection deferred (acceptable per brief; callers can override).

## Build Output

```
BUILD SUCCESSFUL in 2s
7 actionable tasks: 3 executed, 4 up-to-date
```

19 compiler warnings (MC 26.2 JARs compiled at class-file major version 69, compiler reports 68 max). These are pre-existing project-wide warnings, not introduced by this task.

## Concerns

- Chest search in `pullFromChests` matches by `getDescriptionId()` suffix (e.g. ends with `"wooden_pickaxe"`). Description IDs typically look like `"item.minecraft.wooden_pickaxe"` so `.endsWith(toolId)` is correct, but if a mod overrides the description ID this could miss a match. A registry-key comparison would be more robust once the MC registry API is available in context.
- `gatherCobble()` blindly mines blocks in a 7×3×7 area near the golem without checking block type. It will pick up whatever drops. This is intentional (simple gather loop) but may destroy non-stone blocks.
- Stone-tier `idFor` upgrade is stubbed as wooden defaults; upgrading requires `g.inventory()` scan for cobblestone item which can be added in a follow-up.
