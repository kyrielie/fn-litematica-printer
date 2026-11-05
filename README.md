# FN Litematica Printer

A next-generation Fabric client mod for Minecraft 26.3 that automates block placement from loaded Litematica schematics with anti-cheat protection, moving-player motion compensation, and autonomous scaffolding.

---

## Key Features

- **Dynamic Player Motion Compensation**: Forward-projects player eye position based on movement velocity to eliminate "out of reach" and "invalid face" kicks while running, walking, or flying.
- **Strict Contact Placement (No Air-Place)**: Multi-point UV raycasting (`findPlacementFace` on `ClipContext.Block.COLLIDER`) prevents placing against air or through solid blocks.
- **Autonomous 3-Phase Scaffolding**: Automatically detects floating or isolated blocks, places temporary support blocks (e.g. Dirt), anchors the target block against the scaffold, and mines the scaffold.
- **Multi-Click Progression**: Automatically tunes repeaters (delay 1–4), comparators (compare vs subtract), and note blocks (pitch 0–24) to match schematic states.
- **Carpet Protocol & Vanilla Dual-Mode**: Natively supports Carpet Extra `accurateBlockPlacement` v2 and v3 encoded hit vectors, while providing realistic pitch/yaw rotation emulation on vanilla servers.
- **Inventory Restock**: Dynamically pulls required items from the 27-slot main inventory into the hotbar using `ContainerInput.SWAP` (`handleContainerInput`), with native offhand placement support.
- **Protocol Movement Guard**: Enforces the Minecraft 26.2+ protocol requirement of $\le 1$ position packet per client tick, safely downgrading aim adjustments to look-only packets.
- **Clean Litematica Hook**: Integrates dynamically with live Litematica schematics and render-layer ranges via `LitematicaBridge`.
- **In-Game HUD & YACL GUI**: On-screen status and placement speed overlay, configurable via Yet Another Config Lib and ModMenu.

---

## Controls

| Key | Default Action |
| :---: | :--- |
| <kbd>V</kbd> | Toggle Printer ON / OFF |

*Keybinding can be rebound in **Options → Controls → Key Binds → Litematica Printer**.*

---

## Project Structure

```
fn-litematica-printer/
├── src/
│   ├── main/
│   │   ├── kotlin/kyrielie/printer/
│   │   │   ├── PrinterMod.kt                     # Common mod entrypoint
│   │   │   ├── model/
│   │   │   │   └── PlacementTarget.kt            # Decoupled placement model
│   │   │   ├── planner/
│   │   │   │   ├── BuildOrderPlanner.kt          # Bottom-up Y & dependency sorter
│   │   │   │   └── CarpetProtocol.kt             # Carpet v2/v3 hit vector encoding
│   │   │   └── util/
│   │   │       └── VecMath.kt                    # Pitch/yaw math & angular proximity sweep
│   │   └── resources/
│   │       ├── assets/litematica_printer/icon.png
│   │       └── fabric.mod.json
│   ├── client/
│   │   └── kotlin/kyrielie/printer/client/
│   │       ├── PrinterClient.kt                  # ClientModInitializer & keybinds
│   │       ├── bridge/
│   │       │   └── LitematicaBridge.kt           # Dynamic reflection hook into Litematica
│   │       ├── config/
│   │       │   ├── PrinterConfig.kt              # Gson disk configuration
│   │       │   ├── PrinterYaclConfig.kt          # YACL settings GUI
│   │       │   └── PrinterModMenuIntegration.kt  # ModMenu integration
│   │       ├── guides/
│   │       │   ├── ScaffoldHelper.kt             # Temporary scaffold site solver
│   │       │   └── PlacementGuideEngine.kt       # Evaluation engine for placement & multi-click
│   │       ├── gui/
│   │       │   └── PrinterHud.kt                 # In-game stats HUD overlay
│   │       └── player/
│   │           ├── FaceRaycaster.kt              # Multi-point UV raycasting & line-of-sight
│   │           ├── InventoryRestockManager.kt    # Hotbar replenishment via ContainerInput.SWAP
│   │           ├── PrinterController.kt          # Main tick loop & motion predictor
│   │           └── ProtocolMovementGuard.kt      # Single position packet enforcer
│   └── test/
│       └── kotlin/kyrielie/printer/
│           └── PrinterCoreTest.kt                # Pure JVM JUnit 5 test suite
```

---

## Building from Source

```bash
# Run unit tests
./gradlew test --no-daemon

# Build production JAR
./gradlew build --no-daemon
```

Built JARs will be generated in `build/libs/`.
