# fn-litematica-printer

<p align="center">
  <img src="src/main/resources/assets/litematica_printer/icon.png" alt="FN Litematica Printer Icon" width="128" height="128" />
</p>

<p align="center">
  <a href="https://fabricmc.net/"><img src="https://img.shields.io/badge/Minecraft-26.3-informational?style=flat-square&logo=minecraft" alt="Minecraft 26.3" /></a>
  <a href="https://fabricmc.net/"><img src="https://img.shields.io/badge/Fabric%20Loader-%3E%3D0.19.5-blue?style=flat-square" alt="Fabric Loader" /></a>
  <a href="https://adoptium.net/"><img src="https://img.shields.io/badge/Java-25-orange?style=flat-square&logo=openjdk" alt="Java 25" /></a>
  <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Kotlin-2.4.0-purple?style=flat-square&logo=kotlin" alt="Kotlin" /></a>
  <a href="https://github.com/isXander/YetAnotherConfigLib"><img src="https://img.shields.io/badge/Config-YACL%20v3-green?style=flat-square" alt="YACL Config" /></a>
  <a href="https://modrinth.com/mod/modmenu"><img src="https://img.shields.io/badge/ModMenu-Supported-brightgreen?style=flat-square" alt="ModMenu" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow?style=flat-square" alt="MIT License" /></a>
</p>

Yet another Litematica printer mod.

---

## Key Features

- **Decoupled Packet Look Synchronization (Approach C)**: Synchronizes the player's look direction with the server via the primary per-tick movement packet (`LocalPlayer.sendPosition()`). Your screen never snaps, camera view is completely untouched, WASD movement is unimpeded, and the server receives exact raycast orientations on every placement.
- **Three Placement Modes**:
  1. `DECOUPLED_PACKET` *(Default & Recommended)*: Server packet look alignment; camera untouched, movement unimpeded, 98%+ placement confirmation rate.
  2. `CROSSHAIR_SWEEP`: Passive placement; only places blocks when your crosshair naturally sweeps across valid block faces within a configured angle threshold (3°–15°).
  3. `AIM_ASSIST`: Smoothly guides your physical camera towards target blocks before placing.
- **Dynamic Motion Compensation**: Forward-projects player eye position based on velocity vectors (`player.deltaMovement * 0.5`) to eliminate "out of reach" and "line of sight" rejections while walking, sprinting, or flying.
- **Strict Contact Placement (No Air-Place)**: Multi-point UV face raycasting (`ClipContext.Block.OUTLINE` with Baritone-inspired 5-point sampling) guarantees blocks are only placed against valid, unobstructed solid neighbor faces.
- **Autonomous 3-Phase Scaffolding**: Automatically detects floating or isolated blocks with no solid neighbors, places temporary support blocks (e.g., Dirt), anchors the schematic block against the scaffold, and clears the scaffold.
- **Multi-Click Progression**: Automatically tunes repeaters (delay 1–4), comparators (comparison vs subtraction), and note blocks (pitch 0–24) to match target schematic states.
- **Carpet Protocol & Vanilla Dual-Mode**: Natively encodes block directional facings into hit vectors using Carpet Extra `accurateBlockPlacement` v2 and v3 protocols when connected to compatible servers.
- **Smart Inventory Restock**: Automatically restocks needed blocks from main inventory (slots 9–35) into the active hotbar slot using asynchronous `ContainerInput.SWAP` (`handleContainerInput`), with native offhand placement support.
- **Real-Time Server Telemetry & Rollback Recovery**: Intercepts server sequence acknowledgments (`ClientboundBlockChangedAckPacket`), confirmations, and rejections (`ClientboundBlockUpdatePacket`) with live latency (ms) tracking. If the server cancels a placement, client ghost block predictions are cleared immediately to prevent crosshair desync.
- **Clean Litematica Hook**: Dynamically detects and interfaces with live Litematica schematics, sub-regions, and render-layer limits via reflection without hard crash dependencies.

---

## Controls

| Key | Default Action |
| :---: | :--- |
| <kbd>V</kbd> | Toggle Printer **ON** / **OFF** |

*Keybinding can be rebound in **Options → Controls → Key Binds → Litematica Printer**.*

---

## Configuration

Settings can be customized in-game via **ModMenu** (powered by YetAnotherConfigLib) or by editing `.minecraft/config/litematica_printer.json`:

