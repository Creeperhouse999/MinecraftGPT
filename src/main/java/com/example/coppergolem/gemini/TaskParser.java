package com.example.coppergolem.gemini;

import com.example.coppergolem.task.Task;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class TaskParser {
    private TaskParser() {}

    public static Task parse(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String t = o.get("task").getAsString();
            return switch (t) {
                case "sort" -> new Task.Sort(o.get("radius").getAsInt());
                case "mine" -> new Task.Mine(
                        o.get("w").getAsInt(), o.get("h").getAsInt(),
                        o.get("length").getAsInt(), o.get("dir").getAsString(),
                        o.has("filter") ? o.get("filter").getAsString() : "all");
                case "chop" -> new Task.Chop(
                        o.get("radius").getAsInt(),
                        o.has("replant") && o.get("replant").getAsBoolean());
                default -> new Task.Unknown("unknown task: " + t);
            };
        } catch (Exception e) {
            return new Task.Unknown("parse error: " + e.getMessage());
        }
    }
}
