# Copper Golem AI Bot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Fabric mod (MC 26.2) where the owner assigns natural-language tasks via a private UI; Groq (Llama) parses them; a copper golem sorts chests, mines areas, and chops trees — while friends install nothing.

**Architecture:** Server-side Fabric mod runs the golem AI, Groq client, key pool, and tasks. Owner-only client mod adds a keybind + control/inventory UI talking to the server over custom packets. The golem is a real vanilla Copper Golem (Plan A) so unmodded friends render it; if 26.2 makes that impractical, fall back to bodiless server-side automation (Plan B). Pure logic (KeyPool, TaskParser) is TDD'd off-MC; MC-coupled code is verified manually with thin mocks.

**Tech Stack:** Java 21, Fabric Loader 0.19.3, Fabric API 0.152.1+26.2, Loom 1.17, Gradle 9.5.1. Groq (`llama-3.3-70b-versatile`) via OpenAI-compatible REST (`https://api.groq.com/openai/v1/chat/completions`) + `java.net.http.HttpClient`. JSON via Gson. JUnit 5 for tests.

## Global Constraints

- **MC version:** 26.2 exactly. MC 26.1+ is **unobfuscated** — no Yarn/Intermediary mappings; use the `net.fabricmc.fabric-loom` plugin (no remap).
- **Java 21.**
- **Friends install nothing** — golem must be a vanilla entity (Plan A) or no entity (Plan B). A custom entity is **forbidden** (would force friend installs).
- **Owner-only control** — server validates owner UUID on every control packet; reject others. No chat commands.
- **API keys** live only in `config/golem.json` (server-side). Never in source, never committed. `config/golem.json` MUST be in `.gitignore`.
- **Groq calls:** ≤1 per assigned task (sort may batch). No per-step reasoning loop. Groq free limits are per-account (30 RPM / 1000 RPD); the key pool guards against a revoked/flagged key, not for quota multiplication.
- **Mod id:** `coppergolem`. Base package: `com.example.coppergolem`.
- **DRY, YAGNI, TDD, frequent commits.**

---

## File Structure

```
build.gradle                                  (Loom, deps)
settings.gradle
gradle.properties                             (versions)
.gitignore                                    (excludes config/golem.json)
src/main/resources/fabric.mod.json            (entrypoints, mod meta)
src/main/java/com/example/coppergolem/
  CopperGolemMod.java                         (main entrypoint)
  config/GolemConfig.java                     (loads config/golem.json)
  gemini/KeyPool.java                         (key rotation/cooling — pure)
  gemini/GroqClient.java                      (HTTP, retry, uses KeyPool)
  gemini/TaskParser.java                      (AI JSON -> Task — pure)
  task/Task.java                              (typed task records)
  task/TaskDispatcher.java                    (Task -> handler)
  task/TaskHandler.java                       (tickable state-machine iface)
  task/SortTask.java
  task/MineTask.java
  task/ChopTask.java
  entity/GolemController.java                 (binds AI+inv to vanilla golem OR bodiless)
  entity/GolemPrimitives.java                 (moveTo/mineBlock/place/chestIO iface)
  inventory/GolemInventory.java               (27-slot, saved data)
  net/Packets.java                            (Prompt/Stop/Pause/Status payloads — shared)
  net/ServerNetworking.java                   (server packet handlers)
src/client/java/com/example/coppergolem/client/
  GolemClientMod.java                         (client entrypoint)
  GolemKeybind.java
  screen/GolemControlScreen.java
  screen/GolemInventoryScreen.java
  net/ClientNetworking.java
src/test/java/com/example/coppergolem/
  gemini/KeyPoolTest.java
  gemini/TaskParserTest.java
  task/SortPlannerTest.java
config/golem.json                             (gitignored; created by owner)
```

---

## Phase 0 — Toolchain + Plan A/B Gate (HARD GO/NO-GO)

This phase decides whether the golem has a vanilla body (Plan A) or is bodiless (Plan B). **No task code until this passes.**

### Task 0: Scaffold buildable mod + verify 26.2 toolchain

**Files:**
- Create: `build.gradle`, `settings.gradle`, `gradle.properties`, `.gitignore`, `src/main/resources/fabric.mod.json`, `src/main/java/com/example/coppergolem/CopperGolemMod.java`

**Interfaces:**
- Produces: a mod that builds and loads, logging on init. Mod id `coppergolem`.

- [ ] **Step 1: Create `gradle.properties` with verified versions**

```properties
org.gradle.jvmargs=-Xmx2G
minecraft_version=26.2
loader_version=0.19.3
fabric_version=0.152.1+26.2
loom_version=1.17-SNAPSHOT
mod_version=0.1.0
maven_group=com.example
archives_base_name=coppergolem
```

- [ ] **Step 2: Create `settings.gradle`**

```gradle
pluginManagement {
    repositories {
        maven { url = 'https://maven.fabricmc.net/' }
        gradlePluginPortal()
    }
}
```

- [ ] **Step 3: Create `build.gradle` (no-remap Loom plugin)**

```gradle
plugins {
    id 'net.fabricmc.fabric-loom' version "${loom_version}"
    id 'java'
}
version = project.mod_version
group = project.maven_group

repositories { mavenCentral() }

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"
    implementation 'com.google.code.gson:gson:2.11.0'
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
}

loom {
    splitEnvironmentSourceSets()
    mods { "coppergolem" { sourceSet sourceSets.main; sourceSet sourceSets.client } }
}

java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
test { useJUnitPlatform() }
```

> Note: MC 26.1+ is unobfuscated; the `fabric-loom` plugin does not remap. If `loom_version` `1.17-SNAPSHOT` fails to resolve, pin the latest released `1.17.x` from maven.fabricmc.net.

- [ ] **Step 4: Create `.gitignore`**

```
.gradle/
build/
run/
config/golem.json
*.class
```

- [ ] **Step 5: Create `fabric.mod.json`**