| Setting | Type | Default | Description |
| :--- | :---: | :---: | :--- |
| **Enable Printer** | `Boolean` | `false` | Master toggle switch for automatic placement. |
| **Placement Look Mode** | `Enum` | `DECOUPLED_PACKET` | Selection between `DECOUPLED_PACKET`, `CROSSHAIR_SWEEP`, and `AIM_ASSIST`. |
| **Survival Reach Limit** | `Double` | `4.5` | Maximum sphere reach distance from eye position (clamped 1.0–4.5 blocks). |
| **Blocks Per Tick** | `Integer` | `2` | Maximum placement budget per game tick (1–8 blocks). |
| **Max Look Angle Limit** | `Double` | `10.0` | Angular tolerance cone in degrees for Sweep mode (bounded 3.0°–15.0°). |
| **Carpet Protocol** | `Boolean` | `true` | Enables Carpet Extra v2/v3 facing encoding in hit vectors. |
| **Enable Scaffolding** | `Boolean` | `true` | Allows placing temporary dirt blocks to support floating structures. |
| **Multi-Click Progression** | `Boolean` | `true` | Automatically tunes repeaters, comparators, and note blocks. |
| **Debug Logging** | `Boolean` | `false` | Enables verbose packet telemetry (ACKs, confirmations, candidate diagnostics). |

---

## Project Architecture

```
fn-litematica-printer/
├── src/
│   ├── main/
│   │   ├── kotlin/kyrielie/printer/
│   │   │   ├── PrinterMod.kt                     # Common mod entrypoint
│   │   │   ├── model/
│   │   │   │   └── PlacementTarget.kt            # Decoupled JVM-testable block target
│   │   │   ├── planner/
│   │   │   │   ├── BuildOrderPlanner.kt          # Ascending Y, gravity & crosshair sorter
│   │   │   │   └── CarpetProtocol.kt             # Carpet v2/v3 hit vector encoding
│   │   │   └── util/
│   │   │       └── VecMath.kt                    # Pitch/yaw calculations & angle wrapping
│   │   └── resources/
│   │       ├── assets/litematica_printer/icon.png
│   │       └── fabric.mod.json
│   ├── client/
│   │   ├── java/kyrielie/printer/client/mixin/
│   │   │   ├── ClientCommonPacketListenerImplMixin.java  # Outgoing packet interceptor
│   │   │   ├── ClientPacketListenerMixin.java            # Incoming ACK/update interceptor
│   │   │   └── LocalPlayerMixin.java                     # Primary move packet look override
│   │   ├── kotlin/kyrielie/printer/client/
│   │   │   ├── PrinterClient.kt                  # ClientModInitializer & keybind registration
│   │   │   ├── bridge/
│   │   │   │   └── LitematicaBridge.kt           # Dynamic reflection hook into Litematica
│   │   │   ├── config/
│   │   │   │   ├── PrinterConfig.kt              # Gson disk configuration model
│   │   │   │   ├── PrinterYaclConfig.kt          # YetAnotherConfigLib GUI screen
│   │   │   │   └── PrinterModMenuIntegration.kt  # ModMenu screen provider
│   │   │   ├── guides/
│   │   │   │   ├── PlacementGuideEngine.kt       # Evaluation engine for placement & tuning
│   │   │   │   └── ScaffoldHelper.kt             # Autonomous temporary scaffolding solver
│   │   │   ├── gui/
│   │   │   │   └── PrinterHud.kt                 # In-game telemetry HUD overlay
│   │   │   ├── player/
│   │   │   │   ├── FaceRaycaster.kt              # Multi-point UV raycaster & face solver
│   │   │   │   ├── InventoryRestockManager.kt    # Automatic container slot replenisher
│   │   │   │   ├── PlacementCache.kt             # In-flight cooldown cache
│   │   │   │   ├── PrinterController.kt          # Tick loop & placement orchestrator
│   │   │   │   └── ProtocolMovementGuard.kt      # Movement packet safety tracker
│   │   │   └── telemetry/
│   │   │       └── ServerTelemetryTracker.kt     # Real-time packet response & latency tracker
│   │   └── resources/
│   │       └── litematica_printer.client.mixins.json
│   └── test/
│       └── kotlin/kyrielie/printer/
│           └── PrinterCoreTest.kt                # Pure JVM JUnit 5 test suite (100% coverage)
```

---

## Building from Source

### Prerequisites
- **Java 25 JDK** (e.g. Eclipse Adoptium Temurin 25)
- Git

### Build Commands

```bash
# Clone repository
git clone https://github.com/kyrielie/fn-litematica-printer.git
cd fn-litematica-printer

# Run pure JVM unit tests (no Minecraft bootstrap needed)
./gradlew test --no-daemon

# Compile release JAR
./gradlew build --no-daemon
```

The compiled mod JAR is placed in `build/libs/fn-litematica-printer-1.0.0+26.3.jar`.

---

## License

This project is licensed under the [MIT License](LICENSE).
