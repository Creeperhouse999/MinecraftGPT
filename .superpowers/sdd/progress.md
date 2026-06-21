# Copper Golem AI Bot — SDD Progress Ledger

Plan: docs/superpowers/plans/2026-06-21-copper-golem-ai-bot.md
Branch: master

## Tasks
Task 0: complete (commits a2baec8..9198441, build SUCCESSFUL w/ concerns). Loom 1.17.12, Gradle 9.5.1, MC 26.2, Java 24. NOTE: brief's `modImplementation` changed to `implementation` (Loom dropped it in no-remap path); fabric-api artifact not run-verified yet (deferred to Task 0b runClient spike).
Task 0b: complete. DECISION = PLAN A (golem = real vanilla `net.minecraft.world.entity.animal.golem.CopperGolem`; Brain behaviors injected via `getBrain().addActivity/removeAllBehaviors`; persistent inventory via Fabric `AttachmentType<CompoundTag>` on Entity/AttachmentTarget). Spike compiled green against real 26.2+fabric-api. runClient/runServer could NOT launch: fabric-api 0.152.1+26.2 `depends java >=25` but only JDK 24/17 installed — runtime save/reload check deferred until a JDK 25 toolchain exists. Foundation kept: `GolemAttachments.GOLEM_DATA`. See task-0b-report.md.

## Minor findings (for final review triage)
- Need JDK 25 toolchain to launch dev client/server (fabric-api requires java >=25; MC 26.2 classes are class-version 69). Bump toolchain before runtime-dependent tasks.
- Re-verify attachment persistence at runtime once JDK 25 is available (spike was removed; re-add if needed).
- If injected Brain goals need new memory module types, add a small Mixin to `CopperGolem` BRAIN_PROVIDER/makeBrain, or store scheduling state in the AttachmentType.
