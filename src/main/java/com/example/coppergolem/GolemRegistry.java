package com.example.coppergolem;

import com.example.coppergolem.entity.GolemController;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of live {@link GolemController}s.
 *
 * <p>The primary index is keyed by the <b>owner</b> UUID (one golem per owner in
 * Plan A): {@link #register}, {@link #get(UUID)}, {@link #all()}, {@link #remove(UUID)}.</p>
 *
 * <p>A secondary index keyed by the <b>entity</b> UUID is maintained for
 * {@link com.example.coppergolem.entity.GolemLife#register(Map)} — its death
 * listener looks up controllers by the dead entity's UUID and, on respawn,
 * swaps the entry to the freshly spawned golem's UUID. {@link #byEntityView()}
 * exposes that map directly so GolemLife can mutate it; this registry keeps the
 * owner index in sync via {@link #syncOwnerIndex()} called each server tick.</p>
 */
public final class GolemRegistry {

    public static final GolemRegistry INSTANCE = new GolemRegistry();

    /** owner UUID -> controller. */
    private final Map<UUID, GolemController> byOwner = new ConcurrentHashMap<>();
    /** entity UUID -> controller (mutated by GolemLife's respawn handler). */
    private final Map<UUID, GolemController> byEntity = new ConcurrentHashMap<>();

    private GolemRegistry() {}

    /** Registers (or replaces) the controller for its owner. */
    public void register(GolemController controller) {
        byOwner.put(controller.owner(), controller);
        byEntity.put(controller.golem().getUUID(), controller);
    }

    /** Returns the controller owned by {@code owner}, or null. */
    public GolemController get(UUID owner) {
        return byOwner.get(owner);
    }

    /** All live controllers (snapshot is backed by the owner index). */
    public Collection<GolemController> all() {
        return Collections.unmodifiableCollection(byOwner.values());
    }

    /** Removes the controller for {@code owner} (and its entity index entry). */
    public void remove(UUID owner) {
        GolemController c = byOwner.remove(owner);
        if (c != null) {
            byEntity.remove(c.golem().getUUID());
        }
    }

    /**
     * The entity-UUID-keyed map handed to {@link com.example.coppergolem.entity.GolemLife}.
     * GolemLife removes the dead entity's entry and inserts the respawned golem's
     * entry on death; {@link #syncOwnerIndex()} reconciles the owner index afterwards.
     */
    public Map<UUID, GolemController> byEntityView() {
        return byEntity;
    }

    /**
     * Reconciles the owner index from the entity index. Call once per server tick:
     * GolemLife's death handler updates {@link #byEntity} (entity-keyed) directly,
     * so after a respawn the owner index must be rebuilt to point at the new
     * controller. Cheap (single golem per owner; map is tiny).
     */
    public void syncOwnerIndex() {
        for (GolemController c : byEntity.values()) {
            GolemController existing = byOwner.get(c.owner());
            if (existing != c) {
                byOwner.put(c.owner(), c);
            }
        }
    }
}
