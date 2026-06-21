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

