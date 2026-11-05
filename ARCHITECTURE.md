# Fabric Mod Template — Architecture Reference

This document serves as the primary technical specification for developers and AI coding agents working on this codebase.

---

## 1. Core Principles & Platform Realities

### Unmapped Mojang Symbols (26.x+)
Starting in Minecraft 26.1, vanilla Minecraft jars are distributed with official Mojang names in both production and development environments.
- **No Yarn or Intermediary**: You do not configure mapping namespaces. Classes, fields, and methods use official Mojang identifiers natively (e.g., `net.minecraft.client.KeyMapping`, `net.minecraft.core.BlockPos`, `net.minecraft.world.level.block.state.BlockState`).
- **Direct Java/Kotlin interop**: No symbol translation layers.

### Split Environment Source Sets
Loom's `splitEnvironmentSourceSets()` separates `src/main` (common code visible to dedicated server and client) and `src/client` (client-only rendering, screens, input, and player controllers).
- **Rule 1**: Code in `src/main` MUST NOT import or reference classes in `src/client`.
- **Rule 2**: If a class or interface is required by both client controllers and common logic, place it in `src/main`.
- **Rule 3**: Never import client-only Minecraft types (e.g., `net.minecraft.client.Minecraft`, `Screen`, `LocalPlayer`) inside `src/main`.

### Unit Testing Isolation (Decision D1 Pattern)
In modern Fabric 26.x projects, running `./gradlew test` executes within a standalone test JVM.
- **The Registry Initialization Trap**: Classes that trigger static initializers touching vanilla registries (e.g. `BuiltInRegistries`, `NoteBlockInstrument`) will fail in test JVM with `ExceptionInInitializerError` / `NoClassDefFoundError` unless the entire game is bootstrapped.
- **Pattern**: Write algorithmic engines, solvers, math, and data models as **pure functions / generic models** (e.g. accepting `String`, primitive integers, or custom property lambdas). Wrap them in thin adapters for Minecraft classes. This allows 100% JVM test coverage and instant test execution without launching Minecraft.

### Protocol Guard & Packet Budgeting
When developing automation or client-assisted features:
- **Single Movement Packet Rule**: Minecraft 26.2+ servers will kick players for `Illegal movement` if more than one `ServerboundMovePlayerPacket.PosRot` or `ServerboundMovePlayerPacket.Pos` is sent within the same client tick.
- **Rotation Gate**: If sending multiple interactions in one tick, ensure rotation convergence precedes the interactions, or send look-only updates (`ServerboundMovePlayerPacket.Rot`).
- **Sliding Rate Limiter**: Strict servers (Paper/Spigot/Folia) monitor block interaction packets per 100ms and per tick. Implement token buckets or sliding windows (e.g., capping interactions at 8 per 310ms).

---

## 2. Directory Layout & Roles

| Path | Purpose |
|---|---|
| `src/main/kotlin/kyrielie/templatemod/` | Common lifecycle entrypoints, core algorithms, domain models. |
| `src/client/kotlin/kyrielie/templatemod/client/` | Client GUI, keybind handlers, rendering, HUD, audio. |
| `src/client/kotlin/kyrielie/templatemod/client/config/` | Config persistence (`ConfigManager`), YACL screens. |
| `src/test/kotlin/kyrielie/templatemod/` | Pure unit tests running on JUnit 5 Jupiter. |
| `src/main/resources/fabric.mod.json` | Fabric metadata, entrypoint registrations, dependencies. |

---

## 3. Configuration Subsystem

The configuration follows a dual-layer pattern:
1. **Headless / File Persistence**: `TemplateModConfig.kt` via Gson with `InstanceCreator` and `sanitize()` method. Handles graceful resets and defaults even if corrupted.
2. **Interactive GUI**: `ModMenuIntegration.kt` via **YetAnotherConfigLib (YACL)**. Automatically provides an in-game configuration menu under ModMenu.

---

## 4. Verification Workflow

Before concluding any development task or pass:
1. Run `./gradlew test --no-daemon` to ensure all unit tests pass.
2. Run `./gradlew build --no-daemon` to confirm compilation, source-set integrity, and JAR packaging.
