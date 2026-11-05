# FN Litematica Printer — Architecture Reference

Technical specification and architectural guide for FN Litematica Printer.

---

## 1. Platform Realities & Core Principles

### Unmapped Mojang Symbols (Minecraft 26.x+)
Vanilla Minecraft jars ship with official Mojang names in production and development:
- Classes, fields, and methods use official Mojang identifiers (`net.minecraft.client.KeyMapping`, `net.minecraft.core.BlockPos`, `net.minecraft.world.level.block.state.BlockState`).
- No Yarn or Intermediary translation layer.

### Split Environment Source Sets
Loom's `splitEnvironmentSourceSets()` separates `src/main` (common code) and `src/client` (client-only rendering, screens, input, and controllers).
- **Rule 1**: Code in `src/main` MUST NOT import or reference classes in `src/client`.
- **Rule 2**: Never import client-only Minecraft types (e.g. `Minecraft`, `Screen`, `LocalPlayer`) inside `src/main`.

### Unit Testing Isolation (Decision D1 Pattern)
Running `./gradlew test` executes within a standalone test JVM without game bootstrapping.
- Mathematical functions, bit models, and sorting logic live as pure Kotlin algorithms in `src/main/kotlin/kyrielie/printer/`.
- Allows 100% JVM test coverage and instant test execution.

### Dynamic Motion & Reach Clamping
- When placing blocks while the player is moving, the player eye vector is forward-projected using `deltaMovement`.
- Survival reach is hard-clamped to $\le 4.5$ blocks.

### Protocol Movement Guard
- In Minecraft 26.2+, sending more than ONE position packet per tick causes server kicks.
- `ProtocolMovementGuard` ensures camera aiming updates dispatched by the printer are safely downgraded to look-only (`ServerboundMovePlayerPacket.Rot`).

---

## 2. Directory Layout & Roles

| Path | Purpose |
|---|---|
| `src/main/kotlin/kyrielie/printer/` | Common lifecycle entrypoint, pure models (`PlacementTarget`), build order planner, Carpet protocol encoding, vector math. |
| `src/client/kotlin/kyrielie/printer/client/` | Client initialization, keybinding hooks, tick dispatching. |
| `src/client/kotlin/kyrielie/printer/client/player/` | `PrinterController`, `ProtocolMovementGuard`, `FaceRaycaster`, `InventoryRestockManager`. |
| `src/client/kotlin/kyrielie/printer/client/guides/` | `PlacementGuideEngine`, `ScaffoldHelper` (3-phase autonomous scaffolding). |
| `src/client/kotlin/kyrielie/printer/client/bridge/` | `LitematicaBridge` (dynamic reflection hook into Litematica). |
| `src/client/kotlin/kyrielie/printer/client/config/` | `PrinterConfig`, `ConfigManager`, `PrinterYaclConfig`, ModMenu integration. |
| `src/client/kotlin/kyrielie/printer/client/gui/` | `PrinterHud` in-game stats overlay. |
| `src/test/kotlin/kyrielie/printer/` | Pure unit tests on JUnit 5 Jupiter. |
| `src/main/resources/fabric.mod.json` | Fabric metadata, entrypoint registrations, dependencies. |
