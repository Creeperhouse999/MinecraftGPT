# C3 Report — Iron+ Tool Tiers & Ore-Hunt Planner/Executor Wiring

## Status
COMPLETE. Build green (`BUILD SUCCESSFUL in 4s`, 100 warnings — all pre-existing major-version notes, zero errors).

## Changes

### 1. `craft/Recipes.java`
Added two 3×3 (needsTable=true) recipes:
- `minecraft:iron_pickaxe` ← 3 `iron_ingot` + 2 `stick`
- `minecraft:diamond_pickaxe` ← 3 `diamond` + 2 `stick`

### 2. `entity/ToolManager.java`
- Added imports: `CraftingHelper`, `Recipes`, `Ores`, `BuiltInRegistries`, `Identifier`.
- Added `crafts` field (`CraftingHelper`) initialised in constructor.
- Added public method `ensurePickaxeOfTier(Ores.Tier minTier)`:
  1. Scans inventory for any pickaxe whose `Ores.tierOf(path).ordinal() >= minTier.ordinal()` and equips it.
  2. Searches nearby chests (radius 16) for adequate pickaxe and pulls it.
  3. Tries to craft `diamond_pickaxe` then `iron_pickaxe` (whichever meets minTier) via `CraftingHelper` (which handles gate approval and crafting-table placement).
  4. Returns false if all paths fail.
- Added private helpers `equipPickaxeOfTierFromInventory` and `pullPickaxeOfTierFromChests`.

### 3. `task/OreHuntTask.java`
Replaced the TODO(C3) block (which called `ensureTool(PICKAXE)` then did a separate tier check) with a single call:
```java
if (!tools.ensurePickaxeOfTier(info.minTier())) {
    failed = "need " + info.minTier().name().toLowerCase() + " pickaxe";
    return true;
}
```

### 4. `agent/AgentPlanner.java`
Extended system prompt to include `ore_hunt` in the valid-kinds list and describe its JSON shape, its incidental ore mining behaviour, and the automatic iron+ pickaxe acquisition.

### 5. `agent/PlanExecutor.java`
Added `ore_hunt` case in `buildHandler`:
```java
case "ore_hunt" -> {
    String oreArg = args.getOrDefault("ore", "coal");
    int oreCount  = parseInt(args, "count", 1);
    yield new OreHuntTask(oreArg, oreCount, tools, g);
}
```

## Concerns / TODOs
- `CraftingHelper` inside `ToolManager` is constructed with a new instance; if callers also hold a `CraftingHelper`, they are separate objects (no shared state issue here, both are stateless facades over `GolemPrimitives`).
- `ensurePickaxeOfTier` does not attempt to gather raw materials (iron ingots, diamonds) before crafting — it only crafts if ingots/diamonds are already in inventory. Gathering smelted metals (iron → ingots) would require a smelting step not yet in the recipe table.
- NETHERITE tier is unhandled in the craft fallback (no recipe exists); method returns false for that tier if not already in inventory or chests.