```json
{
  "schemaVersion": 1,
  "id": "coppergolem",
  "version": "${version}",
  "name": "Copper Golem AI Bot",
  "environment": "*",
  "entrypoints": {
    "main": ["com.example.coppergolem.CopperGolemMod"],
    "client": ["com.example.coppergolem.client.GolemClientMod"]
  },
  "depends": { "fabricloader": ">=0.19.3", "minecraft": "26.2", "java": ">=21" }
}
```

- [ ] **Step 6: Create entrypoint `CopperGolemMod.java`**

```java
package com.example.coppergolem;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CopperGolemMod implements ModInitializer {
    public static final String MOD_ID = "coppergolem";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOG.info("[coppergolem] server init");
    }
}
```

- [ ] **Step 7: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. If version/loom resolution fails, this is the toolchain checkpoint — pin nearest released versions from maven.fabricmc.net before proceeding.

- [ ] **Step 8: Commit**

```bash
git init
git add -A
git commit -m "chore: scaffold Fabric 26.2 mod, verify toolchain"
```

### Task 0b: Plan A/B spike — can we attach AI + inventory to a vanilla Copper Golem?

**Goal:** Decide Plan A (vanilla body) vs Plan B (bodiless). This is a research+spike task, not TDD.

- [ ] **Step 1: Inspect the unobfuscated copper golem class**

Because 26.1+ is unobfuscated, the entity class is human-readable in the Loom-decompiled sources. Locate the copper golem entity class (search decompiled `net.minecraft.entity` for `CopperGolem`). Confirm: (a) it is spawnable via server commands/code, (b) its goal selector / brain is accessible to add or replace AI goals, (c) we can attach persistent data (Fabric `AttachmentType` from Fabric API) keyed by entity UUID.

- [ ] **Step 2: In-game spike**

Add a temporary debug command `/golem spike` that: spawns a vanilla copper golem, attaches an `AttachmentType<NbtCompound>` to it, writes a marker, reloads the chunk, reads it back, logs success. Run a dev client (`./gradlew runClient`), execute the command, confirm the attachment survives a save/reload.

- [ ] **Step 3: Decision gate**

- If both succeed → **Plan A**. Record in plan: golem = vanilla copper golem + attachment inventory + injected goals.
- If attaching goals OR persistent data to the vanilla entity is impractical → **Plan B (bodiless)**: no entity; tasks run as server-side world operations under the owner's identity; inventory lives in world saved data keyed by owner UUID. A custom entity remains forbidden.

- [ ] **Step 4: Remove the spike command, commit the decision**

```bash
git add -A
git commit -m "spike: decide Plan A vs B for golem body"
```

Document the choice at the top of this plan before continuing. **All later tasks that say "the golem" mean the Plan A entity OR the Plan B bodiless actor; `GolemController` abstracts the difference.**

---

## Phase 1 — Pure Logic (TDD, no Minecraft)

### Task 1: KeyPool — rotation & cooling

**Files:**
- Create: `src/main/java/com/example/coppergolem/gemini/KeyPool.java`
- Test: `src/test/java/com/example/coppergolem/gemini/KeyPoolTest.java`

**Interfaces:**
- Produces:
  - `KeyPool(List<String> keys, LongSupplier clockMs)` — clock injected for tests.
  - `Optional<String> nextActiveKey()` — first key not cooling, else empty.
  - `void markCooling(String key, long durationMs)` — cools that key until `now+duration`.
  - `int activeCount()` / `int coolingCount()`.

- [ ] **Step 1: Write the failing test**

```java
package com.example.coppergolem.gemini;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class KeyPoolTest {
    @Test
    void rotatesPastCoolingKeyThenRecoversAfterDuration() {
        AtomicLong now = new AtomicLong(1000);
        KeyPool pool = new KeyPool(List.of("A", "B"), now::get);

        assertEquals("A", pool.nextActiveKey().orElseThrow());
        pool.markCooling("A", 60_000);
        assertEquals("B", pool.nextActiveKey().orElseThrow());
        assertEquals(1, pool.activeCount());
        assertEquals(1, pool.coolingCount());

        pool.markCooling("B", 60_000);
        assertTrue(pool.nextActiveKey().isEmpty());   // all cooling

        now.set(1000 + 60_001);                        // time passes
        assertEquals("A", pool.nextActiveKey().orElseThrow()); // A recovered
        assertEquals(2, pool.activeCount());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests KeyPoolTest`
Expected: FAIL — `KeyPool` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.coppergolem.gemini;

import java.util.*;
import java.util.function.LongSupplier;

public final class KeyPool {
    private final List<String> keys;
    private final Map<String, Long> coolingUntil = new HashMap<>();
    private final LongSupplier clockMs;

    public KeyPool(List<String> keys, LongSupplier clockMs) {
        if (keys == null || keys.isEmpty()) throw new IllegalArgumentException("no keys");
        this.keys = List.copyOf(keys);
        this.clockMs = clockMs;
    }

    private boolean cooling(String key) {
        Long until = coolingUntil.get(key);
        return until != null && clockMs.getAsLong() < until;
    }

    public Optional<String> nextActiveKey() {
        for (String k : keys) if (!cooling(k)) return Optional.of(k);
        return Optional.empty();
    }

    public void markCooling(String key, long durationMs) {
        coolingUntil.put(key, clockMs.getAsLong() + durationMs);
    }

