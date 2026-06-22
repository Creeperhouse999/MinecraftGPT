package com.example.coppergolem.entity;

import com.example.coppergolem.craft.CraftingHelper;
import com.example.coppergolem.inventory.GolemInventory;
import com.example.coppergolem.task.TaskHandler;
import com.example.coppergolem.zone.ZoneManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.CopperGolem;

import java.util.UUID;

/**
 * Per-golem controller: owns the runtime state for one copper golem and drives
 * its current task each server tick.
 *
 * <p>Holds: the owner's UUID, the persistent {@link GolemInventory}, the
 * {@link ZoneManager} (build protection), the live {@link CopperGolem} entity,
 * an {@link ApprovalGate} (placeholder — see TODO), and the world-facing
 * {@link WorldGolemPrimitives} that task handlers act through.</p>
 *
 * <p>Task model: for B7 the controller advances a single {@link TaskHandler}.
 * B8's {@code PlanExecutor} will replace this with a multi-step plan runner; the
 * {@link #assign}/{@link #stop}/{@link #pause}/{@link #resume}/{@link #status}
 * surface stays the same.</p>
 */
public final class GolemController {

    private final UUID owner;
    private final CopperGolem golem;
    private final ServerLevel level;
    private final GolemInventory inventory;
    private final ZoneManager zones;

    private final ApprovalGate gate;
    private final WorldGolemPrimitives primitives;
    private final ToolManager toolManager;
    private final CraftingHelper crafts;

    /** Manages health (20 HP), home point, and death-respawn logic. */
    private final GolemLife life;

    /** The task currently being driven; null when idle. */
    private TaskHandler current;
    private boolean paused;

    public GolemController(UUID owner,
                           CopperGolem golem,
                           ServerLevel level,
                           GolemInventory inventory,
                           ZoneManager zones) {
        this.owner = owner;
        this.golem = golem;
        this.level = level;
        this.inventory = inventory;
        this.zones = zones;

        // TODO(B8/B12): inject the real UI-backed approval gate. Until the
        // approval UI exists, auto-approve every acquire/craft request.
        this.gate = desc -> true;

        // Wire crafting + tool management against the primitives.
        this.primitives = new WorldGolemPrimitives(golem, level, zones, inventory);
        this.crafts = new CraftingHelper(this.primitives, this.gate);
        this.primitives.setCraftingHelper(this.crafts);
        this.toolManager = new ToolManager(this.primitives, this.gate);

        // Initialise life — sets max health to 20 (10 hearts) on spawn.
        this.life = new GolemLife();
        this.life.initHealth(golem);
    }

    // -------------------------------------------------------------------------
    // Tick loop
    // -------------------------------------------------------------------------

    /**
     * Advance the current task by one tick. Clears the task when it reports
     * completion. {@code level} is passed for parity with future per-tick world
     * access; the controller already holds its bound {@link ServerLevel}.
     */
    public void tick(ServerLevel level) {
        if (current == null || paused) {
            return;
        }
        boolean done = current.tick(primitives);
        if (done) {
            current = null;
        }
    }

    // -------------------------------------------------------------------------
    // Control surface
    // -------------------------------------------------------------------------

    /** Assign a new task, replacing any in progress. */
    public void assign(TaskHandler task) {
        this.current = task;
        this.paused = false;
    }

    /** Abandon the current task. */
    public void stop() {
        this.current = null;
        this.paused = false;
    }

    /** Pause the current task (it stops advancing but is retained). */
    public void pause() {
        this.paused = true;
        if (current != null) {
            current.pause();
        }
    }

    /** Resume a paused task. */
    public void resume() {
        this.paused = false;
        if (current != null) {
            current.resume();
        }
    }

    /** Human-readable status line for the UI. */
    public String status() {
        if (current == null) {
            return "idle";
        }
        return (paused ? "[paused] " : "") + current.status();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public UUID owner() {
        return owner;
    }

    public GolemInventory inventory() {
        return inventory;
    }

    public ZoneManager zones() {
        return zones;
    }

    public CopperGolem golem() {
        return golem;
    }

    public GolemPrimitives primitives() {
        return primitives;
    }

    public ToolManager toolManager() {
        return toolManager;
    }

    public CraftingHelper crafts() {
        return crafts;
    }

    public ApprovalGate gate() {
        return gate;
    }

    /** Returns the {@link GolemLife} that owns health and home-point logic. */
    public GolemLife life() {
        return life;
    }

    /**
     * Returns the home point the golem respawns at on death.
     * Delegates to {@link GolemLife#getHomePoint()}.
     */
    public net.minecraft.core.BlockPos homePoint() {
        return life.getHomePoint();
    }

    /**
     * Sets the home point the golem respawns at on death.
     * Delegates to {@link GolemLife#setHomePoint(net.minecraft.core.BlockPos)}.
     *
     * @param pos the new respawn position
     */
    public void setHomePoint(net.minecraft.core.BlockPos pos) {
        life.setHomePoint(pos);
    }
}
