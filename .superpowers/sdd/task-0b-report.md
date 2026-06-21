# Task 0b Report — Plan A vs Plan B for the AI golem body

**Date:** 2026-06-21
**Decision: PLAN A** (golem = real vanilla Copper Golem entity + injected Brain behaviors + persistent Fabric AttachmentType inventory).

---

## Copper Golem entity — class & package

- **Class:** `net.minecraft.world.entity.animal.golem.CopperGolem`
- **AI class:** `net.minecraft.world.entity.animal.golem.CopperGolemAi`
- **State enum:** `net.minecraft.world.entity.animal.golem.CopperGolemState`
- **Hierarchy:** `CopperGolem` → `AbstractGolem` → `PathfinderMob` → `Mob` → `LivingEntity` → `Entity`
- **EntityType:** `net.minecraft.world.entity.EntityTypes.COPPER_GOLEM` (registry id `minecraft:copper_golem`, from `EntityTypeIds`)

MC 26.2 is unobfuscated; package/method names above are the real names read from the Loom-provided
`minecraft-common.jar` (class file version 69 = Java 25). Signatures were read directly from the class
constant pools (no JDK 25 javap available) and then **proven by a compiling spike** (see below).

---

## Evidence

### (a) Spawnable from server code / commands — YES

- `EntityTypes.COPPER_GOLEM` is a public static `EntityType<CopperGolem>`.
- `EntityType.spawn(ServerLevel, PostSpawnProcessor, BlockPos, EntitySpawnReason, boolean, boolean)`
  returns the spawned `Entity`. The spike calls
  `EntityTypes.COPPER_GOLEM.spawn(level, null, pos, EntitySpawnReason.COMMAND, false, false)` and it
  compiles cleanly.
- Also spawnable in-game via `/summon minecraft:copper_golem` (it is a registered vanilla entity in 26.2).

### (b) AI goals injectable from a mod — YES (via the Brain, not GoalSelector)

The Copper Golem uses the **Brain / behavior (BTL) system**, not the legacy `GoalSelector`:
- `CopperGolem` has `getBrain()` (public, inherited from `LivingEntity`), a static
  `BRAIN_PROVIDER` (`net.minecraft.world.entity.ai.Brain$Provider`), and overrides `makeBrain(...)`.
- `CopperGolemAi` builds activities: `initCoreActivity`, `initIdleActivity`, `updateActivity`,
  registering `net.minecraft.world.entity.schedule.Activity` / `ActivityData` with behaviors such as
  `TransportItemsBetweenContainers`, `RandomStroll`, `AnimalPanic`, `MoveToTargetSink`,
  `LookAtTargetSink`, `InteractWithDoor`, `SetEntityLookTargetSometimes`, `CountDownCooldownTicks`.
- `net.minecraft.world.entity.ai.Brain` exposes public mutators usable from mod code at runtime:
  `addActivity(...)`, `removeAllBehaviors()`, `setActiveActivity(...)`, `setActiveActivityIfPossible(...)`,
  `setMemory(...)`. So a mod can call `golem.getBrain().addActivity(...)` / `removeAllBehaviors()` to add
  or replace AI behaviors. This is the same well-established pattern used to retarget vanilla brain mobs
  (villagers, piglins, etc.).

Caveat (not a blocker): the brain's *allowed* memory modules and sensors are fixed at construction by
`BRAIN_PROVIDER`. Behaviors that need a brand-new memory module type must either reuse existing memories
or have the memory present in the allowed set; if our injected goals need novel memories that the
provider does not allow, we would attach them via a small Mixin to `CopperGolem.makeBrain`/`brainProvider`,
or store our own scheduling state in the AttachmentType instead of in brain memories. Either way the
entity's AI is open and modifiable — confirmed.

### (c) Persistent Fabric AttachmentType keyed to the entity — YES

- Fabric API `0.152.1+26.2` is resolved and on the classpath; it bundles
  `fabric-data-attachment-api-v1` `2.2.16+aa0fd6fe9c`.
