package com.example.coppergolem.task;

import java.util.*;

public final class SortPlanner {
    private SortPlanner() {}

    public record ChestSnapshot(String chestId, Map<String,Integer> items) {}
    public record Move(String fromChest, String toChest, String item, int count) {}

    public static List<Move> plan(List<ChestSnapshot> chests, Map<String,String> itemToGroup) {
        // total per (group, chest)
        Map<String, Map<String,Integer>> groupChestTotals = new HashMap<>();
        for (ChestSnapshot c : chests) {
            for (var e : c.items().entrySet()) {
                String group = itemToGroup.get(e.getKey());
                if (group == null) continue;
                groupChestTotals
                    .computeIfAbsent(group, g -> new HashMap<>())
                    .merge(c.chestId(), e.getValue(), Integer::sum);
            }
        }
        // home chest per group: max total, tie -> lowest chestId
        Map<String,String> groupHome = new HashMap<>();
        for (var ge : groupChestTotals.entrySet()) {
            String home = ge.getValue().entrySet().stream()
                .sorted((a,b) -> {
                    int byCount = Integer.compare(b.getValue(), a.getValue());
                    return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
                })
                .findFirst().map(Map.Entry::getKey).orElseThrow();
            groupHome.put(ge.getKey(), home);
        }
        // moves: every stack not in its group's home
        List<Move> moves = new ArrayList<>();
        for (ChestSnapshot c : chests) {
            for (var e : c.items().entrySet()) {
                String group = itemToGroup.get(e.getKey());
                if (group == null) continue;
                String home = groupHome.get(group);
                if (!home.equals(c.chestId())) {
                    moves.add(new Move(c.chestId(), home, e.getKey(), e.getValue()));
                }
            }
        }
        moves.sort(Comparator.comparing(Move::item).thenComparing(Move::fromChest));
        return moves;
    }
}
