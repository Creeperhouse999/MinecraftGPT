package com.example.coppergolem.task;

import com.example.coppergolem.entity.GolemPrimitives;
import com.example.coppergolem.gemini.GroqClient;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Framed-chests-only sorting (Task D2).
 *
 * <p>Phase flow: SCAN → ASK → EXECUTE → DONE.
 * Unframed chests are ignored entirely as destinations. If any loose item has
 * no matching framed chest the task enters a NEEDS_FRAME pause after placing
 * what it can, surfacing a status listing the unplaceable items.
 * Calling {@link #resume()} re-runs the full SCAN → ASK → EXECUTE cycle.</p>
 */
public final class SortTask implements TaskHandler {

    private enum Phase { SCAN, ASK, ASK_WAIT, EXECUTE, DONE }

    private static final String SYS =
        "You are a Minecraft item categorizer. You are given a list of framed storage " +
        "chests where each chest has a 'frameItem' (the representative item for its whole " +
        "category, e.g. 'minecraft:dirt' means ALL dirt-variant blocks go there) and an " +
        "optional 'section' label (context from a sign above the chest). You are also given " +
        "a list of loose items that need to be sorted. Assign each loose item to exactly one " +
        "chestId whose frameItem category matches it best. A dirt chest receives dirt, " +
        "coarse_dirt, grass_block, etc. Respond with strict JSON: " +
        "{\"assignments\":{\"<item_id>\":\"<chestId>\"}}. No prose. If no chest fits an " +
        "item, map it to null.";

    private final Task.Sort spec;
    private final GroqClient ai;

    private Phase phase = Phase.SCAN;
    /** True while the golem is user-paused (via pause()). */
    private boolean paused = false;
    /** True while waiting for the owner to add a framed chest. */
    private boolean needsFrame = false;
    private String needsFrameStatus = "";
    /** Queued after buildMoves when some items had no matching chest. */
    private boolean pendingNeedsFrame = false;
    private String pendingNeedsFrameStatus = "";

    private String error;

    /** chestPos → chestId (e.g. "C0"). Only framed chests are stored here. */
    private final Map<BlockPos, String> chestIds = new LinkedHashMap<>();
    /** chestId → frame-item registry id. */
    private final Map<String, String> chestFrameItem = new HashMap<>();
    /** chestId → section text from sign above (may be empty). */
    private final Map<String, String> chestSection = new HashMap<>();
    /** Total loose items across all framed chests: item registry id → count. */
    private final Map<String, Integer> looseItems = new HashMap<>();
    /** Execution queue. */
    private Deque<SortPlanner.Move> moves = new ArrayDeque<>();

    /** In-flight async AI call; non-null only during ASK_WAIT phase. */
    private CompletableFuture<Optional<String>> aiCall = null;

    public SortTask(Task.Sort spec, GroqClient ai) {
        this.spec = spec;
        this.ai = ai;
    }

    // -------------------------------------------------------------------------
    // TaskHandler.tick
    // -------------------------------------------------------------------------

    @Override
    public boolean tick(GolemPrimitives g) {
        if (paused || needsFrame) return false;
        switch (phase) {
            case SCAN -> {
                scan(g);
                phase = Phase.ASK;
            }
            case ASK -> {
                if (chestFrameItem.isEmpty()) {
                    error = "No framed chests found in radius " + spec.radius();
                    phase = Phase.DONE;
                    break;
                }
                // Kick off the async call once, then move to ASK_WAIT.
                aiCall = startAskAiAsync();
                phase = Phase.ASK_WAIT;
            }
            case ASK_WAIT -> {
                // Poll the future each tick — never block the server thread.
                if (aiCall == null || !aiCall.isDone()) break; // still waiting
                Optional<String> resp;
                try {
                    resp = aiCall.join();
                } catch (Exception ex) {
                    resp = Optional.empty();
                }
                aiCall = null;
                if (resp.isEmpty()) {
                    error = "AI busy — all keys cooling";
                    phase = Phase.DONE;
                    break;
                }
                Map<String, String> assignments = parseAssignments(resp.get());
                if (assignments == null) {
                    error = "AI response parse failed";
                    phase = Phase.DONE;
                    break;
                }
                buildMoves(assignments);
                phase = Phase.EXECUTE;
            }
            case EXECUTE -> {
                if (moves.isEmpty()) {
                    // All placeable moves done; check if we need to pause for missing frames.
                    if (pendingNeedsFrame) {
                        needsFrame = true;
                        needsFrameStatus = pendingNeedsFrameStatus;
                        pendingNeedsFrame = false;
                    } else {
                        phase = Phase.DONE;
                    }
                    break;
                }
                executeOne(g, moves.peek());
                moves.poll();
            }
            case DONE -> { return true; }
        }
        return phase == Phase.DONE;
    }

    // -------------------------------------------------------------------------
    // SCAN — discover framed chests and their contents
    // -------------------------------------------------------------------------

    private void scan(GolemPrimitives g) {
        chestIds.clear();
        chestFrameItem.clear();
        chestSection.clear();
        looseItems.clear();

        Map<BlockPos, String> framedChests = g.readFramedChests(spec.radius());

        int idx = 0;
        for (Map.Entry<BlockPos, String> entry : framedChests.entrySet()) {
            BlockPos pos = entry.getKey();
            String frameItemId = entry.getValue();

            String cid = "C" + (idx++);
            chestIds.put(pos, cid);
            chestFrameItem.put(cid, frameItemId);
            chestSection.put(cid, g.readSignAbove(pos));

            // Collect all items in this chest as candidates to sort.
            g.readChest(pos).forEach((item, count) -> {
                Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
                if (itemId != null) {
                    looseItems.merge(itemId.toString(), count, Integer::sum);
                }
            });
        }
    }

    // -------------------------------------------------------------------------
    // ASK — send framed-chest catalogue + loose items to Groq (CRITICAL-2 async)
    // -------------------------------------------------------------------------

    /** Build the JSON payload and fire the async AI request. */
    private CompletableFuture<Optional<String>> startAskAiAsync() {
        JsonObject payload = new JsonObject();

        JsonArray chests = new JsonArray();
        for (Map.Entry<BlockPos, String> e : chestIds.entrySet()) {
            String cid = e.getValue();
            JsonObject co = new JsonObject();
            co.addProperty("chestId", cid);
            co.addProperty("frameItem", chestFrameItem.getOrDefault(cid, ""));
            String sec = chestSection.getOrDefault(cid, "");
            if (!sec.isEmpty()) co.addProperty("section", sec);
            chests.add(co);
        }
        payload.add("framedChests", chests);

        JsonObject items = new JsonObject();
        looseItems.forEach(items::addProperty);
        payload.add("looseItems", items);

        return ai.generateJsonAsync(SYS, payload.toString());
    }

    /** Parse the AI JSON response into item→chestId assignments. */
    private Map<String, String> parseAssignments(String json) {
        try {
            JsonObject assignments = JsonParser.parseString(json)
                .getAsJsonObject()
                .getAsJsonObject("assignments");
            Map<String, String> out = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : assignments.entrySet()) {
                JsonElement val = entry.getValue();
                out.put(entry.getKey(), val.isJsonNull() ? null : val.getAsString());
            }
            return out;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    // -------------------------------------------------------------------------
    // Build move queue; collect unplaceable items
    // -------------------------------------------------------------------------

    private void buildMoves(Map<String, String> assignments) {
        moves = new ArrayDeque<>();
        List<String> unplaceable = new ArrayList<>();

        for (Map.Entry<String, String> entry : assignments.entrySet()) {
            String itemId = entry.getKey();
            String targetChest = entry.getValue();
            int count = looseItems.getOrDefault(itemId, 0);
            if (count == 0) continue;

            if (targetChest == null || !chestFrameItem.containsKey(targetChest)) {
                unplaceable.add(itemId);
                continue;
            }
            // fromChest="" sentinel means "pull from all framed chests except target".
            moves.add(new SortPlanner.Move("", targetChest, itemId, count));
        }

        if (!unplaceable.isEmpty()) {
            pendingNeedsFrame = true;
            pendingNeedsFrameStatus = "no chest for: " + String.join(", ", unplaceable);
        }
    }

    // -------------------------------------------------------------------------
    // EXECUTE one move
    // -------------------------------------------------------------------------

    private void executeOne(GolemPrimitives g, SortPlanner.Move m) {
        BlockPos to = posFor(m.toChest());
        if (to == null) return;

        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(m.item()));
        int want = m.count();

        // Pull item from every framed chest that is NOT the target.
        for (Map.Entry<BlockPos, String> e : chestIds.entrySet()) {
            if (want <= 0) break;
            BlockPos from = e.getKey();
            if (from.equals(to)) continue;
            g.moveTo(from);
            want -= g.pullFromChest(from, item, want);
        }

        // Push whatever we pulled to the target chest.
        int pulled = m.count() - want;
        if (pulled > 0) {
            g.moveTo(to);
            g.pushToChest(to, item, pulled);
        }
    }

    private BlockPos posFor(String id) {
        return chestIds.entrySet().stream()
            .filter(e -> e.getValue().equals(id))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }

    // -------------------------------------------------------------------------
    // TaskHandler boilerplate
    // -------------------------------------------------------------------------

    @Override
    public String status() {
        if (needsFrame) return needsFrameStatus;
        if (error != null) return error;
        return switch (phase) {
            case SCAN     -> "Sorting: scanning framed chests";
            case ASK      -> "Sorting: planning with AI";
            case ASK_WAIT -> "Sorting: waiting for AI response";
            case EXECUTE  -> "Sorting: " + moves.size() + " moves left";
            case DONE     -> "Sorting: done";
        };
    }

    @Override
    public void pause() {
        paused = true;
    }

    /**
     * Resume after user-pause or after the owner has added a framed chest.
     * Re-runs the full SCAN → ASK → EXECUTE cycle.
     */
    @Override
    public void resume() {
        paused = false;
        needsFrame = false;
        needsFrameStatus = "";
        pendingNeedsFrame = false;
        pendingNeedsFrameStatus = "";
        error = null;
        aiCall = null;
        phase = Phase.SCAN;
        moves.clear();
    }
}
