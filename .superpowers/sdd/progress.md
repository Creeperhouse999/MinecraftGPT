# Copper Golem AI Bot — SDD Progress Ledger

Plan: docs/superpowers/plans/2026-06-21-copper-golem-ai-bot.md
Branch: master

## Tasks
Task 0: complete (commits a2baec8..9198441, build SUCCESSFUL w/ concerns). Loom 1.17.12, Gradle 9.5.1, MC 26.2, Java 24. NOTE: brief's `modImplementation` changed to `implementation` (Loom dropped it in no-remap path); fabric-api artifact not run-verified yet (deferred to Task 0b runClient spike).
Task 0b: complete. DECISION = PLAN A (golem = real vanilla `net.minecraft.world.entity.animal.golem.CopperGolem`; Brain behaviors injected via `getBrain().addActivity/removeAllBehaviors`; persistent inventory via Fabric `AttachmentType<CompoundTag>` on Entity/AttachmentTarget). Spike compiled green against real 26.2+fabric-api. runClient/runServer could NOT launch: fabric-api 0.152.1+26.2 `depends java >=25` but only JDK 24/17 installed — runtime save/reload check deferred until a JDK 25 toolchain exists. Foundation kept: `GolemAttachments.GOLEM_DATA`. See task-0b-report.md.

Task 1: complete (commits 10046b6..4db2f9e, review clean). KeyPool + test pass. Added testRuntimeOnly junit-platform-launcher:1.10.2 to build.gradle (Loom 1.17 needs it).

Task 2: complete (commit 5895264, review clean). Task records + TaskParser, 5 assertions pass.

Task 3: complete (commit 81a4363, build green). GolemConfig.loadOrCreate, model default set to llama-3.3-70b-versatile (Groq). config/golem.json untouched.

Task 4: complete (commit 5afca24, build green). GroqClient (OpenAI-compat, Bearer auth, json_object, key rotation). Runtime smoke test DEFERRED to JDK 25.

Task 5: complete (commit 427e2d5, build green). GolemInventory w/ hotbar (31 slots). KEY DISCOVERY: MC 26.2 uses MOJMAP names, not Yarn. SimpleContainer (not SimpleInventory), getItem/setItem/removeItem/getContainerSize (not getStack/setStack/size), ItemTags.PICKAXES/AXES via tags (no PickaxeItem class), isDamageableItem/getDamageValue. Public method signatures preserved per brief.

B1 (Task 11): complete (commit 00590b7, build green). SortTask Groq grouping + execute. Registry: BuiltInRegistries.ITEM.getKey -> net.minecraft.resources.Identifier (NOT ResourceLocation in 26.2), getValue(Identifier.parse(str)).

B2: complete (commit 3ec166f, test green). ZoneManager rectangle protection, MC-free. NBT stubbed (TODO — wire persistence in B7/B11).

B3: complete (commit 49675ce, build green). GolemInventory player layout: hotbar9+main27+armor4+offhand1=41. storage=slots0..35. No callers broke.

B4: complete (commit 6e83bcb, build green). ApprovalGate interface + ToolManager(g, gate) asks before craft/take tool. No call sites yet; B7/B8 inject real gate.

B5: complete (commit f252032, 6 tests pass). AgentPlanner + PlanStep, parse tested. NOTE: .superpowers/ scratch files are tracked in git (ignored-but-tracked); harmless, untrack at final cleanup. config/golem.json confirmed NOT tracked (secrets safe).

