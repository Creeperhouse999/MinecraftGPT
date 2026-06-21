# Task 0 Report: Scaffold Fabric 26.2 Mod + Verify Toolchain

## Status
DONE_WITH_CONCERNS

## Files Created
- `gradle.properties` — versions as specified in brief
- `settings.gradle` — fabricmc maven + gradlePluginPortal
- `build.gradle` — Loom plugin + dependencies (see version pin notes)
- `.gitignore` — updated existing file (removed `.superpowers/` exclusion that was already present)
- `gradle/wrapper/gradle-wrapper.properties` — Gradle 9.5.1
- `gradle/wrapper/gradle-wrapper.jar` — downloaded from GitHub gradle/gradle v9.5.1
- `gradlew.bat` — Windows Gradle wrapper script
- `src/main/resources/fabric.mod.json` — mod metadata with version expansion
- `src/main/java/com/example/coppergolem/CopperGolemMod.java` — server entrypoint
- `src/client/java/com/example/coppergolem/client/GolemClientMod.java` — client entrypoint stub (required by splitEnvironmentSourceSets + fabric.mod.json client entrypoint)

## Build Command Run
```
.\gradlew.bat build
```

## Full Build Output (final successful run)
```
WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by net.rubygrapefruit.platform.internal.NativeLibraryLoader in an unnamed module
WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
WARNING: Restricted methods will be blocked in a future release unless native access is enabled

> Configure project :
Fabric Loom: 1.17.12

> Task :compileJava UP-TO-DATE
> Task :processResources
> Task :classes
> Task :compileClientJava UP-TO-DATE
> Task :processClientResources NO-SOURCE
> Task :processIncludeJars UP-TO-DATE
> Task :clientClasses UP-TO-DATE
> Task :jar
> Task :compileTestJava NO-SOURCE
> Task :assemble
> Task :processTestResources NO-SOURCE
> Task :testClasses UP-TO-DATE
> Task :test NO-SOURCE
> Task :validateAccessWidener NO-SOURCE
> Task :check UP-TO-DATE
> Task :build

BUILD SUCCESSFUL in 1s
5 actionable tasks: 2 executed, 3 up-to-date
```

## Version Pins Changed from Brief
| Setting | Brief Value | Actual Resolved |
|---------|------------|-----------------|
| `loom_version` | `1.17-SNAPSHOT` | resolved to `1.17.12` (SNAPSHOT resolved correctly) |
| `fabric_version` | `0.152.1+26.2` | accepted (no resolution error; Loom fetches from fabricmc maven) |
| `loader_version` | `0.19.3` | accepted |
| `minecraft_version` | `26.2` | accepted (current release as of 2026-06-16) |

`gradle.properties` was left with `loom_version=1.17-SNAPSHOT` as specified. SNAPSHOT resolved to `1.17.12` at runtime.

## CONCERN: modImplementation replaced with implementation
The brief specified `modImplementation` for fabric-loader and fabric-api dependencies. Loom 1.17.12 with `splitEnvironmentSourceSets()` for MC 26.x (unobfuscated) does **not** register a `modImplementation` configuration. Using `modImplementation` caused:

```
Could not find method modImplementation() for arguments [net.fabricmc:fabric-loader:0.19.3]
```

**Resolution**: Changed to `implementation` for both `fabric-loader` and `fabric-api` dependencies. This is the correct approach for unobfuscated (no-remap) MC 26.x where Loom does not provide the `modImplementation` shim. The build compiles and produces `build/libs/MinecraftGPT-0.1.0.jar` (2498 bytes).

## CONCERN: fabric-api resolution status
The `fabric-api:0.152.1+26.2` dependency was accepted by Loom without a resolution error during compilation, but `processResources` and `compileJava` succeeded without needing to download fabric-api classes (Loom may lazily resolve at runtime only). Full runtime classpath resolution was not tested (no `runClient`/`runServer`).

## CONCERN: Java 24 / Gradle 9.5.1 native access warning
Java 24 (installed) emits a `WARNING: A restricted method in java.lang.System has been called` from `native-platform-0.22-milestone-29.jar`. This is a warning only and does not affect the build. Will be resolved when Gradle updates native-platform.

## Commit
- Short hash: `93762d3`
- Message: `chore: scaffold Fabric 26.2 mod, verify toolchain`
