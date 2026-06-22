package com.example.coppergolem.zone;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages a collection of named rectangular protection zones.
 *
 * <p>NBT persistence (writeNbt / readNbt) is stubbed with TODO comments;
 * no Minecraft classes are imported so this compiles and unit-tests without
 * the game on the classpath.
 */
public class ZoneManager {

    private final List<Zone> zones = new ArrayList<>();

    // ── Mutation ──────────────────────────────────────────────────────────

    public void addZone(Zone z) {
        zones.add(z);
    }

    /**
     * Removes the first zone whose name matches {@code name}.
     *
     * @return true if a zone was removed, false if no match found.
     */
    public boolean removeZone(String name) {
        return zones.removeIf(z -> z.name().equals(name));
    }

    /**
     * Renames the first zone whose name matches {@code oldName} to
     * {@code newName}.
     *
     * @return true if a zone was renamed, false if no match found.
     */
    public boolean renameZone(String oldName, String newName) {
        for (int i = 0; i < zones.size(); i++) {
            Zone z = zones.get(i);
            if (z.name().equals(oldName)) {
                zones.set(i, new Zone(newName, z.minX(), z.minZ(), z.maxX(), z.maxZ()));
                return true;
            }
        }
        return false;
    }

    /**
     * Replaces the bounds of an existing zone identified by {@code name}.
     * Does nothing if the name is not found.
     */
    public void updateZone(String name, int minX, int minZ, int maxX, int maxZ) {
        for (int i = 0; i < zones.size(); i++) {
            if (zones.get(i).name().equals(name)) {
                zones.set(i, new Zone(name, minX, minZ, maxX, maxZ));
                return;
            }
        }
    }

    // ── Query ─────────────────────────────────────────────────────────────

    /** Returns an unmodifiable view of all registered zones. */
    public List<Zone> zones() {
        return List.copyOf(zones);
    }

    /**
     * Returns true if the block column at (x, z) falls inside any registered
     * zone (inclusive bounds on both axes).
     */
    public boolean isProtected(int x, int z) {
        for (Zone zone : zones) {
            if (x >= zone.minX() && x <= zone.maxX()
                    && z >= zone.minZ() && z <= zone.maxZ()) {
                return true;
            }
        }
        return false;
    }

    // ── NBT persistence (stubbed — no Minecraft imports) ──────────────────

    /**
     * TODO: persist zones to an NbtCompound for server saved-data.
     * Signature will be writeNbt(NbtCompound nbt) once MC is on classpath.
     */
    public void writeNbt(/* NbtCompound nbt */) {
        // stub
    }

    /**
     * TODO: restore zones from an NbtCompound for server saved-data.
     * Signature will be static ZoneManager readNbt(NbtCompound nbt) once MC
     * is on classpath.
     */
    public static ZoneManager readNbt(/* NbtCompound nbt */) {
        // stub
        return new ZoneManager();
    }
}
