package com.example.coppergolem.agent;

import com.example.coppergolem.gemini.GroqClient;
import com.google.gson.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Turns a natural-language prompt into an ordered list of {@link PlanStep}s
 * by asking a Groq LLM to return strict JSON.
 */
public final class AgentPlanner {

    private final GroqClient ai;

    public AgentPlanner(GroqClient ai) {
        this.ai = ai;
    }

    /**
     * Calls the LLM and parses the result into a plan.
     *
     * @param prompt       user goal, e.g. "dig a 3x3 tunnel north 16 blocks"
     * @param worldContext serialized inventory / tool state for resource-aware planning
     * @return ordered plan steps, or empty list on any failure
     */
    private static String buildSystem(String worldContext) {
        return
            "You are a Minecraft bot planner. Given a goal and the bot's current world context, " +
            "output ONLY a JSON object with this exact structure:\n" +
            "{\"plan\":[{\"kind\":\"<verb>\",\"args\":{\"key\":\"value\",...},\"label\":\"<human description>\"}...]}\n" +
            "Valid kind values ONLY: sort, mine, chop, deposit, acquire_tool, craft, torch, ore_hunt, follow, come, attack, defend, chat, bring.\n" +
            "- sort: sort nearby chests. No args needed.\n" +
            "- mine: mine blocks. Args: material (cobblestone/dirt/stone/gravel), count (number).\n" +
            "- chop: chop trees. Args: count (number of trees).\n" +
            "- ore_hunt: targeted ore mining. Args: ore (coal/iron/gold/diamond/redstone/copper/emerald), count (number).\n" +
            "- deposit: dump inventory to nearest chest. No args needed.\n" +
            "- acquire_tool: get or craft a tool. Args: tool (pickaxe/axe).\n" +
            "- craft: craft an item. Args: item (torch/stick/etc), count.\n" +
            "- follow: follow the owner continuously. No args. Return {\"kind\":\"follow\",\"args\":{},\"label\":\"Follow owner\"}\n" +
            "- come: move to owner once and stop. No args. Return {\"kind\":\"come\",\"args\":{},\"label\":\"Come to owner\"}\n" +
            "- attack: attack nearby hostile mobs until area is clear. No args. Return {\"kind\":\"attack\",\"args\":{},\"label\":\"Attack hostiles\"}\n" +
            "- defend: continuously attack any hostile that comes near. No args. Return {\"kind\":\"defend\",\"args\":{},\"label\":\"Defend mode\"}\n" +
            "- chat: reply to any question, greeting, or info request. Args: message (full answer). Use label as SHORT summary max 60 chars. Use for: hi/hello, 'how do I craft X', 'what can you do', 'how to make Y', any general question.\n" +
            "- bring: find item and bring it to owner. Args: item (minecraft item id e.g. minecraft:iron_ingot), count (number). Priority: search chests first, then craft, then mine. Use for 'bring me X', 'get me X', 'fetch X'.\n" +
            "IMPORTANT RULES:\n" +
            "1. If the goal is a question or greeting (hi, hello, how to craft X, what is Y, what can you do, etc.), ALWAYS use 'chat' kind. Put full answer in message arg.\n" +
            "2. If the goal is NOT one of the above tasks (e.g. 'build', 'place'), " +
            "return {\"plan\":[{\"kind\":\"unknown\",\"args\":{},\"label\":\"Task not supported: <goal>\"}]}\n" +
            "2. NEVER plan to mine near the bot's current position (within 10 blocks) - always move away first.\n" +
            "3. For mining tasks, only mine cobblestone/stone/dirt/gravel - NOT building materials or ores (use ore_hunt for ores).\n" +
            "4. Keep plans simple: 1-3 steps maximum.\n" +
            "Account for tool durability, spares in inventory, and available inventory space.\n" +
            "Return ONLY valid JSON. No markdown, no explanation, no extra keys.\n" +
            "World context:\n" + worldContext;
    }

    public List<PlanStep> plan(String prompt, String worldContext) {
        String system = buildSystem(worldContext);

        String userText = prompt;

        Optional<String> resp = ai.generateJson(system, userText);
        if (resp.isEmpty()) return Collections.emptyList();
        return parse(resp.get());
    }

    /**
     * Non-blocking variant of {@link #plan}: returns a future that resolves when
     * the LLM responds. Does NOT block the calling thread.
     *
     * @param prompt       user goal
     * @param worldContext serialised inventory / tool state
     * @return future resolving to ordered plan steps (empty on failure)
     */
    public CompletableFuture<List<PlanStep>> planAsync(String prompt, String worldContext) {
        String system = buildSystem(worldContext);

        return ai.generateJsonAsync(system, prompt)
                .thenApply(opt -> opt.map(AgentPlanner::parse).orElse(Collections.emptyList()));
    }

    /**
     * Pure parser — converts a Groq JSON response into plan steps.
     * Malformed or missing fields → empty list (never throws).
     */
    public static List<PlanStep> parse(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray planArray = root.getAsJsonArray("plan");
            if (planArray == null) return Collections.emptyList();

            List<PlanStep> steps = new ArrayList<>(planArray.size());
            for (JsonElement elem : planArray) {
                JsonObject obj = elem.getAsJsonObject();
                String kind = obj.get("kind").getAsString();
                String label = obj.get("label").getAsString();

                Map<String, String> args = new LinkedHashMap<>();
                JsonElement argsElem = obj.get("args");
                if (argsElem != null && argsElem.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : argsElem.getAsJsonObject().entrySet()) {
                        args.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }

                steps.add(new PlanStep(kind, Collections.unmodifiableMap(args), label));
            }
            return Collections.unmodifiableList(steps);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
