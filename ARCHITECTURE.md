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

### Protocol Movement Guard & Silent Packet Aiming (Approach C)
- In Minecraft 26.2+, sending more than ONE position packet per tick causes server kicks.
- `ProtocolMovementGuard` ensures camera aiming updates dispatched by the printer are safely downgraded to look-only (`ServerboundMovePlayerPacket.Rot`).
- **Silent Packet Aiming (Approach C)**: Through `LocalPlayerMixin`, the client's outgoing movement packet look angles are decoupled from the camera orientation. The player retains full free-look control while exact placement vector look packets are transmitted silently right before block interaction, eliminating camera jitter and movement interference.

### Server Telemetry & Verification Loop
- Incoming server confirmations (`ClientboundBlockChangedAckPacket`), server block state syncs (`ClientboundBlockUpdatePacket`), multi-block chunk updates (`ClientboundSectionBlocksUpdatePacket`), and position corrections (`ClientboundPlayerPositionPacket`) are tracked by `ServerTelemetryTracker`.
- Multi-block updates (`handleChunkBlocksUpdate`) run block updates through `ServerTelemetryTracker.onBlockUpdate` to ensure positions in chunk batches release their in-flight tracking and placement leases.
- Mixins hook `ClientPacketListener` at `@At("TAIL")` to execute safely on the Minecraft Render thread after `PacketUtils.ensureRunningOnSameThread()`, preventing cross-thread rendering exceptions (e.g., Sodium RenderAsserts).
- When the server syncs a hotbar slot count via `ClientboundContainerSetSlotPacket`, rollback tracking is synchronized immediately to prevent false-positive rollback cooldown lockouts.
- Server air-rejection auto-heal lease is reduced to 350ms to allow immediate re-evaluation without locking out placement.

### Interactive Block Sneak-Placing & Shift-Click Protection
- When right-clicking interactive blocks (`NoteBlock`, `BaseEntityBlock` containers, crafting tables, repeaters, comparators, trapdoors), the placement action is executed via `withShift(player, true)`.
- Dispatches a temporary `ServerboundPlayerInputPacket` with `shift = true` and updates `player.setShiftKeyDown(true)` so both client and server bypass block interaction (e.g. tuning noteblocks or opening chests) and execute standard item placement.
- In-place block transformations (`TransformBlock`, such as axe-stripping logs) also check for interactive blocks and respect tool hotbar slot switching pauses.

### Scaffolding Axis & Orientation Constraints
- Scaffold positions are strictly forbidden from occupying any coordinate where the schematic defines a block (`schemState.isAir` must be true).
- Axis-dependent blocks (`RotatedPillarBlock` logs, pillars) enforce contact face axis alignment (`faceToTarget.axis == reqAxis`). Vertical pillars strictly require scaffolding directly below (`Direction.DOWN`) or above (`Direction.UP`), prohibiting horizontal side attachments that would produce incorrectly rotated logs.
- User-configurable whitelist (`scaffoldBlockWhitelist`) with `strictScaffoldWhitelist` prevents unwanted junk blocks (like cobblestone) from ever being deployed.

### Diagnostic Logging & Stall Analysis
- When block placement stalls (e.g. no valid candidates or all candidates rejected), `PrinterController` computes an aggregated failure diagnostic summary rate-limited to once every 3.0 seconds.
- Rejections are categorized across all candidate blocks in survival reach: missing items (grouped by identifier and quantity), unsupported blocks requiring scaffolding, orientation/angle infeasibilities, raycast occlusions, safety gate blocks, player AABB collisions, and Litematica render layer filtering.
- **30-Second Stall Alert**: If the printer is active but fails to place any blocks for 30 consecutive seconds, it dispatches an explicit in-game alert to the player (`[Printer] Stalled (30s): <Reason>`) detailing whether the halt is due to depleted inventory materials, layer-range restrictions, lack of scaffolding support, or no blocks remaining in range.
- Multi-stage block transformations (e.g. placing unstripped logs as Stage 1 of stripped logs) are recognized by `ServerTelemetryTracker` as valid intermediate states (`[Server STAGE 1]`), eliminating false-positive `[WRONG BLOCK MISPLACED]` error spam.

### Item Depletion & Intelligent Hotbar Restocking
- When replenishing depleted stacks from main inventory (slots 9..35) to the hotbar (slots 0..8), `InventoryRestockManager` avoids blindly overwriting the active hotbar slot.
- Slot selection prioritizes:
  1. The current active slot if empty (`count == 0`).
  2. Any other empty hotbar slot (`isEmpty || count == 0`).
  3. Non-tool hotbar slots (avoiding weapons, digger tools, shears, and durability items).
- Rollback count tracking is reset immediately during restocking and hotbar switches to prevent false-positive server desync rollbacks.
- The block switch delay cooldown is strictly constrained to fire only when the held item or selected hotbar slot actually changes.

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
| `src/client/kotlin/kyrielie/printer/client/telemetry/` | `ServerTelemetryTracker` tracking live ACKs, rollbacks, and rubberbands. |
| `src/client/java/kyrielie/printer/client/mixin/` | Mixins into `ClientPacketListener`, `ClientCommonPacketListenerImpl`, and `LocalPlayer`. |
| `src/client/resources/litematica_printer.client.mixins.json` | Client mixin configuration. |
| `src/test/kotlin/kyrielie/printer/` | Pure unit tests on JUnit 5 Jupiter. |
| `src/main/resources/fabric.mod.json` | Fabric metadata, entrypoint registrations, dependencies. |
