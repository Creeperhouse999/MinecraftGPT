package com.example.coppergolem.agent;

import com.example.coppergolem.gemini.GroqClient;
import com.google.gson.*;

import java.util.*;

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
    public List<PlanStep> plan(String prompt, String worldContext) {
        String system =
            "You are a Minecraft bot planner. Given a goal and the bot's current world context, " +
            "output ONLY a JSON object with this exact structure:\n" +
            "{\"plan\":[{\"kind\":\"<verb>\",\"args\":{\"key\":\"value\",...},\"label\":\"<human description>\"}...]}\n" +
            "Valid kind values: sort, mine, chop, deposit, acquire_tool, craft, torch, ore_hunt.\n" +
            "ore_hunt: targeted ore mining. Example: {\"kind\":\"ore_hunt\",\"args\":{\"ore\":\"diamond\",\"count\":\"30\"}}. " +
            "The golem strip-mines at the optimal Y level and collects the target ore; it also picks up " +
            "incidental ores (iron, redstone, lapis, etc.) encountered along the way. " +
            "The golem will automatically acquire an iron+ pickaxe when required by the ore tier.\n" +
            "Account for tool durability, spares in inventory, and available inventory space " +
            "when choosing step counts and args.\n" +
            "Return ONLY valid JSON. No markdown, no explanation, no extra keys.\n" +
            "World context:\n" + worldContext;

        String userText = prompt;

        Optional<String> resp = ai.generateJson(system, userText);
        if (resp.isEmpty()) return Collections.emptyList();
        return parse(resp.get());
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