- `net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry.create(Identifier, Consumer<Builder>)` with
  `Builder.persistent(Codec)`, `.copyOnDeath()`, `.syncWith(...)`, `.initializer(...)`,
  `.buildAndRegister()`.
- `net.fabricmc.fabric.api.attachment.v1.AttachmentTarget` provides `setAttached`, `getAttached`,
  `getAttachedOrCreate`, `hasAttached`, `removeAttached`.
- The data-attachment mixin config includes **`EntityMixin`** and `AttachmentTargetsMixin` → **`Entity`
  implements `AttachmentTarget`**, so the Copper Golem (an Entity) can carry attachments keyed by its
  own identity. Persistence is implemented by `AttachmentSerializingImpl` + `AttachmentSavedData` +
  `EntityMixin` (writes/reads attachment NBT in the entity's save data), so a `.persistent(...)` attachment
  survives save/reload. `CompoundTag.CODEC` exists for an `AttachmentType<CompoundTag>` (mojmap name for
  `NbtCompound`).

The spike registers exactly this:
```java
AttachmentRegistry.create(
    Identifier.fromNamespaceAndPath(MOD_ID, "spike_data"),
    b -> b.persistent(CompoundTag.CODEC).copyOnDeath());
```
and calls `golem.setAttached(...)` / `golem.getAttached(...)` / `golem.hasAttached(...)` — all compile.

---

## Did runClient / runServer launch? — NO (environment blocker, not an architecture blocker)

- A temporary `/golem spike` + `/golem spike read` command was implemented (spawn copper golem, attach a
  `CompoundTag` marker, read it back) and the whole project **compiled successfully**
  (`./gradlew compileJava` → BUILD SUCCESSFUL), proving every API call against the real 26.2 + fabric-api.
- `./gradlew runServer` failed at the Fabric Loader resolution stage:
  > Mod 'Fabric API' (fabric-api) 0.152.1+26.2 requires version 25 or later of Java, but only 24 is present.
  MC 26.2 / fabric-api are compiled to class version 69 (Java 25) and fabric-api declares `depends: java >=25`.
  Only JDK 24 and JDK 17 are installed on this machine; no JDK 25 is available, so the dev client/server
  cannot launch here.
- Note: this is **not** the `implementation` vs `modImplementation` problem flagged in the brief. Fabric
  Loader *did* pick up fabric-api as a mod (it appeared in resolution and toggled per-environment
  sub-modules). The only blocker is the missing JDK 25. Once a JDK 25 toolchain is installed, the spike
  is expected to run as written.

### Spike runtime result
Not observed in-game (no JDK 25 to launch). Architecture validated by source inspection + successful
compile against the real API. The runtime save/reload check of the attachment should be re-run once a
JDK 25 dev environment exists, but the attachment API's persistence is a documented, mixin-backed feature
of Entity, so there is no reason to expect failure.

---

## Decision: PLAN A

All three gates pass on source + compile evidence:
- (a) the copper golem is a normal registered vanilla entity, spawnable from server code/commands;
- (b) its AI is the open Brain system with public `addActivity` / `removeAllBehaviors` / `setMemory`
  mutators, so a mod can add or replace behaviors at runtime (with a tiny optional Mixin if novel brain
  memories are needed);
- (c) `Entity` implements Fabric's `AttachmentTarget`, and `AttachmentRegistry.persistent(CompoundTag.CODEC)`
  gives a per-entity inventory that persists across save/reload.

Therefore the golem will be a **real vanilla Copper Golem** that unmodded friends render natively with no
install, with mod-side AI behaviors injected into its Brain and a persistent AttachmentType inventory.
Plan B (bodiless) is not needed.

### Residual risk / follow-up for downstream tasks
1. **Confirm at runtime** once JDK 25 is installed: re-run the spike, verify the attachment survives
   `/reload` + world save/quit/reload. (Low risk; persistence is built into the API.)
2. **Brain memory limits:** if injected goals need new memory module types not in `BRAIN_PROVIDER`'s
   allowed set, add a small Mixin to the brain provider or store scheduling state in the AttachmentType.
3. **Toolchain:** bump the project to a JDK 25 toolchain to enable `runClient`/`runServer`.
