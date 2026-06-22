package com.example.coppergolem.zone;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZoneManagerTest {
    @Test
    void protectsInsideRectangleRegardlessOfCornerOrder() {
        ZoneManager zm = new ZoneManager();
        zm.addZone(new Zone("base", 100, 200, 90, 190)); // min/max swapped on purpose
        assertTrue(zm.isProtected(95, 195));   // inside
        assertTrue(zm.isProtected(100, 200));  // on corner
        assertFalse(zm.isProtected(101, 195)); // x outside (max is 100)
        assertFalse(zm.isProtected(95, 201));  // z outside

        assertTrue(zm.renameZone("base", "house"));
        assertEquals("house", zm.zones().get(0).name());
        assertTrue(zm.removeZone("house"));
        assertFalse(zm.isProtected(95, 195));  // gone
    }
}
