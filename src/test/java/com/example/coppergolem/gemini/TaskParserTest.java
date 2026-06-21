package com.example.coppergolem.gemini;

import com.example.coppergolem.task.Task;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskParserTest {
    @Test
    void parsesSortMineChopAndRejectsGarbage() {
        assertEquals(new Task.Sort(30),
            TaskParser.parse("{\"task\":\"sort\",\"radius\":30}"));
        assertEquals(new Task.Mine(3, 3, 16, "north", "all"),
            TaskParser.parse("{\"task\":\"mine\",\"w\":3,\"h\":3,\"length\":16,\"dir\":\"north\",\"filter\":\"all\"}"));
        assertEquals(new Task.Chop(12, true),
            TaskParser.parse("{\"task\":\"chop\",\"radius\":12,\"replant\":true}"));
        assertTrue(TaskParser.parse("not json") instanceof Task.Unknown);
        assertTrue(TaskParser.parse("{\"task\":\"fly\"}") instanceof Task.Unknown);
    }
}
