package com.example.coppergolem.gemini;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class KeyPoolTest {
    @Test
    void rotatesPastCoolingKeyThenRecoversAfterDuration() {
        AtomicLong now = new AtomicLong(1000);
        KeyPool pool = new KeyPool(List.of("A", "B"), now::get);

        assertEquals("A", pool.nextActiveKey().orElseThrow());
        pool.markCooling("A", 60_000);
        assertEquals("B", pool.nextActiveKey().orElseThrow());
        assertEquals(1, pool.activeCount());
        assertEquals(1, pool.coolingCount());

        pool.markCooling("B", 60_000);
        assertTrue(pool.nextActiveKey().isEmpty());   // all cooling

        now.set(1000 + 60_001);                        // time passes
        assertEquals("A", pool.nextActiveKey().orElseThrow()); // A recovered
        assertEquals(2, pool.activeCount());
    }
}
