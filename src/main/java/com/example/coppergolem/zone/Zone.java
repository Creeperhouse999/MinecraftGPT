package com.example.coppergolem.zone;

/**
 * Immutable value describing a rectangular protected zone.
 * Spans the full vertical column (bedrock to sky); only X/Z are bounded.
 * Corners are stored normalized (minX <= maxX, minZ <= maxZ).
 */
public record Zone(String name, int minX, int minZ, int maxX, int maxZ) {

    /**
     * Canonical constructor — normalises corner order so callers may pass
     * corners in any order.
     */
    public Zone {
        int nx1 = Math.min(minX, maxX);
        int nx2 = Math.max(minX, maxX);
        int nz1 = Math.min(minZ, maxZ);
        int nz2 = Math.max(minZ, maxZ);
        minX = nx1;
        maxX = nx2;
        minZ = nz1;
        maxZ = nz2;
    }
}
