package com.example.coppergolem.gemini;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class GroqClient {
    private static final String ENDPOINT =
        "https://api.groq.com/openai/v1/chat/completions";
    private final KeyPool pool;
    private final String model;
    private final HttpClient http;

    public GroqClient(KeyPool pool, String model, HttpClient http) {
        this.pool = pool; this.model = model; this.http = http;
    }

    public Optional<String> generateJson(String systemInstruction, String userText) {
        for (int attempt = 0; attempt < pool.activeCount() + 1; attempt++) {
            Optional<String> key = pool.nextActiveKey();
            if (key.isEmpty()) return Optional.empty();      // all cooling -> block
            try {
                HttpResponse<String> resp = send(key.get(), systemInstruction, userText);
                if (resp.statusCode() / 100 != 2) { pool.markCooling(key.get(), 60_000); continue; }
                Optional<String> text = extractText(resp.body());
                if (text.isPresent()) return text;
            } catch (Exception e) {
                pool.markCooling(key.get(), 60_000);
            }
        }
        return Optional.empty();
    }

    private HttpResponse<String> send(String key, String sys, String user) throws Exception {
        JsonObject body = buildBody(sys, user);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(ENDPOINT))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + key)
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Async variant: starts the HTTP call off the calling thread and returns a
     * future that resolves to the extracted JSON string (same logic as
     * {@link #generateJson} but non-blocking).
     *
     * <p>Key rotation / cooldown is applied before the request; on a non-2xx or
     * parse error the key is marked cooling and the future resolves to empty.</p>
     */
    public CompletableFuture<Optional<String>> generateJsonAsync(String systemInstruction, String userText) {
        Optional<String> key = pool.nextActiveKey();
        if (key.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String chosenKey = key.get();
        JsonObject body = buildBody(systemInstruction, userText);
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(ENDPOINT))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + chosenKey)
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
            .handle((resp, ex) -> {
                if (ex != null || resp == null || resp.statusCode() / 100 != 2) {
                    pool.markCooling(chosenKey, 60_000);
                    return Optional.<String>empty();
                }
                Optional<String> text = extractText(resp.body());
                if (text.isEmpty()) pool.markCooling(chosenKey, 60_000);
                return text;
            });
    }

    private JsonObject buildBody(String sys, String user) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray messages = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", sys);
        messages.add(sysMsg);
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", user);
        messages.add(userMsg);
        body.add("messages", messages);
        body.addProperty("temperature", 0);
        JsonObject fmt = new JsonObject();
        fmt.addProperty("type", "json_object");
        body.add("response_format", fmt);
        return body;
    }

    private Optional<String> extractText(String responseBody) {
        try {
            JsonObject o = JsonParser.parseString(responseBody).getAsJsonObject();
            String text = o.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
            JsonParser.parseString(text); // validate it is JSON
            return Optional.of(text);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
