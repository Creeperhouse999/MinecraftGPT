package com.example.coppergolem.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

public record GolemConfig(List<String> geminiKeys, String model,
                          int sortRadius, boolean ownerBindRequired) {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static GolemConfig loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                return GSON.fromJson(Files.readString(file), GolemConfig.class);
            }
            GolemConfig def = new GolemConfig(List.of(), "llama-3.3-70b-versatile", 30, true);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(def),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return def;
        } catch (IOException e) {
            throw new RuntimeException("failed to load " + file, e);
        }
    }
}
