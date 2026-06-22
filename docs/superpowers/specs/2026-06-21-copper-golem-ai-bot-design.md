# Copper Golem AI Bot — Design Spec

**Date:** 2026-06-21
**Status:** Approved design, ready for implementation plan

> **⚠️ AUTHORITATIVE DESIGN: see [Design Revision — Agent Model](#design-revision--agent-model) at the bottom.** The sections above describe the original single-task design (sort/mine/chop dispatch). The revision supersedes the command model, inventory, AI role, and adds protected zones, health/death, the ask-gate, crafting, and the colored-plan UI. Where they conflict, the revision governs. The provider is **Groq**, not Gemini (ignore "Gemini" above).

## Summary

A Fabric mod for Minecraft 26.2 ("Chaos Cubed") that turns a vanilla Copper
Golem into a private, AI-controlled bot. The owner opens a control UI (keybind),
types natural-language tasks, and a Google Gemini model parses them into
structured actions the golem executes in the world: sorting chests, mining
(cobble/dirt/stone), and chopping trees. The golem has its own hidden inventory,
accessible to the owner at any time, even mid-task.

## Goals

- Owner assigns tasks in plain language; Gemini converts to structured actions.
- Golem autonomously: **sorts existing chests** (priority), **mines NxN areas**,
  **chops trees**.
- Golem runs while the owner plays and while the owner is offline (server-side).
- Golem has a 27-slot inventory the owner can open any time, mid-task.
- Free Gemini tier viable via a **pool of ~10 API keys** with rotation.
- **Friends install nothing** — they see a normal copper golem walking; only the
  owner has UI/control.

## Non-Goals

- No per-step AI reasoning loop (too many API calls for free tier).
- No chat-command interface (UI-only, keeps the bot private from friends).
- No multi-owner / shared control in v1.
- No custom entity model — reuse the vanilla Copper Golem.

## Architecture

Server-side Fabric mod uploaded to Aternos (Aternos allows custom mod uploads
via the Mods → Upload button; fallback is exaroton if ever blocked). Owner also
runs a client-side mod for the keybind + UI. Friends run vanilla.

```
[You press G] → Golem Control UI
        │ type task, hit send
        ▼
PromptPacket (client → server, owner UUID checked)
        ▼
[GeminiClient + KeyPool]  POST → Gemini, rotate keys on 429
        │ strict-JSON response
        ▼
[TaskParser] → typed Task object
        ▼
[TaskDispatcher] → SortTask | MineTask | ChopTask
        ▼
[Task state machine] ticks each game tick, calling golem AI primitives
        ▼
Vanilla Copper Golem acts in world; StatusPacket → owner UI
```

### Sides

- **Server side:** entity AI, tasks, Gemini client + key pool, hidden inventory
  (source of truth), owner-UUID enforcement.
- **Client side (owner only):** keybind `G`, Control Screen, inventory screen,
  status display. Sends control packets, receives status packets.
- **Friends:** vanilla — render the golem natively, no mod, no control.

## Distribution Model

The golem **is a real vanilla Copper Golem entity**. The server mod attaches AI
behaviors and a hidden inventory to it (stored in server-side attached/saved data
keyed by golem UUID). Because the entity is vanilla, unmodded clients render it
and join normally.

- **Aternos server:** mod installed.
- **Owner client:** mod installed (UI + keybind).
- **Friends:** nothing installed; see a normal copper golem wandering.

Owner enforcement: server checks owner UUID on every control packet. Non-owner
packets rejected. No chat commands exist, so friends never see task text.

**Risk:** a 27-slot inventory on a vanilla copper golem is not a vanilla feature.
Stored via Fabric attachment / saved world data keyed by UUID. If the mod is
removed, that inventory data is orphaned. Acceptable.

## Components

Each unit has one purpose, a clear interface, and is testable in isolation.

1. **`CopperGolemMod`** — mod entrypoint, registration, config load.
2. **`BotGolemController`** — attaches AI + inventory to a vanilla copper golem,
   tracks owner UUID, holds current task. (Not a custom entity — a controller
   bound to a vanilla entity instance.)
3. **AI primitives** (`ai/`) — `moveTo(pos)`, `mineBlock(pos)`, `placeBlock`,
   `pickupNearbyItems(radius)`, `pullFrom(chest)`, `pushTo(chest, filter)`.
   Wrap vanilla navigation + world edits. Task handlers call only these.
4. **`GolemInventory`** — 27 slots, server-side, owner-openable any time.
5. **`KeyPool`** — N keys, each `ACTIVE` or `COOLING(until)`. Returns next active
   key; marks cooling on 429. Pure logic, unit-tested with mocked 429.
6. **`GeminiClient`** — HTTP to Gemini, strict-JSON (responseSchema), 1 retry on
   bad JSON, uses KeyPool. Two call shapes: command-parse, sort-plan.
7. **`TaskParser`** — Gemini JSON (or error) → typed `Task`. Pure, unit-tested.
8. **`TaskDispatcher`** — `Task` → handler.
9. **Task handlers** — `SortTask`, `MineTask`, `ChopTask`. State machines ticked
   per game tick.
10. **Networking** (`net/`) — shared packets `PromptPacket`, `StopPacket`,
    `PausePacket` (client→server), `StatusPacket` (server→client).
11. **Client UI** (`src/client/`) — `GolemKeybind`, `GolemControlScreen`,
    inventory screen, `ClientPacketHandlers`.
12. **`GolemConfig`** — loads `config/golem.json`.

## Gemini Integration

- **Model:** `gemini-2.0-flash` (free tier, fast). Configurable.
- **Role:** command parser only — 1 API call per assigned task. Sort uses 1 call
  (or N if the snapshot is batched). No per-step loop.
- **Output:** strict JSON via responseSchema. Bad/non-JSON → 1 retry → graceful
  fail ("couldn't understand, rephrase").

### Key Pool & Limits

- ~10 keys in `config/golem.json`.
- Per key: `ACTIVE` or `COOLING(until timestamp)`.
- Request flow: pick first ACTIVE key → on HTTP 429 mark COOLING (60s rate-limit;
  longer for daily cap) → try next → all COOLING ⇒ request fails.
- **On all keys cooling:** UI status shows `Gemini busy — all keys cooling,
  retry in Ns`; new prompts refused; running tasks finish.

### Config (`config/golem.json`) — server-side, gitignored, never committed

```json
{
  "geminiKeys": ["KEY1", "...", "KEY10"],
  "model": "gemini-2.0-flash",
  "sortRadius": 30,
  "ownerBindRequired": true
}
```

**Security:** keys live only in this server-side file. Added to `.gitignore`.
Never in source, never committed.

## Tasks (v1)

### SortTask (priority)

- Scan **all chests in a 30×30 area** (configurable `sortRadius`). No designated
  dump/input chest — sort *among* existing chests, consolidating chaos.
- **No hardcoded categories.** Gemini groups items by similarity.
- **Group → chest assignment by existing majority:** the chest already holding the
  most of a group becomes that group's home; strays are consolidated into it.
  Empty chests are available for groups that have no current home.
- Flow: `SCAN` (chests + contents → snapshot) → `ASK_GEMINI` (snapshot →
  `{itemType → targetChestId}` map, respecting majority clusters) → `PLAN_MOVES`
  (diff current vs target, batch by route) → `EXECUTE` (walk, pull misplaced
  stacks, push to target) → `DONE` (status summary).
- **Edge — large snapshot:** cap snapshot size; if a 30×30 region has too many
  chests/items for one prompt, sort in sub-region batches (multiple Gemini calls).

### MineTask (cobble / dirt / stone, NxN)

- Gemini provides `{w, h, length, dir, filter?}` (e.g. 3×3 tunnel, 16 long,
  north). Optional `filter` mines only matching blocks; otherwise all.
- Drops collected into golem inventory. **Inventory full** → return to nearest
  chest, dump, resume.
- Flow: `PLAN_CELLS` → per cell `{WALK → MINE → PICKUP}` → `INV_FULL?` dump →
  `DONE`.

### ChopTask (trees)

- Detect tree (log column + leaves above), pick nearest in radius.
- Chop full trunk upward, collect logs + drops (saplings/apples). Optional
  replant.
- Flow: `FIND_TREE → WALK → CHOP_UP → PICKUP` → next tree or `DONE`.

All handlers use only the shared AI primitives, so each is testable against a
mock world.

## Tools, Hotbar & Durability

The golem must hold and use proper tools, not mine bare-handed.

- **Hotbar:** in addition to the 27-slot storage, the golem has a small hotbar
  (tool slots) and an "active tool". Mining requires a pickaxe equipped;
  chopping requires an axe. (Dirt needs no tool, but stone/cobble do — the
  golem still equips a pickaxe for any mine job to be safe.)
- **Tool sourcing order** (run before a tool-requiring task starts, and again
  whenever the active tool breaks):
  1. Matching tool already in inventory/hotbar → equip it.
  2. Else scan chests in radius for a matching tool → pull, equip.
  3. Else have crafting materials (in inventory or pullable from chests) →
     craft the tool. Craft-from-materials only: planks+sticks → wooden,
     cobblestone+sticks → stone. (Sticks craftable from planks.)
  4. Else gather materials by mining/chopping, then craft.
  5. Still impossible → fail task with status (e.g. "no pickaxe and no
     materials").
- **Durability:** track the active tool's damage. When it is near breaking,
  fetch or craft a replacement before continuing.
- **Bulk jobs:** for large requests ("mine 1000 stacks of cobble"), the golem
  estimates how many tools the job needs and pre-stocks **multiple** spare
  tools (find + craft) so it never stalls mid-job. Stacking spares in the
  hotbar/storage is expected behavior.
- **Component:** a `ToolManager` owns this logic — `ensureTool(type)`,
  `onToolBroken()`, `stockSpares(count)` — used by `MineTask` and `ChopTask`
  via the primitives. Keeps tasks free of tool plumbing.

## Control UI (owner client only)

- **Keybind:** default `G`, rebindable in MC Controls. Opens Control Screen.
- **Prompt box:** type task → `PromptPacket` → server parse path.
- **Stop button:** `StopPacket` → abort current task, golem idles.
- **Pause/Resume:** `PausePacket` → suspend without losing progress.
- **View Inventory button:** opens the golem's 27-slot inventory (mid-task OK).
- **Status line:** current task + state (`Sorting: 3/8 chests`), key-pool status
  (`keys: 7 active / 3 cooling`) — fed by `StatusPacket`.

## Error Handling

- Gemini down / all keys cooling → UI status message, no crash.
- Bad JSON from Gemini → 1 retry → "couldn't understand, rephrase".
- Pathfind stuck (cell unreachable) → skip cell, log; repeated fails → abort task
  with status message.
- Inventory full mid-mine → auto-dump to nearest chest, resume.
- Golem chunk unloaded → task pauses, resumes on reload.
- Non-owner control packet → rejected server-side.

## Testing

- **`KeyPool`** — unit test rotation/cooling with mocked 429. No MC.
- **`TaskParser`** — sample Gemini JSON → assert `Task`. No MC.
- **Task state machines** — mock world interface, assert transitions.
- **Manual in-game** — sort/mine/chop in a test world; verify friends (vanilla)
  see the golem and cannot control it.
- TDD for pure logic (KeyPool, parser, classification); MC-coupled code uses thin
  mocks + manual verification.

## Project Layout

```
build.gradle, fabric.mod.json
.gitignore                         (excludes config/golem.json)
src/main/java/.../coppergolem/
  CopperGolemMod.java              (entrypoint)
  entity/BotGolemController.java
  inventory/GolemInventory.java
  ai/                              (primitives: move/mine/place/chest IO)
  task/  Task, TaskDispatcher, SortTask, MineTask, ChopTask
  gemini/ GeminiClient, KeyPool, TaskParser
  net/   PromptPacket, StopPacket, PausePacket, StatusPacket (shared)
  config/ GolemConfig
src/client/java/.../coppergolem/
  GolemKeybind.java
  screen/GolemControlScreen.java
  screen/GolemInventoryScreen.java
  net/ClientPacketHandlers.java
src/test/java/...                  (KeyPool, TaskParser tests)
config/golem.json                  (gitignored, server-side)
```

**Build target:** Fabric Loader + Fabric API for MC 26.2, Java 21.

## Risks / Open Items

1. **MC 26.2 toolchain.** Very new version. Yarn mappings + Fabric API for 26.2
   may be partial. **First implementation step: verify the toolchain exists for
   exactly 26.2.** If not, fall back to nearest supported snapshot and note the
   delta.
2. **Vanilla Copper Golem API surface.** Confirm 26.2 exposes the copper golem
   entity in a way the mod can spawn + attach AI to. Fallback chain (a custom
   entity is **rejected** — it would force friends to install the jar):
   - **Plan A (preferred):** drive a real vanilla copper golem (visual; friends
     install nothing).
   - **Plan B (fallback, if A impossible):** **bodiless mode** — no entity
     spawned. Tasks run as server-side world edits (break blocks, move items,
     sort chests) with no visible mob. Owner keeps full UI/keybind/control and the
     golem inventory (saved data, not attached to a mob). Friends see nothing and
     install nothing. Tradeoff: lose the walking-golem visual.
3. **Orphaned inventory data** if mod removed (accepted).
4. **Large sort snapshot** prompt size → batching mitigation.
5. **Gemini free-tier daily cap** even with 10 keys — pool extends, not removes,
   the limit; block-at-limit behavior covers it.
```
---

# Design Revision — Agent Model

This revision is the authoritative design. It changes the command model from
"AI parses one fixed task" to "AI plans a whole job as ordered steps the golem
executes autonomously", and adds protected zones, player-style inventory,
health/death, an ask-gate for gear, in-world crafting, auto-torching, and a
colored live-plan UI. Provider is **Groq** (`llama-3.3-70b-versatile`).

## Scope (this version)

In scope: **sort chests, mine materials, chop trees, protected safe zones,
the control UI**. Deferred to a later version: combat (attack/defend),
follow/come, building/placing as standalone, giving items as standalone,
continuous standing-orders.

## 1. Command Model — Plan-Ahead Agent

- Owner types a free-form prompt ("mine 2000 cobble", "sort my chests", "chop
  the trees near me"). One Groq call returns a **whole-job plan**, not a single
  task.
- The plan is an **ordered list of steps**; the AI decides the granularity
  (sensible high-level steps, e.g. "Craft 5 pickaxes", "Mine 2000 cobble",
  "Deposit in chest"). Each step maps to a built-in macro/primitive.
- The plan is **resource-aware**: the AI accounts for tool durability and how
  many spares to carry (e.g. ~500 cobble per iron pickaxe → 5 picks for 2000),
  reserves inventory slots for those spares, and plans return-trips to deposit
  when the inventory fills. Numbers are planned, not random.
- **Execution is deterministic mod logic** — no per-step AI calls (free-tier
  safe). Built-in autonomy handles known interruptions: inventory full → deposit
  to a matching chest → resume; tool worn out → equip/craft a spare → resume.

### Primitives the planner composes (this version)

`mine-area` (NxN, pickaxe), `chop-trees` (axe), `sort-chests` (Groq grouping,
majority-home), plus the support behaviors below (craft, deposit, torch,
acquire-tool). Targeting accepts explicit coords in the prompt
("at 100 64 -200") or relative phrasing ("here", "in front"); the mod resolves
relative targets from the golem/owner position.

### Errors

On any step failure (path blocked, no tool, protected zone, missing table) the
golem **stops** and surfaces the failed step + reason. The owner chooses:
**tell it what to do** / **stop** / **"do it yourself"** → the latter sends the
error + situation back to Groq for a re-plan around the obstacle (one extra
call, only on owner request), then resumes autonomously.

## 2. Inventory — Player Layout

The golem's inventory mirrors a player's: **hotbar (9) + main (27) + armor (4) +
offhand (1)**. The owner can open it any time (even mid-job) and manually
add/remove items. Stored server-side via Fabric attachment keyed by the golem's
UUID; kept across death (see §5).

## 3. Gear Acquisition — Two Paths + Ask-Gate

Applies uniformly to **tools, weapons, armor, and materials/torches**:

- **"I'll give you X"** → the owner hand-places it in the golem inventory via UI.
- **"Get yourself X"** → the golem finds it in nearby chests, or crafts it.
- **Ask-gate:** before **crafting OR taking** a tool/weapon/armor (and before
  crafting/taking torches), the golem **pauses and asks** the owner
  ("I want to take/craft <item> — ok?"), showing the item. The golem idles until
  answered.
- **Pre-approve per job:** a checkbox at job start ("allow tools/armor/crafting
  for this job") suppresses the per-item asks for that job. Unchecked → it asks
  each time.
- The golem keeps a **safety reserve of spare tools** (and a stack of torches)
  sized to the planned job.

## 4. Crafting

- **2×2 in-inventory crafting** for small items with no table: sticks, torches,
  planks, wooden tools.
- **3×3 crafting needs a crafting table.** The golem first **searches** for a
  table nearby (placed or in chests); if none, it **crafts and places its own**
  table (asking per the gate), uses it, then picks it back up.

## 5. Health, Death & Home Point

- Golem has **20 HP (10 hearts)**, like a player, and **can die**.
- A configurable **home point** (UI/config; near the village around
  `-5616 ~ 3872`, exact coords TBD by owner). On death the golem **respawns at
  the home point**.
- **Inventory is kept** across death (it is a robot — nothing drops).

## 6. Auto-Torching Underground

- The golem carries a **safety stack of torches** for underground jobs (acquired
  per the ask-gate; placing existing torches needs no ask).
- During mining/underground work it **automatically places torches** for light
  at sensible spacing.

## 7. Protected Safe Zones (Anti-Grief)

The core protection against the golem wrecking player builds (which are made of
the same blocks it mines).

- Zones are **defined in the UI** by entering corner coordinates → a **rectangle
  (X/Z)**. No physical marker block.
- Each zone spans **bedrock to sky** (full vertical column of the rectangle).
- Each zone has a **name** ("base", "house"); the owner can **rename, edit
  coordinates, and delete** zones. Multiple zones allowed.
- **Inside any zone the golem may ONLY sort chests and give/fetch items**
  (non-destructive). It must **never mine, place, build, or otherwise alter
  blocks** inside a zone. Mining/placing primitives consult the zone manager and
  refuse positions inside any zone (treated as a step error → §1 error flow).
- This lets the owner wrap their base once and mine the same block types freely
  elsewhere.

## 8. Control UI (owner client only)

- Keybind opens the panel. **Prompt box** to type the job; **per-job
  pre-approve** checkbox.
- **Colored live plan:** the AI's plan rendered as a checklist —
  🟢 green = done, 🔵 blue = currently doing, 🔴 red = failed (with the error).
  Steps tick through live.
- **Ask-gate prompt:** when the golem asks to take/craft gear, the UI shows the
  request with approve/deny.
- **Stop / error choices:** stop the job; on error choose tell-it / stop /
  do-it-yourself.
- **Zone manager:** add/edit/rename/delete named protected zones (corner coords).
- **Inventory view:** the golem's full player-style inventory (hotbar/main/armor/
  offhand), drag items in/out.

## 9. Unchanged from the original design

Plan A vanilla Copper Golem (friends install nothing), owner-UUID enforcement,
Groq key pool with block-at-limit, server-side persistence, runs while owner is
offline.

## Reconciliation with already-built code

Already built and **kept**: KeyPool, GroqClient, GolemConfig, ToolManager
(find/craft/durability/spares + stone-only gather), SortPlanner, SortTask,
MineTask, ChopTask, GolemPrimitives (interface), TaskHandler, GolemInventory.

**Changes required by this revision:**
- `GolemInventory`: expand from 27+4 to full player layout (hotbar 9 + main 27 +
  armor 4 + offhand 1); add 2×2 craft support hooks.
- AI role: add an `AgentPlanner` (prompt → ordered plan via Groq) and a
  `PlanExecutor` (runs steps, built-in autonomy, error-stop). `TaskParser`
  becomes plan parsing. The single-task `TaskDispatcher` is folded into the
  executor (each step still backed by SortTask/MineTask/ChopTask macros).
- New components: `ZoneManager` (named zones, `isProtected(BlockPos)`, enforced
  in mine/place primitives), `CraftingHelper` (2×2 + table find/place/3×3),
  `GolemHealth`/home-point + respawn, ask-gate state in the controller, the
  colored-plan + zone-manager + ask-prompt UI.
- Permission flow: `ToolManager` gains an **ask-gate** hook (request → await
  owner approval) instead of fully-automatic craft/take, with per-job
  pre-approval bypass.