    public int activeCount() { return (int) keys.stream().filter(k -> !cooling(k)).count(); }
    public int coolingCount() { return keys.size() - activeCount(); }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests KeyPoolTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/coppergolem/gemini/KeyPool.java src/test/java/com/example/coppergolem/gemini/KeyPoolTest.java
git commit -m "feat: key pool with rotation and cooling"
```

### Task 2: Task records + TaskParser

**Files:**
- Create: `src/main/java/com/example/coppergolem/task/Task.java`
- Create: `src/main/java/com/example/coppergolem/gemini/TaskParser.java`
- Test: `src/test/java/com/example/coppergolem/gemini/TaskParserTest.java`

**Interfaces:**
- Produces:
  - `sealed interface Task permits Task.Sort, Task.Mine, Task.Chop, Task.Unknown` with records:
    - `Task.Sort(int radius)`
    - `Task.Mine(int w, int h, int length, String dir, String filter)` — `filter` may be `"all"`.
    - `Task.Chop(int radius, boolean replant)`
    - `Task.Unknown(String reason)`
  - `TaskParser.parse(String geminiJson) -> Task` — maps `{"task":...}` JSON; malformed/unknown → `Task.Unknown`.

- [ ] **Step 1: Write the failing test**

```java
package com.example.coppergolem.gemini;

import com.example.coppergolem.task.Task;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskParserTest {
    @Test
    void parsesSortMineChopAndRejectsGarbage() {
        assertEquals(new Task.Sort(30),
            TaskParser.parse("{\"task\":\"sort\",\"radius\":30}"));
        assertEquals(new Task.Mine(3, 3, 16, "north", "all"),
            TaskParser.parse("{\"task\":\"mine\",\"w\":3,\"h\":3,\"length\":16,\"dir\":\"north\",\"filter\":\"all\"}"));
        assertEquals(new Task.Chop(12, true),
            TaskParser.parse("{\"task\":\"chop\",\"radius\":12,\"replant\":true}"));
        assertTrue(TaskParser.parse("not json") instanceof Task.Unknown);
        assertTrue(TaskParser.parse("{\"task\":\"fly\"}") instanceof Task.Unknown);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests TaskParserTest`
Expected: FAIL — `Task`/`TaskParser` do not exist.

- [ ] **Step 3: Write `Task.java`**

```java
package com.example.coppergolem.task;

public sealed interface Task permits Task.Sort, Task.Mine, Task.Chop, Task.Unknown {
    record Sort(int radius) implements Task {}
    record Mine(int w, int h, int length, String dir, String filter) implements Task {}
    record Chop(int radius, boolean replant) implements Task {}
    record Unknown(String reason) implements Task {}
}
```

- [ ] **Step 4: Write `TaskParser.java`**

```java
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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests TaskParserTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/coppergolem/task/Task.java src/main/java/com/example/coppergolem/gemini/TaskParser.java src/test/java/com/example/coppergolem/gemini/TaskParserTest.java
git commit -m "feat: typed Task records and Gemini JSON parser"
```

### Task 3: GolemConfig loader

**Files:**
- Create: `src/main/java/com/example/coppergolem/config/GolemConfig.java`

**Interfaces:**
- Produces:
  - `record GolemConfig(List<String> geminiKeys, String model, int sortRadius, boolean ownerBindRequired)`
  - `static GolemConfig loadOrCreate(Path configFile)` — reads `config/golem.json`; if missing, writes a template (empty keys, `model="gemini-2.0-flash"`, `sortRadius=30`, `ownerBindRequired=true`) and returns it.

- [ ] **Step 1: Implement loader**

```java
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
            GolemConfig def = new GolemConfig(List.of(), "gemini-2.0-flash", 30, true);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(def),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return def;
        } catch (IOException e) {
            throw new RuntimeException("failed to load " + file, e);
        }
    }
}
```

- [ ] **Step 2: Build (no unit test — IO glue, verified manually in Task 4)**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/example/coppergolem/config/GolemConfig.java
git commit -m "feat: golem config loader with template generation"
```

### Task 4: GroqClient — HTTP (OpenAI-compatible), key rotation on 429, JSON mode

**Files:**
- Create: `src/main/java/com/example/coppergolem/gemini/GroqClient.java`

**Interfaces:**
- Consumes: `KeyPool`, `GolemConfig`.
- Produces:
  - `GroqClient(KeyPool pool, String model, HttpClient http)`
  - `Optional<String> generateJson(String systemInstruction, String userText)` — returns the model's raw JSON text, or empty if all keys cooling. On HTTP 429 or non-2xx marks the used key cooling (60s) and retries the next key. Validates the body is JSON.
- Uses Groq's OpenAI-compatible Chat Completions endpoint with `response_format: {"type":"json_object"}`. Network glue; verified manually against a real key. State machine + parsing already covered by Tasks 1–2.

- [ ] **Step 1: Implement client**

```java
package com.example.coppergolem.gemini;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.util.Optional;

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

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(ENDPOINT))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + key)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
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
```

- [ ] **Step 2: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual smoke test (one real key in config)**

Add a temporary dev `/golem testgroq` command that builds a `KeyPool` from config, calls `generateJson` with the sort system prompt + a sample, logs the JSON. Run `./gradlew runClient`, confirm a JSON task returns. Remove the temp command after.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/coppergolem/gemini/GroqClient.java
git commit -m "feat: Groq client with key rotation and JSON-mode output"
```

---

## Phase 2 — Golem Primitives, Inventory, Tasks

### Task 5: GolemPrimitives interface + GolemInventory

**Files:**
- Create: `src/main/java/com/example/coppergolem/entity/GolemPrimitives.java`
- Create: `src/main/java/com/example/coppergolem/inventory/GolemInventory.java`

**Interfaces:**
- Produces:
  - `interface GolemPrimitives` — the abstraction task handlers use, implemented for Plan A (vanilla golem) or Plan B (bodiless). Methods:
    - `boolean moveTo(BlockPos pos)` — pathfind/teleport-step toward pos; true when adjacent/at it.
    - `boolean mineBlock(BlockPos pos)` — break block, drops to golem inventory; false if unreachable.
    - `boolean placeBlock(BlockPos pos, Item item)`
    - `void pickupNearbyItems(int radius)`
    - `List<BlockPos> findChests(int radius)`
    - `Map<Item,Integer> readChest(BlockPos chest)`
    - `int pullFromChest(BlockPos chest, Item item, int max)` — moves up to `max` into golem inv; returns moved count.
    - `int pushToChest(BlockPos chest, Item item, int max)` — moves from golem inv into chest; returns moved count.
    - `BlockPos position()`
    - `GolemInventory inventory()`
  - `GolemInventory` — 27-slot `SimpleInventory` subclass with `boolean isFull()`, `writeNbt`/`readNbt` for saved data.

- [ ] **Step 1: Write `GolemPrimitives.java`** (interface only — implementations land in Task 11 after Plan A/B is fixed)

```java
package com.example.coppergolem.entity;

