package com.example.coppergolem.task;

import com.example.coppergolem.entity.GolemPrimitives;
import com.example.coppergolem.gemini.GroqClient;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.*;

public final class SortTask implements TaskHandler {

    private enum Phase { SCAN, ASK, PLAN, EXECUTE, DONE }

    private static final String SYS =
        "You group Minecraft item ids by similarity. Respond with strict JSON " +
        "{\"groups\":{\"<item_id>\":\"<group_name>\"}}. Group like-with-like " +
        "(logs together, ores together, food together). No prose.";

    private final Task.Sort spec;
    private final GroqClient ai;
    private Phase phase = Phase.SCAN;
    private boolean paused;
    private final Map<BlockPos, String> chestIds = new HashMap<>();
    private List<SortPlanner.ChestSnapshot> snapshots = new ArrayList<>();
    private Deque<SortPlanner.Move> moves = new ArrayDeque<>();
    private String error;

    public SortTask(Task.Sort spec, GroqClient ai) {
        this.spec = spec;
        this.ai = ai;
    }

    @Override
    public boolean tick(GolemPrimitives g) {
        if (paused) return false;
        switch (phase) {
            case SCAN -> { scan(g); phase = Phase.ASK; }
            case ASK -> {
                Map<String, String> groups = askAi();
                if (groups == null) {
                    error = "AI busy — all keys cooling";
                    phase = Phase.DONE;
                } else {
                    moves = new ArrayDeque<>(SortPlanner.plan(snapshots, groups));
                    phase = Phase.EXECUTE;
                }
            }
            case PLAN -> phase = Phase.EXECUTE;
            case EXECUTE -> {
                if (moves.isEmpty()) { phase = Phase.DONE; break; }
                executeOne(g, moves.peek());
                moves.poll();
            }
            case DONE -> { return true; }
        }
        return phase == Phase.DONE;
    }

    private void scan(GolemPrimitives g) {
        snapshots = new ArrayList<>();
        int idx = 0;
        for (BlockPos c : g.findChests(spec.radius())) {
            String id = "C" + (idx++);
            chestIds.put(c, id);
            Map<String, Integer> items = new HashMap<>();
            g.readChest(c).forEach((item, n) -> {
                Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
                if (itemId != null) {
                    items.merge(itemId.toString(), n, Integer::sum);
                }
            });
            snapshots.add(new SortPlanner.ChestSnapshot(id, items));
        }
    }

    private Map<String, String> askAi() {
        JsonObject payload = new JsonObject();
        JsonArray arr = new JsonArray();
        for (var s : snapshots) {
            JsonObject co = new JsonObject();
            co.addProperty("chest", s.chestId());
            JsonObject items = new JsonObject();
            s.items().forEach(items::addProperty);
            co.add("items", items);
            arr.add(co);
        }
        payload.add("chests", arr);
        Optional<String> resp = ai.generateJson(SYS, payload.toString());
        if (resp.isEmpty()) return null;
        try {
            JsonObject groups = JsonParser.parseString(resp.get()).getAsJsonObject()
                .getAsJsonObject("groups");
            Map<String, String> out = new HashMap<>();
            for (var e : groups.entrySet()) out.put(e.getKey(), e.getValue().getAsString());
            return out;
        } catch (Exception e) {
            return Map.of(); // empty -> no moves
        }
    }

    private void executeOne(GolemPrimitives g, SortPlanner.Move m) {
        BlockPos from = posFor(m.fromChest());
        BlockPos to = posFor(m.toChest());
        if (from == null || to == null) return;
        Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(m.item()));
        g.moveTo(from);
        g.pullFromChest(from, item, m.count());
        g.moveTo(to);
        g.pushToChest(to, item, m.count());
    }

    private BlockPos posFor(String id) {
        return chestIds.entrySet().stream()
            .filter(e -> e.getValue().equals(id))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
    }

    @Override
    public String status() {
        if (error != null) return error;
        return switch (phase) {
            case SCAN -> "Sorting: scanning chests";
            case ASK, PLAN -> "Sorting: planning";
            case EXECUTE -> "Sorting: " + moves.size() + " moves left";
            case DONE -> "Sorting: done";
        };
    }

    @Override
    public void pause() { paused = true; }

    @Override
    public void resume() { paused = false; }
}
