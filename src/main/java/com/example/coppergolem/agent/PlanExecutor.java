package com.example.coppergolem.agent;

import com.example.coppergolem.craft.CraftingHelper;
import com.example.coppergolem.entity.GolemPrimitives;
import com.example.coppergolem.entity.ToolManager;
import com.example.coppergolem.gemini.GroqClient;
import com.example.coppergolem.task.*;
import com.example.coppergolem.zone.ZoneManager;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs a list of {@link PlanStep}s as the golem's active job.
 *
 * <p>Maps each step kind to a task handler:
 * <ul>
 *   <li>"sort"         → {@link SortTask}</li>
 *   <li>"mine"         → {@link MineTask}</li>
 *   <li>"chop"         → {@link ChopTask}</li>
 *   <li>"deposit"      → inline: dump inventory to nearest chest</li>
 *   <li>"acquire_tool" → inline: {@link ToolManager#ensureTool}</li>
 *   <li>"craft"        → inline: {@link CraftingHelper#craft}</li>
 *   <li>"torch"        → inline: ensure torch stock in nearest chest</li>
 * </ul>
 * </p>
 *
 * <p>Autonomy: if inventory is full mid-step, a deposit detour runs automatically
 * before resuming. Tool wear is handled internally by MineTask / ChopTask.</p>
 *
 * <p>Failure: if a step fails (handler signals done-failed or throws), the step is
 * marked {@link StepState#FAILED} and execution stops. The owner may then call
 * {@link #resumeWithInstruction}, {@link #doItYourself}, or {@link #stop}.</p>
 */
public final class PlanExecutor {

    // -------------------------------------------------------------------------
    // Public types
    // -------------------------------------------------------------------------

    /** UI colour hints: PENDING=grey, RUNNING=blue, DONE=green, FAILED=red. */
    public enum StepState { PENDING, RUNNING, DONE, FAILED }

    /** Per-step snapshot for the UI. */
    public record StepStateView(String label, StepState state) {}

    // -------------------------------------------------------------------------
    // Internal step tracking
    // -------------------------------------------------------------------------

    private static final class StepEntry {
        final PlanStep step;
        StepState state = StepState.PENDING;
        TaskHandler handler = null; // null until step starts

        StepEntry(PlanStep step) { this.step = step; }
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final List<StepEntry> entries;
    private final GroqClient ai;
    private final ZoneManager zones;
    private final ToolManager tools;
    private final CraftingHelper crafts;

    /** Index of the currently-executing step. */
    private int cursor = 0;

    /** True while a deposit detour is running (inventory-full mid-step). */
    private boolean depositDetour = false;

    /** Set when the executor is stopped (by failure or owner call). */
    private boolean stopped = false;

    /** Human-readable reason the last step failed. */
    private String errorInfo = null;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public PlanExecutor(List<PlanStep> plan,
                        GolemPrimitives g,
                        GroqClient ai,
                        ZoneManager zones,
                        ToolManager tools,
                        CraftingHelper crafts) {
        this.ai     = ai;
        this.zones  = zones;
        this.tools  = tools;
        this.crafts = crafts;

        List<StepEntry> list = new ArrayList<>(plan.size());
        for (PlanStep step : plan) list.add(new StepEntry(step));
        this.entries = list;
    }

    // -------------------------------------------------------------------------
    // Tick — called every game tick
    // -------------------------------------------------------------------------

    /**
     * Drive the current step forward by one tick.
     * Returns immediately when stopped or all steps are complete.
     */
    public void tick(GolemPrimitives g) {
        if (stopped || cursor >= entries.size()) return;

        // Autonomy: inventory-full detour (runs before/instead of normal step logic)
        if (!depositDetour && g.inventory().isStorageFull()) {
            runDepositDetour(g);
            return;
        }
        depositDetour = false;

        StepEntry entry = entries.get(cursor);

        // Initialise the handler on first tick for this step
        if (entry.handler == null) {
            entry.state = StepState.RUNNING;
            try {
                entry.handler = buildHandler(entry.step, g);
            } catch (Exception ex) {
                markFailed(entry, "Could not build handler: " + ex.getMessage());
                return;
            }
            if (entry.handler == null) {
                // Unknown kind — skip gracefully
                entry.state = StepState.DONE;
                cursor++;
                return;
            }
        }

        // Tick the handler
        boolean done;
        try {
            done = entry.handler.tick(g);
        } catch (Exception ex) {
            markFailed(entry, "Handler threw: " + ex.getMessage());
            return;
        }

        if (done) {
            // Check whether the handler finished with a failure embedded in status()
            String status = entry.handler.status();
            if (status != null && status.toLowerCase().contains("fail")) {
                markFailed(entry, status);
            } else {
                entry.state = StepState.DONE;
                cursor++;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Owner choices
    // -------------------------------------------------------------------------

    /**
     * Re-plan from the failed step using the owner's corrective instruction.
     * Splices AI-generated steps in place of the failed step and resumes.
     */
    public void resumeWithInstruction(String instr) {
        if (!stopped || cursor >= entries.size()) return;

        String context = buildWorldContext();
        String system =
            "You are a Minecraft bot planner. Given the failed step and corrective " +
            "instruction, output ONLY a JSON object with structure: " +
            "{\"plan\":[{\"kind\":\"<verb>\",\"args\":{},\"label\":\"<desc>\"}...]}\n" +
            "Valid kinds: sort, mine, chop, deposit, acquire_tool, craft, torch.\n" +
            "World context:\n" + context;
        String user = "Failed step: " + entries.get(cursor).step.label() +
                      "\nInstruction: " + instr;

        Optional<String> resp = ai.generateJson(system, user);
        List<PlanStep> newSteps = resp.map(AgentPlanner::parse)
                                      .orElse(Collections.emptyList());
        if (!newSteps.isEmpty()) {
            spliceAt(cursor, newSteps);
        }
        errorInfo = null;
        stopped = false;
    }

    /**
     * Ask the AI to autonomously plan around the error and splice the result in.
     * One AI call; does not require further owner input.
     */
    public void doItYourself() {
        if (!stopped || cursor >= entries.size()) return;

        String context = buildWorldContext();
        String system =
            "You are a Minecraft bot planner. A step failed. Re-plan autonomously " +
            "around the failure to complete the original goal. Output ONLY: " +
            "{\"plan\":[{\"kind\":\"<verb>\",\"args\":{},\"label\":\"<desc>\"}...]}\n" +
            "Valid kinds: sort, mine, chop, deposit, acquire_tool, craft, torch.\n" +
            "World context:\n" + context;
        String user = "Failed step: " + entries.get(cursor).step.label() +
                      "\nError: " + errorInfo;

        Optional<String> resp = ai.generateJson(system, user);
        List<PlanStep> newSteps = resp.map(AgentPlanner::parse)
                                      .orElse(Collections.emptyList());
        if (!newSteps.isEmpty()) {
            spliceAt(cursor, newSteps);
        }
        errorInfo = null;
        stopped = false;
    }

    /** Permanently stop execution. */
    public void stop() {
        stopped = true;
    }

    // -------------------------------------------------------------------------
    // UI view
    // -------------------------------------------------------------------------

    /** Returns a per-step snapshot suitable for UI rendering. */
    public List<StepStateView> view() {
        List<StepStateView> out = new ArrayList<>(entries.size());
        for (StepEntry e : entries) {
            out.add(new StepStateView(e.step.label(), e.state));
        }
        return Collections.unmodifiableList(out);
    }

    /** Returns the error message from the most-recent failure, or null. */
    public String errorInfo() {
        return errorInfo;
    }

    /** Returns true when the plan has finished all steps successfully. */
    public boolean isDone() {
        return !stopped && cursor >= entries.size();
    }

    /** Returns true when stopped due to a failure. */
    public boolean isFailed() {
        return stopped && errorInfo != null;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Build a TaskHandler for the given step.
     * Returns null for unknown kinds (treated as no-op / skipped).
     */
    private TaskHandler buildHandler(PlanStep step, GolemPrimitives g) {
        Map<String, String> args = step.args();
        return switch (step.kind()) {

            case "sort" -> {
                int radius = parseInt(args, "radius", 16);
                yield new SortTask(new Task.Sort(radius), ai);
            }

            case "mine" -> {
                int w      = parseInt(args, "w", 1);
                int h      = parseInt(args, "h", 2);
                int length = parseInt(args, "length", 8);
                String dir = args.getOrDefault("dir", "north");
                String filter = args.getOrDefault("filter", "");
                BlockPos origin = g.position();
                yield new MineTask(new Task.Mine(w, h, length, dir, filter), origin, tools);
            }

            case "chop" -> {
                int radius  = parseInt(args, "radius", 12);
                boolean replant = parseBool(args, "replant", false);
                yield new ChopTask(
                    new Task.Chop(radius, replant),
                    () -> g.findTreeBases(radius),
                    tools
                );
            }

            case "deposit" -> new InlineDepositHandler(g);

            case "acquire_tool" -> {
                String kindStr = args.getOrDefault("kind", "pickaxe").toUpperCase();
                ToolManager.ToolKind toolKind;
                try { toolKind = ToolManager.ToolKind.valueOf(kindStr); }
                catch (IllegalArgumentException ex) { toolKind = ToolManager.ToolKind.PICKAXE; }
                final ToolManager.ToolKind finalKind = toolKind;
                yield new SingleShotHandler(
                    "Acquire " + kindStr.toLowerCase(),
                    () -> tools.ensureTool(finalKind)
                );
            }

            case "craft" -> {
                String item  = args.getOrDefault("item", "");
                int count    = parseInt(args, "count", 1);
                yield new SingleShotHandler(
                    "Craft " + count + "x " + item,
                    () -> crafts.craft(item, count)
                );
            }

            case "torch" -> {
                int stock = parseInt(args, "stock", 16);
                yield new SingleShotHandler(
                    "Ensure " + stock + " torches",
                    () -> crafts.canCraft("minecraft:torch")
                         && crafts.craft("minecraft:torch", stock)
                );
            }

            default -> null; // unknown kind → step will be skipped
        };
    }

    /** Perform an immediate deposit to the nearest chest as a mid-task detour. */
    private void runDepositDetour(GolemPrimitives g) {
        depositDetour = true;
        List<BlockPos> chests = g.findChests(16);
        if (chests.isEmpty()) return;
        BlockPos chest = chests.get(0);
        var inv = g.inventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty()) {
                g.pushToChest(chest, stack.getItem(), stack.getCount());
            }
        }
    }

    /** Mark the current step failed and stop execution. */
    private void markFailed(StepEntry entry, String reason) {
        entry.state = StepState.FAILED;
        errorInfo   = reason;
        stopped     = true;
    }

    /**
     * Replace the step at {@code index} with all steps in {@code newSteps},
     * preserving existing entries after the replaced slot.
     */
    private void spliceAt(int index, List<PlanStep> newSteps) {
        entries.remove(index);
        int i = index;
        for (PlanStep s : newSteps) {
            entries.add(i++, new StepEntry(s));
        }
    }

    /** Build a minimal world-context string for re-planning calls. */
    private String buildWorldContext() {
        return "{\"remaining_steps\":" + (entries.size() - cursor) +
               ",\"error\":\"" + (errorInfo == null ? "" : errorInfo.replace("\"", "'")) + "\"}";
    }

    // -------------------------------------------------------------------------
    // Utility parsers
    // -------------------------------------------------------------------------

    private static int parseInt(Map<String, String> args, String key, int def) {
        String v = args.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static boolean parseBool(Map<String, String> args, String key, boolean def) {
        String v = args.get(key);
        if (v == null) return def;
        return Boolean.parseBoolean(v.trim());
    }

    // -------------------------------------------------------------------------
    // Inline task handlers
    // -------------------------------------------------------------------------

    /**
     * Deposits all non-empty inventory slots into the nearest chest.
     * Completes in one tick once a chest is reachable.
     */
    private static final class InlineDepositHandler implements TaskHandler {
        private final GolemPrimitives g;
        private boolean done = false;
        private String status = "Deposit: pending";

        InlineDepositHandler(GolemPrimitives g) { this.g = g; }

        @Override
        public boolean tick(GolemPrimitives g) {
            List<BlockPos> chests = g.findChests(16);
            if (chests.isEmpty()) {
                status = "Deposit: no chest found";
                done = true;
                return true;
            }
            BlockPos chest = chests.get(0);
            if (!g.moveTo(chest)) return false;
            var inv = g.inventory();
            int moved = 0;
            for (int i = 0; i < inv.getContainerSize(); i++) {
                var stack = inv.getItem(i);
                if (!stack.isEmpty()) {
                    moved += g.pushToChest(chest, stack.getItem(), stack.getCount());
                }
            }
            status = "Deposit: moved " + moved + " items";
            done = true;
            return true;
        }

        @Override public String status() { return status; }
        @Override public void pause()  {}
        @Override public void resume() {}
    }

    /**
     * Wraps a simple boolean supplier into a TaskHandler.
     * Executes once on the first tick; success = returns true.
     */
    private static final class SingleShotHandler implements TaskHandler {
        private final String label;
        private final java.util.function.BooleanSupplier action;
        private boolean done = false;
        private boolean success = false;

        SingleShotHandler(String label, java.util.function.BooleanSupplier action) {
            this.label  = label;
            this.action = action;
        }

        @Override
        public boolean tick(GolemPrimitives g) {
            if (!done) {
                success = action.getAsBoolean();
                done    = true;
            }
            return done;
        }

        @Override
        public String status() {
            if (!done) return label + ": running";
            return success ? label + ": done" : label + ": failed";
        }

        @Override public void pause()  {}
        @Override public void resume() {}
    }
}
