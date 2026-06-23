# D2 Report — Framed-Chests-Only Sorting

## Files Changed

| File | Change |
|------|--------|
| `src/main/java/com/example/coppergolem/entity/GolemPrimitives.java` | Added two interface methods: `readFramedChests(int radius)` and `readSignAbove(BlockPos chest)` |
| `src/main/java/com/example/coppergolem/entity/WorldGolemPrimitives.java` | Implemented the two new methods; added imports for `ItemFrame`, `SignBlockEntity`, `SignText`, `Component` |
| `src/main/java/com/example/coppergolem/task/SortTask.java` | Full rewrite — framed-chests-only SCAN, Groq payload with `{chestId, frameItem, section}`, EXECUTE with per-item pull-from-all-others, pause-on-missing-frame |

`SortPlanner.java` and `TaskHandler.java` were not modified (SortPlanner's `Move` record reused for the execution queue).

## Real MC 26.2 API Used

### ItemFrame (`net.minecraft.world.entity.decoration.ItemFrame`)
- `level.getEntitiesOfClass(ItemFrame.class, aabb)` — queries all item-frame entities in a bounding box
- `frame.blockPosition()` — inherited from `Entity`; returns the `BlockPos` the frame is mounted on (i.e. the chest block)
- `frame.getItem()` — returns `ItemStack` held in the frame (empty stack if nothing)
- `getDirection()` — inherited from `HangingEntity`; not needed for chest matching (we match by `blockPosition()` instead)

### SignBlockEntity (`net.minecraft.world.level.block.entity.SignBlockEntity`)
- `sign.getFrontText()` — returns `SignText`
- `text.getMessage(int line, boolean filtered)` — returns `Component` for each of 4 lines (0–3)
- `component.getString()` — plain string content

### Other
- `BuiltInRegistries.ITEM.getKey(item)` → `Identifier`, `.toString()` for registry id
- `level.getBlockEntity(pos) instanceof SignBlockEntity` pattern match for sign detection

## Interface Methods Added to GolemPrimitives

```java
Map<BlockPos, String> readFramedChests(int radius);
String readSignAbove(BlockPos chest);
```

Only one implementation exists (`WorldGolemPrimitives`) — verified with grep.

## Frame Detection Logic

For each chest found by `findChests(radius)`, a `±1.5`-block AABB is queried for `ItemFrame` entities. A frame is accepted if its `blockPosition()` equals the chest `BlockPos` (item frames hang on the face of the block they're mounted to, so `blockPosition()` returns that block — the chest itself). First non-empty frame wins. Chests with no matching frame are excluded from the destination map entirely.

## Pause / Resume Mechanism

- `buildMoves()` collects items with no AI-assigned chest into `unplaceable` list
- Placeable moves are queued normally; `pendingNeedsFrame = true` + message set
- In `EXECUTE` phase: when `moves` drains, if `pendingNeedsFrame` is true, sets `needsFrame = true` + `needsFrameStatus` = "no chest for: item1, item2, ..." and returns `false` (does not advance to DONE)
- `tick()` short-circuits on `needsFrame` (returns false each tick) until `resume()` is called
- `resume()` clears all pause/frame flags, resets `phase = SCAN`, and clears the move queue — full re-scan on next tick

## Groq Payload Format

```json
{
  "framedChests": [
    {"chestId": "C0", "frameItem": "minecraft:dirt", "section": "Terrain"},
    {"chestId": "C1", "frameItem": "minecraft:oak_log"}
  ],
  "looseItems": {"minecraft:coarse_dirt": 32, "minecraft:oak_planks": 16}
}
```

System prompt instructs: frame item = whole category representative; section text is categorisation context; null assignment = no chest for that item.

## Build Output

```
> Task :clean
> Task :compileJava
> Task :build
BUILD SUCCESSFUL in 3s
```

Only pre-existing "major version 69 is newer than 68" warnings (MC 26.2 compiled with Java 21 vs project Java 21 compiler quirk). Zero errors.
