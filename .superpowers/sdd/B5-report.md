# B5 Report — AgentPlanner with Plan-Step Parsing

## Files Created

- `src/main/java/com/example/coppergolem/agent/PlanStep.java`
  — `record PlanStep(String kind, Map<String,String> args, String label)`
- `src/main/java/com/example/coppergolem/agent/AgentPlanner.java`
  — `AgentPlanner(GroqClient ai)`, `plan(String, String)`, `static List<PlanStep> parse(String)`
- `src/test/java/com/example/coppergolem/agent/PlanStepParseTest.java`
  — 6 JUnit 5 tests covering: 2-step parse, kinds, labels, args, malformed → empty, empty plan → empty

## Test Command

```
.\gradlew.bat test --tests "com.example.coppergolem.agent.PlanStepParseTest"
```

## TDD Cycle

### RED
- Test written first for `parse()` with the exact sample JSON from the brief.
- Ran `.\gradlew.bat test --tests ...` → BUILD FAILED (12 compiler errors: cannot find symbol `AgentPlanner`, `PlanStep`).
- Failure is correct: production classes did not exist.

### GREEN
- Created `PlanStep.java` (plain record).
- Created `AgentPlanner.java` with `parse(String)` using Gson `JsonParser`.
- Ran tests → `BUILD SUCCESSFUL in 2s` (all 6 tests pass).

### Full suite
- `.\gradlew.bat test` → `BUILD SUCCESSFUL in 1s` — no regressions.

## Implementation Notes

- `parse()` is `static`, pure, no network, tested directly.
- On any exception (malformed JSON, missing keys, wrong types) returns `Collections.emptyList()`.
- `args` map entries use `getAsString()` on each `JsonElement` as specified.
- `plan()` builds a resource-aware system prompt referencing tool durability and inventory space, then delegates to `ai.generateJson(system, userText)`.
- GroqClient signature confirmed: `Optional<String> generateJson(String systemInstruction, String userText)`.

## Concerns

None. All specified behavior is unit-tested. Network path in `plan()` is manual-only as required.
