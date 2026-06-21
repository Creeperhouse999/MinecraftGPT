package com.example.coppergolem.gemini;

import java.util.*;
import java.util.function.LongSupplier;

public final class KeyPool {
    private final List<String> keys;
    private final Map<String, Long> coolingUntil = new HashMap<>();
    private final LongSupplier clockMs;

    public KeyPool(List<String> keys, LongSupplier clockMs) {
        if (keys == null || keys.isEmpty()) throw new IllegalArgumentException("no keys");
        this.keys = List.copyOf(keys);
        this.clockMs = clockMs;
    }

    private boolean cooling(String key) {
        Long until = coolingUntil.get(key);
        return until != null && clockMs.getAsLong() < until;
    }

    public Optional<String> nextActiveKey() {
        for (String k : keys) if (!cooling(k)) return Optional.of(k);
        return Optional.empty();
    }

    public void markCooling(String key, long durationMs) {
        coolingUntil.put(key, clockMs.getAsLong() + durationMs);
    }

    public int activeCount() { return (int) keys.stream().filter(k -> !cooling(k)).count(); }
    public int coolingCount() { return keys.size() - activeCount(); }
}