B6: complete (commit e1ba0db, build green). CraftingHelper + Recipes (2x2 + table-find/place). MINOR: oak-only logs (no #logs tag), single table-place spot.

B7: complete (commit 03424fb, build green). WorldGolemPrimitives (all 15 methods, real 26.2 API) + GolemController. Zone enforcement on mine/place, auto-torch via placeTorchIfNeeded. RUNTIME-UNCERTAIN (verify JDK25): (1) moveTo arrival = closerThan(2.0) not nav-complete, may stall; (2) equipTool logical-only, no hand visual; (3) GolemInventory NOT yet serialized to attachment CompoundTag — persistence bridge is follow-up (B9/B11 must wire).

B8: complete (commit ac8845c, build green). PlanExecutor: kind->macro mapping, autonomy (deposit detour), error-stop, StepState colored view, owner re-plan choices.

B9: complete (commit 4721c58, build green). GolemLife: 20HP, home-point (default -5616,64,3872), respawn keeps inventory. ServerLivingEntityEvents.AFTER_DEATH. TODO: B11 must wire GolemLife.register(Map<UUID,GolemController>) from mod init.

B10: complete (commit e993ef2, build green). [24/27=89%] Packets (6 C2S + 4 S2C, owner-checked) + ServerNetworking. GolemController gained startFromPrompt/receiveApproval/receiveErrorChoice/handleZoneEdit stubs. mod init calls Packets.register(). TODO B11: call ServerNetworking.register(registry-lookup).

B11: complete (commit 200f100, build green). [25/27=93%] GolemRegistry + onInit wiring: config load, KeyPool/GroqClient/AgentPlanner, ServerNetworking.register, GolemLife.register, END_SERVER_TICK, /golem spawn + stop. IMPORTANT TODOs: (1) startFromPrompt still STUB — planner->PlanExecutor->assign NOT connected (core flow!) — needs B11b; (2) inventory<->attachment persistence deferred (needs HolderLookup.Provider for ItemStack codec); (3) ApprovalGate placeholder (B12 real gate).

B11b: complete (commit 8d9ffc3, build green). [26/28=93%] Core flow WIRED: startFromPrompt -> AgentPlanner.plan -> PlanExecutor -> tick. controller holds planner/primitives/groq/zones/tools/crafts. planView()/status() ready. TODO: B12 push planView to client + real ApprovalGate.

C1: complete (commit e162f1f, test pass). Ores table + tier gating, pure logic.

## NEW DECISIONS (mid-exec)
- SUBAGENT MODELS: only haiku (mechanical) or sonnet (judgment). NEVER opus/fable on subagents. (user rule)
- HAND VISUAL: equipTool must set copper golem main-hand ItemStack (golem.setItemInHand) so unmodded friends see it holding pickaxe/axe. Fix B7 equipTool + wire in tool-equip flow. Caveat: 26.2 copper golem model may not render held item — set data anyway, verify visual at JDK25.
- ITEM FRAMES: golem should read item frames on chests to know chest type for sorting (frame shows diamond -> diamond chest). NEW feature, add to SortTask/sorting. NOT yet specced/built.
- B12 PARTIAL ON DISK (uncommitted): src/client GolemKeybind, net/ClientNetworking, screen/* exist from interrupted B12 dispatch. Need to finish+commit B12 properly. C1's haiku added screen stubs to make build pass.

C2: complete (commit 94e13fc, build green). [28/33=85%] MineTask incidental ore (6 neighbors, tier-checked), OreHuntTask (strip-mine, fail "need <tier> pickaxe"). TODO C3: ensurePickaxeOfTier(minTier) — currently ensureTool(PICKAXE) gets any.

C3: complete (commit 93a91ac, build green). iron+/diamond pickaxe recipes, ToolManager.ensurePickaxeOfTier, OreHuntTask uses it, planner ore_hunt kind, executor maps ore_hunt. No smelting (uses existing ingots).

## SORTING RULE CHANGED (affects D2)
- Golem uses ONLY chests with item frames. NOT allowed to pick unframed chests. Majority rule RETIRED for sorting.
- Frame = category (flower frame -> all flowers). Sign above = context to AI only.
- Item with NO matching framed chest -> PAUSE + error "no chest for <item>" in UI, leave item in place. Owner adds framed chest, types continue -> golem re-scans + places. (resume mechanism)

D1: complete (commit 5bfb223, build green). [30/33=91%] equipTool sets golem.setItemInHand(MAIN_HAND) so friends see held tool. syncHeldItem() helper. Visual confirmed only at JDK25 test (vanilla copper golem model may not render held item).

D2: complete (commit c816ffa, build green). [31/33=94%] Framed-chests-only SortTask. Added GolemPrimitives.readFramedChests(radius) + readSignAbove(chest), impl in WorldGolemPrimitives (getEntitiesOfClass(ItemFrame), SignBlockEntity.getFrontText). Pause-on-missing-frame (needsFrame, status "no chest for: ..."), resume() re-scans. SortPlanner majority now dead code (kept for Move record).

B12: complete (commit 9e6de2b, build green). [32/33=97% CODE COMPLETE] Client UI: all screens (control colored-plan, ask-gate, zone mgr, tell-it, inventory placeholder), keybind, S2C receivers, C2S senders + server push of planView/status. PLACEHOLDER: GolemInventoryScreen label-only (synced container out of scope); activeKeys/coolingKeys sent 0/0 (KeyPool counts not exposed on controller).
ONLY B13 (JDK25 in-game test) remains - user's task.

## v2 AGENT MODEL ACTIVE — see spec "Design Revision" + plan "Addendum B". Tasks now B1-B13. Old Task 12-17 superseded.

## IMPORTANT — mapping note for all remaining MC tasks
The plan's code blocks use YARN names. Real MC 26.2 = MOJMAP. Every MC-touching task (6,9,10,11,13,14,16) must translate: ServerWorld->ServerLevel, BlockPos ok, breakBlock->destroyBlock, World->Level, getStack->getItem, etc. Implementers verify against decompiled 26.2 sources. Build compiles (JDK24) but cannot RUN until JDK25.

Task 6: complete (commit cae7a3d, build green). GolemPrimitives interface, 14 methods. Mojmap: net.minecraft.core.BlockPos, net.minecraft.world.item.Item/ItemStack.

Task 5b: complete (commits 4f7c42a + fix c40c5e9, build green, review clean). ToolManager find/craft/durability/spares. CRITICAL grief bug FIXED: gatherCobble now filters to stone/cobble/deepslate via new GolemPrimitives.getBlockId(BlockPos). NOTE: Task 13 WorldGolemPrimitives must implement getBlockId via Level.getBlockState(pos).getBlock() registry lookup. Interface now has 15 methods.

Task 7: complete (commit 5a8367f, build/test green). SortPlanner majority-home consolidation + test pass.

Task 8: complete (commit f6b48d7, build green). TaskHandler interface.

Task 9: complete (commit f339742, build green). MineTask + pickaxe via ToolManager, inv-full dump, spares for bulk. Mojmap relative()/above(), isStorageFull/getItem/getContainerSize.

Task 10: complete (commit e964d2b, build green). ChopTask + axe via ToolManager, matches MineTask style. Replant deferred to primitive impl.

## FINAL REVIEW FINDINGS (fix wave dispatched)
CRITICAL-1: ask-gate hardwired desc->true, never sends AskGateS2C, receiveApproval no latch -> ask-gate NONFUNCTIONAL (auto-approves). MUST FIX (core feature).
CRITICAL-2: GroqClient.http.send blocks server tick thread (SortTask askAi, AgentPlanner.plan via startFromPrompt, PlanExecutor replan) -> freezes whole server 200-800ms. MUST FIX async.
IMPORTANT: A dead-golem tick gap on respawn (executor dropped); B no HTTP timeout; C/D/F same blocking-call variants; E GolemRegistry.all() live view - snapshot before iterate; G ToolManager makes own CraftingHelper (2 instances); H crafting table placed north only; I hardcoded DEFAULT_HOME; 
MINOR: J dumpAll loops 41 slots incl armor/offhand (should be 36); K OreHunt strip-mine branchStart Z never advances (same strip); L failed executor never cleared; N SortTask may re-sort correctly-placed items.

## Minor findings (for final review triage)
- ToolManager chest tool-match uses getDescriptionId().endsWith(toolId) — fragile; prefer registry-key compare.
- ToolManager idFor() always wooden tier (stone detection stubbed) — acceptable per brief.
- CraftingHelper Recipes: oak-only log inputs; should accept #minecraft:logs tag members.
- CraftingHelper crafting-table placement tries only one spot (north); fails if occupied — try multiple adjacent spots.
- PlanExecutor detects step failure via "fail" substring in status string — brittle; prefer an explicit failed-flag on TaskHandler.
- PlanExecutor torch step marks DONE even when torch unavailable (no explicit fail path).


- Need JDK 25 toolchain to launch dev client/server (fabric-api requires java >=25; MC 26.2 classes are class-version 69). Bump toolchain before runtime-dependent tasks.
- Re-verify attachment persistence at runtime once JDK 25 is available (spike was removed; re-add if needed).
- If injected Brain goals need new memory module types, add a small Mixin to `CopperGolem` BRAIN_PROVIDER/makeBrain, or store scheduling state in the AttachmentType.
