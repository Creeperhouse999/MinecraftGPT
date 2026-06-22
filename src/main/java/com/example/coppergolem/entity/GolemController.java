package com.example.coppergolem.entity;

import com.example.coppergolem.agent.AgentPlanner;
import com.example.coppergolem.agent.PlanExecutor;
import com.example.coppergolem.agent.PlanStep;
import com.example.coppergolem.craft.CraftingHelper;
import com.example.coppergolem.gemini.GroqClient;
import com.example.coppergolem.inventory.GolemInventory;
import com.example.coppergolem.net.Packets;
import com.example.coppergolem.task.TaskHandler;
import com.example.coppergolem.zone.ZoneManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.golem.CopperGolem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    /** Shared planner (null when no API keys configured) and its LLM client. */
    private final AgentPlanner planner;
    private final GroqClient groq;

    /** Manages health (20 HP), home point, and death-respawn logic. */
    private final GolemLife life;

    /** The task currently being driven; null when idle. */
    private TaskHandler current;
    private boolean paused;

    /** The active multi-step plan runner; null when no job is running. */
    private PlanExecutor executor;

    /** Whether the current job was started with owner pre-approval. */
    private boolean jobPreApproved;

    public GolemController(UUID owner,
                           CopperGolem golem,
                           ServerLevel level,
                           GolemInventory inventory,
                           ZoneManager zones,
                           AgentPlanner planner,
                           GroqClient groq) {
        this.owner = owner;
        this.golem = golem;
        this.level = level;
        this.inventory = inventory;
        this.zones = zones;
        this.planner = planner;
        this.groq = groq;

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
        // Drive the active plan, if any. The executor manages its own per-step
        // task handlers; clear it once the plan finishes (or stops on failure).
        if (executor != null) {
            executor.tick(primitives);
            if (executor.isDone()) {
                executor = null;
            }
            return;
        }

        // Legacy single-task path (assign()-based) — retained for callers that
        // drive a bare TaskHandler instead of a full plan.
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

    /** Abandon the current task and/or plan. */
    public void stop() {
        if (executor != null) {
            executor.stop();
            executor = null;
        }
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
        if (executor != null) {
            if (executor.isFailed()) {
                return "failed: " + executor.errorInfo();
            }
            // Surface the first RUNNING step label, else fall back to a summary.
            for (PlanExecutor.StepStateView v : executor.view()) {
                if (v.state() == PlanExecutor.StepState.RUNNING) {
                    return "running: " + v.label();
                }
            }
            return "running";
        }
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

    /** The shared planner (null when no API keys configured). */
    public AgentPlanner planner() {
        return planner;
    }

    /** The LLM client backing the planner (null when no API keys configured). */
    public GroqClient groq() {
        return groq;
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

    // -------------------------------------------------------------------------
    // Networking stubs (wired by B10 ServerNetworking; implemented in B11/B12)
    // -------------------------------------------------------------------------

    /**
     * Start a new job from a freeform natural-language prompt.
     *
     * <p>Asks the {@link AgentPlanner} to turn {@code text} into a list of
     * {@link PlanStep}s, builds a {@link PlanExecutor} over them, and makes it the
     * controller's active job. {@code preApprove} is recorded so the approval gate
     * can auto-approve this job (see {@link #jobPreApproved}; full wiring is
     * deferred to B12 — the placeholder gate currently auto-approves everything).</p>
     */
    public void startFromPrompt(String text, boolean preApprove) {
        this.jobPreApproved = preApprove;

        if (planner == null || groq == null) {
            com.example.coppergolem.CopperGolemMod.LOG.warn(
                    "[coppergolem] startFromPrompt ignored — planner disabled (no API keys). text={}",
                    text);
            return;
        }

        String worldContext = buildWorldContext();
        List<PlanStep> plan = planner.plan(text, worldContext);
        if (plan == null || plan.isEmpty()) {
            com.example.coppergolem.CopperGolemMod.LOG.warn(
                    "[coppergolem] planner returned no steps for prompt: {}", text);
            this.executor = null;
            return;
        }

        // Replace any in-flight job with the new plan.
        if (this.executor != null) {
            this.executor.stop();
        }
        this.current = null;
        this.paused = false;
        this.executor = new PlanExecutor(plan, primitives, groq, zones, toolManager, crafts);

        com.example.coppergolem.CopperGolemMod.LOG.info(
                "[coppergolem] startFromPrompt text={} preApprove={} steps={}",
                text, preApprove, plan.size());
    }

    /**
     * Short world-context string handed to the planner: golem position plus a
     * coarse nearby-chest count. Kept minimal for B11; richer inventory/tool
     * serialization can be layered in later.
     */
    private String buildWorldContext() {
        net.minecraft.core.BlockPos p = primitives.position();
        int nearbyChests = primitives.findChests(16).size();
        return "{\"pos\":[" + p.getX() + "," + p.getY() + "," + p.getZ() + "]"
                + ",\"nearby_chests\":" + nearbyChests + "}";
    }

    /**
     * Deliver an owner approval-gate reply to the running task.
     *
     * <p>TODO(B12): route {@code approve} into the live {@link ApprovalGate}
     * future/latch so the waiting task unblocks.</p>
     */
    public void receiveApproval(boolean approve) {
        // TODO(B12): unblock ApprovalGate latch
        com.example.coppergolem.CopperGolemMod.LOG.info(
                "[coppergolem] receiveApproval approve={}", approve);
    }

    /**
     * Deliver an error-recovery choice to the running task.
     *
     * <p>TODO(B11/B12): route into PlanExecutor error handler.</p>
     *
     * @param choice      short key ("retry", "skip", "abort", "custom")
     * @param instruction freeform instruction when choice is "custom"
     */
    public void receiveErrorChoice(String choice, String instruction) {
        if (executor == null) {
            com.example.coppergolem.CopperGolemMod.LOG.info(
                    "[coppergolem] receiveErrorChoice ignored — no active plan (choice={})", choice);
            return;
        }
        switch (choice == null ? "" : choice.toLowerCase()) {
            case "custom", "retry" -> executor.resumeWithInstruction(
                    instruction == null ? "" : instruction);
            case "skip", "diy", "yourself" -> executor.doItYourself();
            case "abort", "stop" -> stop();
            default -> com.example.coppergolem.CopperGolemMod.LOG.warn(
                    "[coppergolem] unknown error choice: {}", choice);
        }
    }

    /**
     * Returns the current plan as a list of {@link Packets.StepLine} rows for the
     * UI (sent via {@code ServerNetworking.sendPlanView}). Empty when idle.
     */
    public List<Packets.StepLine> planView() {
        if (executor == null) {
            return Collections.emptyList();
        }
        List<Packets.StepLine> out = new ArrayList<>();
        for (PlanExecutor.StepStateView v : executor.view()) {
            out.add(new Packets.StepLine(v.label(), v.state().name()));
        }
        return out;
    }

    /**
     * Apply a zone-edit operation from the client.
     *
     * @param op   "add", "remove", or "update"
     * @param name zone name
     */
    public void handleZoneEdit(String op, String name,
                                int minX, int minZ, int maxX, int maxZ) {
        switch (op) {
            case "add"    -> zones.addZone(
                    new com.example.coppergolem.zone.Zone(name, minX, minZ, maxX, maxZ));
            case "remove" -> zones.removeZone(name);
            case "update" -> zones.updateZone(name, minX, minZ, maxX, maxZ);
            default -> com.example.coppergolem.CopperGolemMod.LOG.warn(
                    "[coppergolem] unknown zone op: {}", op);
        }
    }
}
