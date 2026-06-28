package com.example.coppergolem.entity;

import com.example.coppergolem.agent.AgentPlanner;
import com.example.coppergolem.agent.PlanExecutor;
import com.example.coppergolem.agent.PlanStep;
import com.example.coppergolem.craft.CraftingHelper;
import com.example.coppergolem.gemini.GroqClient;
import com.example.coppergolem.inventory.GolemInventory;
import com.example.coppergolem.net.Packets;
import com.example.coppergolem.net.ServerNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import com.example.coppergolem.task.FollowTask;
import com.example.coppergolem.task.TaskHandler;
import com.example.coppergolem.zone.ZoneManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.golem.CopperGolem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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

    /** Gate implementation: polls pendingGateItem / pendingGateAnswer each call. */
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

    /** Whether the golem is in "follow owner" mode. */
    private boolean following = false;

    /** The active FollowTask instance (kept to avoid re-creating each tick). */
    private FollowTask followTask = null;

    /** The active multi-step plan runner; null when no job is running. */
    private PlanExecutor executor;

    /** Whether the current job was started with owner pre-approval. */
    private boolean jobPreApproved;

    /** Active tool item last tick — used to detect a tool breaking (item → empty). */
    private net.minecraft.world.item.Item lastActiveToolItem = null;
    /** True once we've warned the owner about the current low-durability tool. */
    private boolean lowDurabilityWarned = false;

    // -------------------------------------------------------------------------
    // Async approval-gate state (CRITICAL-1)
    // -------------------------------------------------------------------------

    /**
     * The item description currently pending an owner decision.
     * Null when no approval is in flight.
     */
    private volatile String pendingGateItem = null;

    /**
     * Owner's answer: null = still waiting, true = approved, false = denied.
     */
    private volatile Boolean pendingGateAnswer = null;

    /**
     * The server reference used to look up the owner player for S2C sends.
     * Set once from startFromPrompt / tick; null until first use.
     */
    private MinecraftServer server = null;

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

        // Wire crafting + tool management against the primitives.
        // One CraftingHelper instance shared by both toolManager and the
        // controller (IMPORTANT-G: no duplicate helpers, gate routing is
        // always through this single controller gate).
        this.primitives = new WorldGolemPrimitives(golem, level, zones, inventory);

        // Real non-blocking approval gate (CRITICAL-1):
        // - If jobPreApproved → always true.
        // - If already answered for this item → consume & return answer.
        // - If no pending request → send AskGateS2C and return false (retry next tick).
        // - If pending but no answer yet → return false (still waiting).
        this.gate = itemDesc -> {
            if (jobPreApproved) return true;
            // Same item, answer arrived?
            if (itemDesc.equals(pendingGateItem) && pendingGateAnswer != null) {
                boolean answer = pendingGateAnswer;
                pendingGateItem  = null;
                pendingGateAnswer = null;
                return answer;
            }
            // New item request: send AskGateS2C and register pending.
            if (pendingGateItem == null) {
                pendingGateItem   = itemDesc;
                pendingGateAnswer = null;
                if (server != null) {
                    ServerPlayer ownerPlayer = server.getPlayerList().getPlayer(owner);
                    if (ownerPlayer != null) {
                        ServerNetworking.sendAskGate(ownerPlayer, itemDesc);
                    }
                }
            }
            // Not yet approved — deny this attempt; will retry on next tick.
            return false;
        };

        this.crafts = new CraftingHelper(this.primitives, this.gate);
        this.primitives.setCraftingHelper(this.crafts);
        // Three-arg constructor: share the same CraftingHelper (IMPORTANT-G).
        this.toolManager = new ToolManager(this.primitives, this.gate, this.crafts);

        // Initialise life — sets max health to 20 (10 hearts) on spawn.
        this.life = new GolemLife();
        this.life.initHealth(golem);

        // Suppress vanilla wandering/idle Brain behaviors so golem stands still
        // when no task is running. The Brain still exists but all activities cleared.
        golem.getBrain().stopAll(level, golem);
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
        // Guard: don't tick against a removed/dead entity (IMPORTANT-A).
        if (golem.isRemoved()) return;

        // Tool-break + low-durability awareness: message the owner.
        checkToolDurability();

        // Drive the active plan, if any. The executor manages its own per-step
        // task handlers; clear it once the plan finishes OR fails so a new
        // prompt can replace it (MINOR-L: clear on failed too).
        if (executor != null) {
            executor.tick(primitives);
            if (executor.isDone() || executor.isFailed()) {
                if (executor.isDone()) {
                    executor = null;
                }
                // Suppress wandering immediately after task ends
                golem.getBrain().stopAll(level, golem);
            }
            return;
        }

        // Legacy single-task path (assign()-based) — retained for callers that
        // drive a bare TaskHandler instead of a full plan.
        if (current == null || paused) {
            // Suppress vanilla Brain wandering every tick when idle
            golem.getBrain().stopAll(level, golem);
            return;
        }
        boolean done = current.tick(primitives);
        if (done) {
            current = null;
        }
    }

    /**
     * Detect tool breakage and low durability on the active tool, and notify the
     * owner in chat. A tool "breaks" when the active item changes to empty while
     * the golem is actively working. Low-durability warns once per tool.
     */
    private void checkToolDurability() {
        net.minecraft.world.item.ItemStack active = inventory.activeTool();
        net.minecraft.world.item.Item nowItem = active.isEmpty() ? null : active.getItem();

        // Tool just broke: had an item last tick, now empty/different.
        if (lastActiveToolItem != null && nowItem != lastActiveToolItem) {
            // Only treat as "broke" if the slot is now empty (vs swapped to another tool).
            if (nowItem == null) {
                msgOwner("[Golem] My tool broke! I'll try to get another.");
            }
            lowDurabilityWarned = false; // reset for the next tool
        }
        lastActiveToolItem = nowItem;

        // Low-durability warning (under 15%), once per tool.
        if (nowItem != null && !lowDurabilityWarned) {
            int pct = inventory.activeToolDurabilityPct();
            if (pct >= 0 && pct <= 15) {
                msgOwner("[Golem] My " + active.getHoverName().getString()
                        + " is low (" + pct + "%). I may need a replacement soon.");
                lowDurabilityWarned = true;
            }
        }
    }

    /** Send a chat message to the owner player if they're online. */
    private void msgOwner(String text) {
        MinecraftServer srv = server != null ? server : level.getServer();
        if (srv == null) return;
        ServerPlayer p = srv.getPlayerList().getPlayer(owner);
        if (p != null) {
            p.sendSystemMessage(net.minecraft.network.chat.Component.literal(text));
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
        this.current  = null;
        this.paused   = false;
        this.following = false;
        this.followTask = null;
    }

    /**
     * Enter "follow owner" mode: stops any running plan or task, then assigns a
     * {@link FollowTask} that continuously navigates toward the owner each tick.
     *
     * @param ownerPlayer the player to follow (must be non-null at call time;
     *                    if they go offline the task keeps the last-known goal until
     *                    their position updates again)
     */
    public void startFollow(ServerPlayer ownerPlayer) {
        stop(); // clear any running plan/task first
        following  = true;
        followTask = new FollowTask(() -> {
            // Re-resolve the player each tick so we track their live position.
            // Use the stored server reference (set by startFromPrompt / tick).
            MinecraftServer srv = server != null ? server : level.getServer();
            if (srv == null) return null;
            ServerPlayer p = srv.getPlayerList().getPlayer(owner);
            return p != null ? p.blockPosition() : null;
        });
        assign(followTask);
    }

    /**
     * Exit "follow owner" mode and go idle.
     */
    public void stopFollow() {
        following  = false;
        followTask = null;
        stop();
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
                    // Surface gate-waiting status if approval is in flight.
                    if (pendingGateItem != null && pendingGateAnswer == null) {
                        return "waiting for approval: " + pendingGateItem;
                    }
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
    /**
     * Start a new job from a freeform natural-language prompt.
     *
     * <p>Calls {@link AgentPlanner#planAsync} off the server tick thread so HTTP
     * never blocks the tick loop (CRITICAL-2). While the LLM call is in flight the
     * controller status reports "planning…". When the future resolves it is applied
     * on the server thread via {@link MinecraftServer#execute}.</p>
     */
    public void startFromPrompt(String text, boolean preApprove) {
        this.jobPreApproved = preApprove;

        if (planner == null || groq == null) {
            com.example.coppergolem.CopperGolemMod.LOG.warn(
                    "[coppergolem] startFromPrompt ignored — planner disabled (no API keys). text={}",
                    text);
            return;
        }

        // Stop any in-flight job.
        if (this.executor != null) {
            this.executor.stop();
            this.executor = null;
        }
        this.current = null;
        this.paused = false;

        // Mark "planning…" so the UI shows activity immediately.
        final String planningStatus = "planning...";
        // Use a sentinel executor that reports planning status.
        // We capture the server from the level to apply the future result.
        MinecraftServer srv = level.getServer();
        this.server = srv;

        String worldContext = buildWorldContext();

        // Fire-and-forget async call — does NOT block the tick thread.
        CompletableFuture<List<PlanStep>> future = planner.planAsync(text, worldContext);
        future.thenAccept(plan -> {
            // Apply result back on the server thread.
            if (srv != null) {
                srv.execute(() -> applyPlan(text, preApprove, plan));
            } else {
                applyPlan(text, preApprove, plan);
            }
        });

        com.example.coppergolem.CopperGolemMod.LOG.info(
                "[coppergolem] startFromPrompt async fired text={} preApprove={}", text, preApprove);
    }

    /** Applied on the server thread when planAsync completes. */
    private void applyPlan(String text, boolean preApprove, List<PlanStep> plan) {
        if (plan == null || plan.isEmpty()) {
            com.example.coppergolem.CopperGolemMod.LOG.warn(
                    "[coppergolem] planner returned no steps for prompt: {}", text);
            return;
        }
        // Stop any in-flight job (may have been replaced in the meantime).
        if (this.executor != null) {
            this.executor.stop();
        }
        this.current = null;
        this.paused = false;
        this.executor = new PlanExecutor(plan, primitives, groq, zones, toolManager, crafts,
                owner, level.getServer());
        com.example.coppergolem.CopperGolemMod.LOG.info(
                "[coppergolem] plan applied text={} preApprove={} steps={}", text, preApprove, plan.size());
    }

    /**
     * Short world-context string handed to the planner: golem position plus a
     * coarse nearby-chest count. Kept minimal for B11; richer inventory/tool
     * serialization can be layered in later.
     */
    private String buildWorldContext() {
        net.minecraft.core.BlockPos p = primitives.position();
        int nearbyChests = primitives.findChests(16).size();
        boolean hasPick = inventory.findPickaxeSlot() >= 0;
        boolean hasAxe = inventory.findAxeSlot() >= 0;
        return "{\"pos\":[" + p.getX() + "," + p.getY() + "," + p.getZ() + "]"
                + ",\"nearby_chests\":" + nearbyChests
                + ",\"has_pickaxe\":" + hasPick
                + ",\"has_axe\":" + hasAxe + "}";
    }

    /**
     * Deliver an owner approval-gate reply to the running task.
     *
     * <p>TODO(B12): route {@code approve} into the live {@link ApprovalGate}
     * future/latch so the waiting task unblocks.</p>
     */
    /**
     * Deliver an owner approval-gate reply to the running task (CRITICAL-1).
     *
     * <p>Sets {@link #pendingGateAnswer} so that the next tick the gate lambda
     * picks it up, consumes it, and returns the correct boolean to the caller.</p>
     */
    public void receiveApproval(boolean approve) {
        com.example.coppergolem.CopperGolemMod.LOG.info(
                "[coppergolem] receiveApproval approve={} pending={}", approve, pendingGateItem);
        this.pendingGateAnswer = approve;
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
        // After mutation, push updated list to owner.
        if (server != null) {
            ServerPlayer ownerPlayer = server.getPlayerList().getPlayer(owner);
            if (ownerPlayer != null) {
                java.util.List<Packets.ZoneLine> zoneList = zones.zones().stream()
                        .map(z -> new Packets.ZoneLine(z.name(), z.minX(), z.minZ(), z.maxX(), z.maxZ()))
                        .collect(java.util.stream.Collectors.toList());
                ServerNetworking.sendZoneList(ownerPlayer, zoneList);
            }
        }
    }

    /**
     * Returns the current inventory as a list of {@link Packets.SlotLine} rows
     * for the UI (non-empty slots only).
     */
    public java.util.List<Packets.SlotLine> inventoryView() {
        java.util.List<Packets.SlotLine> out = new ArrayList<>();
        for (int i = 0; i < GolemInventory.SIZE; i++) {
            net.minecraft.world.item.ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                out.add(new Packets.SlotLine(i, id, stack.getCount()));
            }
        }
        return out;
    }
}
