package com.example.coppergolem.entity;

import com.example.coppergolem.inventory.GolemInventory;
import com.example.coppergolem.zone.ZoneManager;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Manages the copper golem's health (20 HP / 10 hearts), home point, and
 * death-respawn cycle.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Set max-health = 20 and full-health on spawn.</li>
 *   <li>Hold the home {@link BlockPos} that the golem respawns at on death.</li>
 *   <li>On death: spawn a fresh copper golem at {@link #homePoint}, set 20 HP,
 *       transfer inventory + zones + owner UUID from the dead controller.</li>
 *   <li>Register a Fabric {@code ServerLivingEntityEvents.AFTER_DEATH} listener
 *       (verified on classpath: fabric-entity-events-v1 5.0.5, interface
 *       {@code AfterDeath.afterDeath(LivingEntity, DamageSource)}).</li>
 * </ul>
 *
 * <h3>Inventory kept on death</h3>
 * The Fabric attachment {@code GolemAttachments.GOLEM_DATA} is declared with
 * {@code .copyOnDeath()} — but this copies to the *same* entity after respawn
 * from player death, not to a newly spawned entity.  For the golem case we
 * explicitly copy every slot from the old {@link GolemInventory} into a new one
 * that is handed to the fresh {@link GolemController}, so nothing is dropped.
 */
public final class GolemLife {

    /** Default home point — owner should override via {@link #setHomePoint}. */
    private static final BlockPos DEFAULT_HOME = new BlockPos(-5616, 64, 3872);

    /** Where the golem respawns after death. */
    private BlockPos homePoint;

    /**
     * Constructs a GolemLife with the default home point.
     * Call {@link #initHealth(CopperGolem)} immediately after the entity spawns.
     */
    public GolemLife() {
        this.homePoint = DEFAULT_HOME;
    }

    /**
     * Constructs a GolemLife with an explicit home point.
     *
     * @param homePoint the block the golem respawns at on death
     */
    public GolemLife(BlockPos homePoint) {
        this.homePoint = homePoint;
    }

    // -------------------------------------------------------------------------
    // Health initialisation
    // -------------------------------------------------------------------------

    /**
     * Sets the copper golem's max health to 20 (10 hearts) and fills HP to full.
     *
     * <p>API verified against MC 26.2 minecraft-common.jar (Mojang mappings):</p>
     * <ul>
     *   <li>{@code Attributes.MAX_HEALTH} — {@code Holder<Attribute>} field</li>
     *   <li>{@code LivingEntity.getAttribute(Holder<Attribute>)} → {@code AttributeInstance}</li>
     *   <li>{@code AttributeInstance.setBaseValue(double)}</li>
     *   <li>{@code LivingEntity.setHealth(float)}</li>
     * </ul>
     *
     * @param golem the copper golem entity that just spawned
     */
    public void initHealth(CopperGolem golem) {
        var attr = golem.getAttribute(Attributes.MAX_HEALTH);
        if (attr != null) {
            attr.setBaseValue(20.0);
        }
        golem.setHealth(20.0f);
    }

    // -------------------------------------------------------------------------
    // Home point
    // -------------------------------------------------------------------------

    /** Returns the current home / respawn point. */
    public BlockPos getHomePoint() {
        return homePoint;
    }

    /** Sets the home / respawn point. */
    public void setHomePoint(BlockPos homePoint) {
        this.homePoint = homePoint;
    }

    // -------------------------------------------------------------------------
    // Respawn
    // -------------------------------------------------------------------------

    /**
     * Spawns a fresh copper golem at {@link #homePoint}, sets 20 HP, and
     * transfers the old controller's inventory and zones into a new
     * {@link GolemController} bound to the same owner.
     *
     * <p>Inventory is copied slot-by-slot from the old {@link GolemInventory};
     * no items are dropped.  The {@link ZoneManager} instance is re-used
     * directly (it is not entity-bound).</p>
     *
     * <p>Called by {@link #register()}'s death listener when the dead entity
     * belongs to a tracked controller.</p>
     *
     * @param level      the server level to spawn in
     * @param controller the {@link GolemController} whose golem just died
     * @return the new {@link GolemController}, or {@code null} if the spawn failed
     */
    public GolemController respawn(ServerLevel level, GolemController controller) {
        // Spawn fresh copper golem at home point.
        // PostSpawnProcessor<T> has a single-arg apply(T) method (verified in 26.2 jar).
        CopperGolem fresh = net.minecraft.world.entity.EntityTypes.COPPER_GOLEM
                .spawn(level, entity -> {}, homePoint,
                        EntitySpawnReason.COMMAND, false, false);
        if (fresh == null) {
            return null;
        }

        // Set 20 HP on the new entity.
        initHealth(fresh);

        // Copy inventory slot-by-slot (kept — nothing dropped).
        GolemInventory oldInv = controller.inventory();
        GolemInventory newInv = new GolemInventory();
        for (int i = 0; i < GolemInventory.SIZE; i++) {
            ItemStack stack = oldInv.getItem(i);
            if (!stack.isEmpty()) {
                newInv.setItem(i, stack.copy());
            }
        }

        // Re-use the ZoneManager and owner UUID.
        UUID owner = controller.owner();
        ZoneManager zones = controller.zones();

        // Build the new controller; GolemLife will be wired up by the new
        // controller's constructor call to initHealth above.
        GolemController newController = new GolemController(
                owner, fresh, level, newInv, zones,
                controller.planner(), controller.groq());

        // Carry the same home point into the new GolemLife.
        newController.life().setHomePoint(homePoint);

        return newController;
    }

    // -------------------------------------------------------------------------
    // Death event registration
    // -------------------------------------------------------------------------

    /**
     * Registers a {@link ServerLivingEntityEvents#AFTER_DEATH} listener that
     * triggers {@link #respawn} when a tracked copper golem dies.
     *
     * <p>Event API verified on classpath (fabric-entity-events-v1 5.0.5+):</p>
     * <ul>
     *   <li>Class: {@code net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents}</li>
     *   <li>Field: {@code static Event<AfterDeath> AFTER_DEATH}</li>
     *   <li>Interface: {@code AfterDeath.afterDeath(LivingEntity entity, DamageSource source)}</li>
     * </ul>
     *
     * <p>The registry of live controllers is supplied as the {@code registry}
     * parameter so this method stays testable without global state.  In practice
     * pass {@link com.example.coppergolem.entity.GolemRegistry#getInstance()} or
     * equivalent once B11 wires a registry; for now pass an empty map and
     * controllers are registered after spawn via the caller.</p>
     *
     * <p>TODO(B11): connect the real GolemRegistry so the death handler can look
     * up and replace the controller by entity UUID.</p>
     *
     * @param registry mutable map of entity UUID → GolemController to update on death
     */
    public static void register(java.util.Map<UUID, GolemController> registry) {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof CopperGolem golem)) {
                return;
            }
            UUID entityId = golem.getUUID();
            GolemController controller = registry.get(entityId);
            if (controller == null) {
                return;
            }

            ServerLevel level = (ServerLevel) golem.level();
            GolemLife life = controller.life();
            GolemController newController = life.respawn(level, controller);
            if (newController != null) {
                // Replace the dead entry with the fresh controller.
                registry.remove(entityId);
                registry.put(newController.golem().getUUID(), newController);
            }
        });
    }
}