import com.example.coppergolem.inventory.GolemInventory;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import java.util.List;
import java.util.Map;

public interface GolemPrimitives {
    boolean moveTo(BlockPos pos);
    boolean mineBlock(BlockPos pos);
    boolean placeBlock(BlockPos pos, Item item);
    void pickupNearbyItems(int radius);
    List<BlockPos> findChests(int radius);
    Map<Item, Integer> readChest(BlockPos chest);
    int pullFromChest(BlockPos chest, Item item, int max);
    int pushToChest(BlockPos chest, Item item, int max);
    BlockPos position();
    GolemInventory inventory();
}
```

- [ ] **Step 2: Write `GolemInventory.java`**

```java
package com.example.coppergolem.inventory;

import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;

public class GolemInventory extends SimpleInventory {
    public static final int SIZE = 27;
    public GolemInventory() { super(SIZE); }

    public boolean isFull() {
        for (int i = 0; i < size(); i++) {
            ItemStack s = getStack(i);
            if (s.isEmpty() || s.getCount() < s.getMaxCount()) return false;
        }
        return true;
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/coppergolem/entity/GolemPrimitives.java src/main/java/com/example/coppergolem/inventory/GolemInventory.java
git commit -m "feat: golem primitives interface and 27-slot inventory"
```

### Task 6: Sort planner (pure logic, TDD)

The "by existing majority" assignment + move plan is pure data logic — extract and test it without Minecraft. The Gemini grouping is delegated; the planner takes a group map and produces moves.

**Files:**
- Create: `src/main/java/com/example/coppergolem/task/SortPlanner.java`
- Test: `src/test/java/com/example/coppergolem/task/SortPlannerTest.java`

**Interfaces:**
- Produces:
  - `record ChestSnapshot(String chestId, Map<String,Integer> items)` — items keyed by item name.
  - `record Move(String fromChest, String toChest, String item, int count)`
  - `SortPlanner.plan(List<ChestSnapshot> chests, Map<String,String> itemToGroup) -> List<Move>`
    where for each group, the home chest = the chest currently holding the most of that group's items (ties → lowest chestId; empty chests usable only if a group has no holder). Moves carry every misplaced stack from its current chest to the group's home chest.

- [ ] **Step 1: Write the failing test**

```java
package com.example.coppergolem.task;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SortPlannerTest {
    @Test
    void consolidatesStraysIntoMajorityChest() {
        var chestA = new SortPlanner.ChestSnapshot("A", Map.of("oak_log", 40, "stone", 5));
        var chestB = new SortPlanner.ChestSnapshot("B", Map.of("oak_log", 8, "stone", 50));
        // groups: logs -> "wood", stone -> "stone"
        Map<String,String> groups = Map.of("oak_log", "wood", "stone", "stone");

        List<SortPlanner.Move> moves =
            SortPlanner.plan(List.of(chestA, chestB), groups);

        // wood home = A (40 > 8). stone home = B (50 > 5).
        // Expect: move 8 oak_log B->A, move 5 stone A->B.
        assertTrue(moves.contains(new SortPlanner.Move("B", "A", "oak_log", 8)));
        assertTrue(moves.contains(new SortPlanner.Move("A", "B", "stone", 5)));
        assertEquals(2, moves.size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests SortPlannerTest`
Expected: FAIL — `SortPlanner` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.example.coppergolem.task;

import java.util.*;

public final class SortPlanner {
    private SortPlanner() {}

    public record ChestSnapshot(String chestId, Map<String,Integer> items) {}
    public record Move(String fromChest, String toChest, String item, int count) {}

    public static List<Move> plan(List<ChestSnapshot> chests, Map<String,String> itemToGroup) {
        // total per (group, chest)
        Map<String, Map<String,Integer>> groupChestTotals = new HashMap<>();
        for (ChestSnapshot c : chests) {
            for (var e : c.items().entrySet()) {
                String group = itemToGroup.get(e.getKey());
                if (group == null) continue;
                groupChestTotals
                    .computeIfAbsent(group, g -> new HashMap<>())
                    .merge(c.chestId(), e.getValue(), Integer::sum);
            }
        }
        // home chest per group: max total, tie -> lowest chestId
        Map<String,String> groupHome = new HashMap<>();
        for (var ge : groupChestTotals.entrySet()) {
            String home = ge.getValue().entrySet().stream()
                .sorted((a,b) -> {
                    int byCount = Integer.compare(b.getValue(), a.getValue());
                    return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
                })
                .findFirst().map(Map.Entry::getKey).orElseThrow();
            groupHome.put(ge.getKey(), home);
        }
        // moves: every stack not in its group's home
        List<Move> moves = new ArrayList<>();
        for (ChestSnapshot c : chests) {
            for (var e : c.items().entrySet()) {
                String group = itemToGroup.get(e.getKey());
                if (group == null) continue;
                String home = groupHome.get(group);
                if (!home.equals(c.chestId())) {
                    moves.add(new Move(c.chestId(), home, e.getKey(), e.getValue()));
                }
            }
        }
        moves.sort(Comparator.comparing(Move::item).thenComparing(Move::fromChest));
        return moves;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests SortPlannerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/coppergolem/task/SortPlanner.java src/test/java/com/example/coppergolem/task/SortPlannerTest.java
git commit -m "feat: sort planner with majority-home consolidation"
```

### Task 7: TaskHandler interface + state enum

**Files:**
- Create: `src/main/java/com/example/coppergolem/task/TaskHandler.java`

**Interfaces:**
- Produces:
  - `interface TaskHandler { boolean tick(GolemPrimitives g); String status(); void pause(); void resume(); }`
    `tick` returns true when the task is complete. Called once per server tick.

- [ ] **Step 1: Write interface**

```java
package com.example.coppergolem.task;

import com.example.coppergolem.entity.GolemPrimitives;

public interface TaskHandler {
    /** @return true when task complete. */
    boolean tick(GolemPrimitives g);
    String status();
    void pause();
    void resume();
}
```

- [ ] **Step 2: Build + commit**

Run: `./gradlew build` → BUILD SUCCESSFUL.

```bash
git add src/main/java/com/example/coppergolem/task/TaskHandler.java
git commit -m "feat: TaskHandler tick interface"
```

### Task 8: MineTask handler

**Files:**
- Create: `src/main/java/com/example/coppergolem/task/MineTask.java`

**Interfaces:**
- Consumes: `GolemPrimitives`, `Task.Mine`, `TaskHandler`.
- Produces: `MineTask(Task.Mine spec, BlockPos origin)` implementing `TaskHandler`. Computes the NxN×length cell list along `dir`, mines each (respecting `filter`), dumps to nearest chest when inventory full, completes when all cells done.

- [ ] **Step 1: Implement (cells precomputed; one cell per tick)**

```java
package com.example.coppergolem.task;

import com.example.coppergolem.entity.GolemPrimitives;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.*;

public final class MineTask implements TaskHandler {
    private final Task.Mine spec;
    private final Deque<BlockPos> cells = new ArrayDeque<>();
    private final int total;
    private boolean paused;

    public MineTask(Task.Mine spec, BlockPos origin) {
        this.spec = spec;
        Direction d = switch (spec.dir().toLowerCase()) {
            case "south" -> Direction.SOUTH;
            case "east"  -> Direction.EAST;
            case "west"  -> Direction.WEST;
            default      -> Direction.NORTH;
        };
        // plane is perpendicular to travel dir; build length planes of w x h
        for (int l = 1; l <= spec.length(); l++) {
            for (int wi = 0; wi < spec.w(); wi++) {
                for (int hi = 0; hi < spec.h(); hi++) {
                    int offW = wi - spec.w() / 2;
                    BlockPos base = origin.offset(d, l).up(hi);
                    BlockPos cell = d.getAxis() == Direction.Axis.Z
                        ? base.east(offW) : base.south(offW);
                    cells.add(cell);
                }
            }
        }
        this.total = cells.size();
    }

    @Override
    public boolean tick(GolemPrimitives g) {
        if (paused || cells.isEmpty()) return cells.isEmpty();
        BlockPos cell = cells.peek();
        if (g.inventory().isFull()) {
            List<BlockPos> chests = g.findChests(16);
            if (!chests.isEmpty()) dumpAll(g, chests.get(0));
            return false;
        }
        if (!g.moveTo(cell)) return false;       // still walking
        g.mineBlock(cell);                       // filter handled in primitive impl
        g.pickupNearbyItems(2);
        cells.poll();
        return cells.isEmpty();
    }

    private void dumpAll(GolemPrimitives g, BlockPos chest) {
        var inv = g.inventory();
        for (int i = 0; i < inv.size(); i++) {
            var st = inv.getStack(i);
            if (!st.isEmpty()) g.pushToChest(chest, st.getItem(), st.getCount());
        }
    }

    @Override public String status() {
        return "Mining: " + (total - cells.size()) + "/" + total;
    }
    @Override public void pause() { paused = true; }
    @Override public void resume() { paused = false; }
}
```

- [ ] **Step 2: Build + commit**

Run: `./gradlew build` → BUILD SUCCESSFUL.

```bash
git add src/main/java/com/example/coppergolem/task/MineTask.java
git commit -m "feat: mine task state machine"
```

### Task 9: ChopTask handler

**Files:**
- Create: `src/main/java/com/example/coppergolem/task/ChopTask.java`

**Interfaces:**
- Consumes: `GolemPrimitives`, `Task.Chop`, `TaskHandler`.
- Produces: `ChopTask(Task.Chop spec, BlockPos origin, Predicate<BlockPos> isLog, Supplier<List<BlockPos>> findLogColumns)` — the log-detection predicates are injected by the primitive impl so the handler stays world-agnostic. Finds a tree, mines its trunk upward, collects drops, optional replant, repeats until none in radius.

- [ ] **Step 1: Implement**

```java
package com.example.coppergolem.task;

import com.example.coppergolem.entity.GolemPrimitives;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.function.Supplier;

public final class ChopTask implements TaskHandler {
    private final Task.Chop spec;
    private final Supplier<List<BlockPos>> findTreeBases; // nearest-first log bases in radius
    private final Deque<BlockPos> currentTrunk = new ArrayDeque<>();
    private int chopped;
    private boolean paused;

    public ChopTask(Task.Chop spec, Supplier<List<BlockPos>> findTreeBases) {
        this.spec = spec;
        this.findTreeBases = findTreeBases;
    }

    @Override
    public boolean tick(GolemPrimitives g) {
        if (paused) return false;
        if (currentTrunk.isEmpty()) {
            List<BlockPos> bases = findTreeBases.get();
            if (bases.isEmpty()) return true;            // no trees left -> done
            BlockPos base = bases.get(0);
            for (int y = 0; y < 12; y++) currentTrunk.add(base.up(y)); // up to 12 logs
        }
        BlockPos log = currentTrunk.peek();
        if (!g.moveTo(log)) return false;
        if (g.mineBlock(log)) chopped++;
        g.pickupNearbyItems(3);
        currentTrunk.poll();
        return false;
    }

    @Override public String status() { return "Chopping: " + chopped + " logs"; }
    @Override public void pause() { paused = true; }
    @Override public void resume() { paused = false; }
}
```

> Note: replant (`spec.replant()`) is handled by the primitive impl when it has a sapling and the base block is now air; kept out of the handler to stay world-agnostic.

- [ ] **Step 2: Build + commit**

Run: `./gradlew build` → BUILD SUCCESSFUL.

```bash
git add src/main/java/com/example/coppergolem/task/ChopTask.java
git commit -m "feat: chop task state machine"
```

### Task 10: SortTask handler + Gemini grouping call

**Files:**
- Create: `src/main/java/com/example/coppergolem/task/SortTask.java`

**Interfaces:**
- Consumes: `GolemPrimitives`, `GroqClient`, `SortPlanner`, `Task.Sort`, `TaskHandler`.
- Produces: `SortTask(Task.Sort spec, GroqClient ai)` implementing `TaskHandler`. Phases: SCAN (build `ChestSnapshot`s from chests in radius) → ASK (snapshot → `{item->group}`) → PLAN (`SortPlanner.plan`) → EXECUTE (run moves one per tick) → DONE.

- [ ] **Step 1: Implement**

```java
package com.example.coppergolem.task;

import com.example.coppergolem.entity.GolemPrimitives;
import com.example.coppergolem.gemini.GroqClient;
import com.google.gson.*;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import java.util.*;

public final class SortTask implements TaskHandler {
    private enum Phase { SCAN, ASK, PLAN, EXECUTE, DONE }
    private static final String SYS =
        "You group Minecraft item ids by similarity. Respond with strict JSON " +
        "{\"groups\":{\"<item_id>\":\"<group_name>\"}}. Group like-with-like " +
        "(logs together, ores together, food together). No prose.";

    private final Task.Sort spec;
    private final GroqClient ai;
    private Phase phase = Phase.SCAN;
    private boolean paused;
    private final Map<BlockPos,String> chestIds = new HashMap<>();
    private List<SortPlanner.ChestSnapshot> snapshots = new ArrayList<>();
    private Deque<SortPlanner.Move> moves = new ArrayDeque<>();
    private String error;

    public SortTask(Task.Sort spec, GroqClient ai) {
        this.spec = spec; this.ai = ai;
    }

    @Override
    public boolean tick(GolemPrimitives g) {
        if (paused) return false;
        switch (phase) {
            case SCAN -> { scan(g); phase = Phase.ASK; }
            case ASK -> {
                Map<String,String> groups = askAi();
                if (groups == null) { error = "AI busy — all keys cooling"; phase = Phase.DONE; }
                else { moves = new ArrayDeque<>(SortPlanner.plan(snapshots, groups)); phase = Phase.EXECUTE; }
            }
            case PLAN -> phase = Phase.EXECUTE;
            case EXECUTE -> {
                if (moves.isEmpty()) { phase = Phase.DONE; break; }
                executeOne(g, moves.peek());
                moves.poll();
            }
            case DONE -> { return true; }
        }
        return phase == Phase.DONE;
    }

    private void scan(GolemPrimitives g) {
        snapshots = new ArrayList<>();
        int idx = 0;
        for (BlockPos c : g.findChests(spec.radius())) {
            String id = "C" + (idx++);
            chestIds.put(c, id);
            Map<String,Integer> items = new HashMap<>();
            g.readChest(c).forEach((item, n) ->
                items.merge(Registries.ITEM.getId(item).toString(), n, Integer::sum));
            snapshots.add(new SortPlanner.ChestSnapshot(id, items));
        }
    }

    private Map<String,String> askAi() {
        JsonObject payload = new JsonObject();
        JsonArray arr = new JsonArray();
        for (var s : snapshots) {
            JsonObject co = new JsonObject();
            co.addProperty("chest", s.chestId());
            JsonObject items = new JsonObject();
            s.items().forEach(items::addProperty);
            co.add("items", items);
            arr.add(co);
        }
        payload.add("chests", arr);
        Optional<String> resp = ai.generateJson(SYS, payload.toString());
        if (resp.isEmpty()) return null;
        try {
            JsonObject g = JsonParser.parseString(resp.get()).getAsJsonObject()
                .getAsJsonObject("groups");
            Map<String,String> out = new HashMap<>();
            for (var e : g.entrySet()) out.put(e.getKey(), e.getValue().getAsString());
            return out;
        } catch (Exception e) { return Map.of(); } // empty -> no moves
    }

    private void executeOne(GolemPrimitives g, SortPlanner.Move m) {
        BlockPos from = posFor(m.fromChest()), to = posFor(m.toChest());
        if (from == null || to == null) return;
        Item item = Registries.ITEM.get(net.minecraft.util.Identifier.of(m.item()));
        g.moveTo(from); g.pullFromChest(from, item, m.count());
        g.moveTo(to);   g.pushToChest(to, item, m.count());
    }

    private BlockPos posFor(String id) {
        return chestIds.entrySet().stream()
            .filter(e -> e.getValue().equals(id)).map(Map.Entry::getKey)
            .findFirst().orElse(null);
    }

    @Override public String status() {
        if (error != null) return error;
        return switch (phase) {
            case SCAN -> "Sorting: scanning chests";
            case ASK, PLAN -> "Sorting: planning";
            case EXECUTE -> "Sorting: " + moves.size() + " moves left";
            case DONE -> "Sorting: done";
        };
    }
    @Override public void pause() { paused = true; }
    @Override public void resume() { paused = false; }
}
```

- [ ] **Step 2: Build + commit**

Run: `./gradlew build` → BUILD SUCCESSFUL.

```bash
git add src/main/java/com/example/coppergolem/task/SortTask.java
git commit -m "feat: sort task with Groq grouping and move execution"
```

### Task 11: TaskDispatcher

**Files:**
- Create: `src/main/java/com/example/coppergolem/task/TaskDispatcher.java`

**Interfaces:**
- Consumes: `Task`, all handlers, `GroqClient`.
- Produces: `TaskDispatcher.create(Task t, BlockPos origin, GroqClient ai, Supplier<List<BlockPos>> findTreeBases) -> Optional<TaskHandler>` — `Task.Unknown` → empty.

- [ ] **Step 1: Implement**

```java
package com.example.coppergolem.task;

import com.example.coppergolem.gemini.GroqClient;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.function.Supplier;

public final class TaskDispatcher {
    private TaskDispatcher() {}

    public static Optional<TaskHandler> create(
            Task t, BlockPos origin, GroqClient ai,
            Supplier<List<BlockPos>> findTreeBases) {
        return Optional.ofNullable(switch (t) {
            case Task.Sort s -> new SortTask(s, ai);
            case Task.Mine m -> new MineTask(m, origin);
            case Task.Chop c -> new ChopTask(c, findTreeBases);
            case Task.Unknown u -> null;
        });
    }
}
```

- [ ] **Step 2: Build + commit**

Run: `./gradlew build` → BUILD SUCCESSFUL.

```bash
git add src/main/java/com/example/coppergolem/task/TaskDispatcher.java
git commit -m "feat: task dispatcher"
```

---

## Phase 3 — Entity/Bodiless, Networking, UI, Wiring

> **Provider note:** This project uses **Groq** (OpenAI-compatible API), not Gemini.
> The AI client class is `GroqClient` (Task 4). Endpoint:
> `https://api.groq.com/openai/v1/chat/completions`. Default model
> `llama-3.3-70b-versatile`. JSON via `response_format: {"type":"json_object"}`.
> Groq free limits are **per account** (30 RPM / 1000 RPD), so the key pool
> guards against a single key being revoked/flagged, not for quota multiplication.

### Task 12: GolemController + GolemPrimitives implementation (Plan A or B)

**Files:**
- Create: `src/main/java/com/example/coppergolem/entity/GolemController.java`
- Create: `src/main/java/com/example/coppergolem/entity/WorldGolemPrimitives.java`

**Interfaces:**
- Consumes: `GolemPrimitives`, `GolemInventory`, `TaskHandler`, `TaskDispatcher`, `GroqClient`.
- Produces:
  - `GolemController` — one per owner. Holds owner UUID, `GolemInventory`, current `TaskHandler` (nullable), paused flag. `tick(ServerWorld)` advances the handler; clears it on completion. `assign(Task)`, `stop()`, `pause()`, `resume()`, `status()`.
  - `WorldGolemPrimitives implements GolemPrimitives` — the real MC implementation. **Plan A:** wraps the vanilla copper golem entity (uses its navigation + position; inventory via attachment). **Plan B:** operates at the owner's position with server-side world edits; `position()` = owner pos. Pick the branch fixed in Task 0b.

- [ ] **Step 1: Implement `WorldGolemPrimitives`** against the decided plan. Use `ServerWorld.breakBlock`, `Block.getDroppedStacks`, `world.getBlockEntity` for chests (`Inventory`), and entity navigation (Plan A) or direct owner-relative ops (Plan B). Log-detection helpers (`findTreeBases`) live here and are passed to `ChopTask`. The block `filter` for `MineTask` is enforced in `mineBlock` (skip + return true if block id not in filter).

> Full method bodies depend on Plan A vs B chosen in Task 0b. Implement each `GolemPrimitives` method using the verified 26.2 server APIs from the unobfuscated sources. Keep the class under ~250 lines; if larger, split chest-IO into `ChestIo.java`.

- [ ] **Step 2: Implement `GolemController`**

```java
package com.example.coppergolem.entity;

import com.example.coppergolem.gemini.GroqClient;
import com.example.coppergolem.inventory.GolemInventory;
import com.example.coppergolem.task.*;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import java.util.*;

public final class GolemController {
    private final UUID owner;
    private final GolemInventory inventory = new GolemInventory();
    private final WorldGolemPrimitives primitives;
    private final GroqClient ai;
    private TaskHandler current;
    private String lastStatus = "idle";

    public GolemController(UUID owner, WorldGolemPrimitives primitives, GroqClient ai) {
        this.owner = owner; this.primitives = primitives; this.ai = ai;
    }

    public UUID owner() { return owner; }
    public GolemInventory inventory() { return inventory; }

    public void assign(Task t) {
        Optional<TaskHandler> h = TaskDispatcher.create(
            t, primitives.position(), ai, () -> primitives.findChests(0)); // findTreeBases supplied by primitives
        current = h.orElse(null);
        if (current == null) lastStatus = "couldn't understand, rephrase";
    }

    public void tick(ServerWorld world) {
        if (current == null) return;
        boolean done = current.tick(primitives);
        lastStatus = current.status();
        if (done) current = null;
    }

    public void stop() { current = null; lastStatus = "stopped"; }
    public void pause() { if (current != null) current.pause(); }
    public void resume() { if (current != null) current.resume(); }
    public String status() { return current == null ? "idle" : lastStatus; }
}
```

> Note: wire `findTreeBases` to the real `WorldGolemPrimitives.findTreeBases(radius)` in implementation; the placeholder lambda above is replaced with the actual supplier.

- [ ] **Step 3: Build + commit**

Run: `./gradlew build` → BUILD SUCCESSFUL.

```bash
git add src/main/java/com/example/coppergolem/entity/
git commit -m "feat: golem controller and world primitives (Plan A/B)"
```

### Task 13: Networking — packets + server handlers

**Files:**
- Create: `src/main/java/com/example/coppergolem/net/Packets.java`
- Create: `src/main/java/com/example/coppergolem/net/ServerNetworking.java`

**Interfaces:**
- Produces:
  - `Packets` — custom payloads with codecs: `PromptC2S(String text)`, `StopC2S()`, `PauseC2S(boolean pause)`, `StatusS2C(String status, int activeKeys, int coolingKeys)`. Use Fabric `CustomPayload` + `PacketCodec`.
  - `ServerNetworking.register(Supplier<GolemController> forPlayer, GroqClient ai, GroqSystemPrompt prompt)` — registers receivers. On `PromptC2S`: **verify sender UUID == controller owner**; if not, drop. Else call `ai.generateJson(SYS, text)` → `TaskParser.parse` → `controller.assign`. On `StopC2S`/`PauseC2S` (owner-checked) → controller methods. Periodically send `StatusS2C` to the owner.

- [ ] **Step 1: Implement `Packets.java`** with the four payloads + codecs (Fabric `PayloadTypeRegistry` registration in `register()`).

- [ ] **Step 2: Implement `ServerNetworking.java`** with owner-UUID checks on every C2S handler and the command system prompt:

```
You convert a Minecraft player's request into ONE task as strict JSON.
Tasks: {"task":"sort","radius":30} |
{"task":"mine","w":3,"h":3,"length":16,"dir":"north|south|east|west","filter":"all|cobblestone|dirt|stone"} |
{"task":"chop","radius":12,"replant":true}.
If unclear, respond {"task":"unknown"}. No prose.
```

- [ ] **Step 3: Build + commit**

Run: `./gradlew build` → BUILD SUCCESSFUL.

```bash
git add src/main/java/com/example/coppergolem/net/
git commit -m "feat: owner-checked networking for prompt/stop/pause/status"
```

### Task 14: Wire it together — mod init, config load, golem spawn command, per-tick

**Files:**
- Modify: `src/main/java/com/example/coppergolem/CopperGolemMod.java`

**Interfaces:**
- Consumes: everything above.
- Produces: on server start, load `GolemConfig`, build `KeyPool` (from `geminiKeys` field — reused name holds Groq keys) + `GroqClient`. Register `/golem spawn` (owner-bind), server-tick callback driving all controllers, and `ServerNetworking`.

- [ ] **Step 1: Implement init** — load config, construct `KeyPool(config.geminiKeys(), System::currentTimeMillis)`, `GroqClient(pool, config.model(), HttpClient.newHttpClient())`. Register `ServerTickEvents.END_SERVER_TICK` to `controller.tick(world)` for each owner's controller. Register `/golem spawn` command that (Plan A) spawns the vanilla copper golem and binds owner, or (Plan B) registers a bodiless controller bound to the command sender. Register `ServerNetworking`.

- [ ] **Step 2: Build + commit**

Run: `./gradlew build` → BUILD SUCCESSFUL.

```bash
git add src/main/java/com/example/coppergolem/CopperGolemMod.java
git commit -m "feat: wire config, Groq client, tick loop, spawn command"
```

### Task 15: Client — keybind + control & inventory screens

**Files:**
- Create: `src/client/java/com/example/coppergolem/client/GolemClientMod.java`
- Create: `src/client/java/com/example/coppergolem/client/GolemKeybind.java`
- Create: `src/client/java/com/example/coppergolem/client/screen/GolemControlScreen.java`
- Create: `src/client/java/com/example/coppergolem/client/screen/GolemInventoryScreen.java`
- Create: `src/client/java/com/example/coppergolem/client/net/ClientNetworking.java`

**Interfaces:**
- Consumes: `Packets`.
- Produces:
  - `GolemKeybind` — registers `key.coppergolem.open` default `G` (`GLFW_KEY_G`), category `Copper Golem`. On press opens `GolemControlScreen`.
  - `GolemControlScreen` — text field (prompt) + Send button (→ `PromptC2S`), Stop button (→ `StopC2S`), Pause/Resume toggle (→ `PauseC2S`), View Inventory button (→ opens `GolemInventoryScreen`), status label fed by the latest `StatusS2C` (task + `keys: N active / M cooling`).
  - `GolemInventoryScreen` — renders the golem's 27-slot inventory (handled screen bound to a server-opened `ScreenHandler`; opening is requested via a button → server opens the handler so contents are authoritative).
  - `ClientNetworking` — registers `StatusS2C` receiver, stores latest status for the screen.

- [ ] **Step 1: Implement client entrypoint + keybind**

```java
package com.example.coppergolem.client;

import net.fabricmc.api.ClientModInitializer;

public class GolemClientMod implements ClientModInitializer {
    @Override public void onInitializeClient() {
        GolemKeybind.register();
        com.example.coppergolem.client.net.ClientNetworking.register();
    }
}
```

- [ ] **Step 2: Implement keybind, screens, client networking** per the interfaces above (Fabric `KeyBindingHelper`, `Screen`, `HandledScreen`, `ClientPlayNetworking`).

- [ ] **Step 3: Build + run client to verify UI**

Run: `./gradlew runClient`
Manual: press `G`, control screen opens; type a prompt → golem gets a task; Stop/Pause work; View Inventory shows 27 slots; status line updates with key counts.

- [ ] **Step 4: Commit**

```bash
git add src/client/
git commit -m "feat: keybind, control screen, inventory screen, client networking"
```

---

## Phase 4 — Integration & Distribution Verification

### Task 16: End-to-end + friends-install-nothing check

- [ ] **Step 1:** Build the production jar: `./gradlew build`. Locate `build/libs/coppergolem-0.1.0.jar`.
- [ ] **Step 2:** Put 9 real Groq keys into `config/golem.json` (the gitignored template from Task 3). Confirm the file is NOT tracked: `git status` shows it ignored.
- [ ] **Step 3:** Dev-server test all three tasks: sort a chaotic chest cluster, mine a 3x3 tunnel, chop trees. Verify inventory openable mid-task.
- [ ] **Step 4:** **Distribution check** — connect a SECOND vanilla client (no mod) to the dev server.
  - Plan A: confirm it sees the copper golem walking, cannot open the UI (no keybind), and `golem`-style chat does nothing.
  - Plan B: confirm it sees no entity and nothing breaks.
- [ ] **Step 5:** Upload jar to Aternos (Mods → Upload) per the spec; confirm server boots with the mod.
- [ ] **Step 6: Commit any fixes**

```bash
git add -A
git commit -m "test: end-to-end tasks and friends-no-mod distribution verified"
```

---

## Self-Review Notes

- **Spec coverage:** sort (Tasks 6,10), mine (8), chop (9), Groq + key pool (1,4), block-at-limit (4 returns empty → SortTask/networking surface "all keys cooling"), owner-only (13), UI keybind/stop/pause/inv/status (15), friends-no-mod (0b decision + 16 check), config gitignored (Task 0 step 4, Task 3).
- **Provider:** Groq replaces Gemini fully. `GroqClient` in Task 4. Config field name `geminiKeys` retained to avoid churn but holds Groq `gsk_` keys (documented in Task 14).
- **Type consistency:** `GolemPrimitives` methods used identically across MineTask/ChopTask/SortTask/controller. `Task` records match `TaskParser` output and `TaskDispatcher` switch.
- **Open risk carried from spec:** Plan A vs B resolved in Task 0b before any task code; a custom entity stays forbidden.
