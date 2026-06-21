package com.example.coppergolem.task;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SortPlannerTest {
    @Test
    void consolidatesStraysIntoMajorityChest() {
        var chestA = new SortPlanner.ChestSnapshot("A", Map.of("oak_log", 40, "stone", 5));
        var chestB = new SortPlanner.ChestSnapshot("B", Map.of("oak_log", 8, "stone", 50));
        // groups: logs -> "wood", stone -> "stone"
        Map<String,String> groups = Map.of("oak_log", "wood", "stone", "stone");

        List<SortPlanner.Move> moves =
            SortPlanner.plan(List.of(chestA, chestB), groups);

        // wood home = A (40 > 8). stone home = B (50 > 5).
        // Expect: move 8 oak_log B->A, move 5 stone A->B.
        assertTrue(moves.contains(new SortPlanner.Move("B", "A", "oak_log", 8)));
        assertTrue(moves.contains(new SortPlanner.Move("A", "B", "stone", 5)));
        assertEquals(2, moves.size());
    }
}
