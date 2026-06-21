
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

