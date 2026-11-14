# Project Roadmap

This document outlines the planned development phases, feature milestones, and implementation tracks for the mod.

---

## 1. Milestone Overview

| Phase | Milestone Name | Description | Status | Target |
|---|---|---|---|---|
| **Phase 1** | `scaffold-core` | Modern Fabric 26.3 + Loom 1.17 + Java 25 template setup with split source sets, YACL config, and JUnit 5 | **DONE** | v1.0.0 |
| **Phase 2** | `feature-core` | Implementation of primary domain features and game listeners | **DONE** | v1.0.0 |
| **Phase 3** | `hud-ui` | In-game HUD, overlays, and GUI controls | **DONE** | v1.0.0 |
| **Phase 4** | `anticheat-guard` | Movement packet guard, rate-limiting, and rotation convergence gates | **DONE** | v1.0.0 |
| **Phase 5** | `release-polish` | Final testing, release build, and documentation completion | **DONE** | v1.0.0 |
| **Phase 6** | `platforming-foundations` | Below-player feet placement, forward bridging/platforming raycast, hold-to-print, and block switch placement cooldown | **DONE** | v2.0.0 |
| **Phase 7** | `support-scaffolding` | Underside/interior shell scaffolding, configurable scaffold blocks, and temporary support for gravity, rotational, and diagonal blocks | **NEEDS REFINEMENT** | v2.1.0 |
| **Phase 8** | `smart-interaction` | Two-stage placement pipeline: auto-stripping logs, copper waxing/scraping, waterlogging, and secondary item switching | **DONE** | v2.0.0 |
| **Phase 9** | `obstacle-clearing` | Obstacle block detection, auto tool selection (`ToolSet` + `itemSaver`), and mine-before-place | **DONE** | v2.0.0 |
| **Phase 10** | `circuit-safety` | Redstone topological dependency graph, update-safe observer sequencing, and falling blocks | **DONE** | v2.0.0 |
| **Phase 11** | `layer-sync` | Strict layer-by-layer synchronization (shulker restocking cut by decision) | **DONE** | v2.0.0 |

---

## 2. Feature Tracks & Technical Specifications

### Phase 6: Platforming Foundations, Trigger Controls & Switch Safety (`v1.1.0`)
*Local References: [`../modsource/baritone/`](~/Documents/Applications/minecraftprojects/modsource/baritone/) (`RayTraceUtils.java`, `aabbSideMultipliers`), [`../blockbard/`](~/Documents/Applications/minecraftprojects/blockbard/) (`ProtocolMovementGuard.kt`)*
- **Below-Player (Under Feet) Placement**:
  - Replace `distToCenterSqr <= 0.8` filter with `player.boundingBox.deflate(1e-4).intersects(AABB(pos))` in `PrinterController`.
  - Enables placing floor blocks, foundations, and bridges directly beneath the player's feet ($Y - 1$) without rejecting valid positions, while continuing to prevent placing blocks inside the player's legs or torso.
- **Forward Bridging & Platforming Raycast**:
  - Update `FaceRaycaster` to support edge-face interactions when extending walkways forward.
  - Relax backface dot-product checks (down to $-0.65$) for neighbor edge faces when eye position is above and behind the block boundary.
  - Sample along the top perimeter of the edge face ($V = 0.85$ to $0.95$), allowing valid interaction without false occlusion from the neighbor's top surface.
  - Forward-project eye position using player `deltaMovement` for continuous sprint-platforming.
- **Hold-to-Print Trigger Mode**:
  - Add configurable `KeyTriggerMode` option (`TOGGLE` vs `HOLD_TO_PRINT`).
  - In `HOLD_TO_PRINT` mode, printer evaluates candidate blocks and places only while the keybinding is actively pressed down (`toggleKey.isDown`).
- **Block Switch Placement Break (Anti-Desync Hotbar Switch Pause)**:
  - **Problem**: When changing target block types during rapid printing (e.g. switching between different materials across adjacent schematic positions), issuing a placement packet (`ServerboundUseItemOnPacket`) immediately or in the same tick as the slot switch (`ServerboundSetCarriedItemPacket`) causes client-server inventory race conditions. The server often processes the placement packet before registering the slot change, placing the previously equipped item in the new position, corrupting the build, and triggering "FastSwitch" anticheat flags.
  - **Switch Cooldown / Break Execution**:
    - Add configurable `blockSwitchCooldownMs` (or `blockSwitchDelayTicks`, default: 1–2 ticks / ~50–100ms) triggered whenever the required placement item differs from the player's currently equipped hotbar item.
    - When an item switch occurs, the printer dispatches the slot change, pauses placement execution for the duration of the break to allow server state synchronization, and only resumes placement once the break has elapsed.
    - Eliminates race-condition misplacements and satisfies GrimAC/Vulcan held-item change settling requirements.

### Phase 7: Shell Scaffolding & Support Structures (`v1.1.0`)
*Local References: [`../noteblockgenerator/reference/printermods/PlacementGuide.java`](~/Documents/Applications/minecraftprojects/noteblockgenerator/reference/printermods/PlacementGuide.java#L83-L90), [`../modsource/baritone/`](~/Documents/Applications/minecraftprojects/modsource/baritone/)*
- **Visible-Only Shell Schematics & Thin Non-Flat Layers**:
  - Address Litematica "export only visible blocks" / hollow shells where thin, curved/non-flat 1-block-thick layers float in mid-air with empty interior/underside.
  - Place scaffolding on the **non-visible bottom/interior side** where the schematic contains `AIR`.
- **Configurable Scaffolding Whitelist (`scaffoldBlockWhitelist`) & Strict Mode (`strictScaffoldWhitelist`)**:
  - Add user-selectable scaffolding item (`scaffoldBlockItem`) and customizable comma-separated whitelist (`scaffoldBlockWhitelist`, default: `minecraft:dirt, minecraft:scaffolding`).
  - Strict whitelist enforcement (`strictScaffoldWhitelist: true`) strictly refuses to deploy unwanted junk blocks (such as cobblestone) if whitelisted items are exhausted.
  - Strict schematic air isolation: scaffolding is strictly forbidden from occupying any coordinate where the schematic defines a block (`schemState.isAir == true`).
  - Axis orientation protection: vertical logs and pillars strictly require scaffolding directly below (`Direction.DOWN`) or above (`Direction.UP`), preventing horizontal side attachments that cause wrong-angle log rotations.
- **Interactive Block Shift-Click (Sneak) Placing**:
  - Right-clicking interactive blocks (noteblocks, chests, crafting tables, repeaters, comparators) dispatches `withShift(player, true)` via `ServerboundPlayerInputPacket` and `player.setShiftKeyDown(true)`.
  - Bypasses block interaction (tuning note blocks or opening GUIs), placing the held block cleanly.
- **Support Scaffolding Engine for Dependent Blocks**:
  - **Gravity Blocks (Falling Block Support)**:
    - Detect schematic blocks subject to gravity (`Sand`, `Gravel`, `Concrete Powder`, `Anvil`, `Pointed Dripstone`).
    - If the position directly below (`pos.down()`) is `AIR` or non-solid, gate placement and automatically place a temporary scaffolding block beneath before placing the gravity block on top, preventing the block from falling into the void or breaking.
  - **Rotational & Directional Block Alignment**:
    - For blocks whose placement orientation depends on clicking a specific face of an adjacent block (e.g., Observers, Pistons facing upward/sideways, Dispensers, Droppers, Hoppers facing into a block, Stairs, Logs), evaluate if the required anchor face exists in the world.
    - When the anchor block is missing, place a temporary scaffolding block on the required adjacent face to serve as the contact surface, click against it to guarantee the exact target orientation, and then optionally remove the scaffold.
  - **Diagonal & Mid-Air Floating Blocks**:
    - For isolated blocks or blocks positioned diagonally from the existing structure with no directly adjacent solid faces within raycast reach, dynamically place a temporary scaffolding stepping-stone/bridge block adjacent to the target site, place the schematic block, and clean up the scaffold.
- **Permanent Backing Support vs Cleanup (`removeScaffolding: false`)**:
  - Configurable `removeScaffolding` setting (defaults to `false` for permanent structural backing, `true` for survival cleanup).
  - When disabled, scaffold blocks remain permanently in the world as a hidden backing skeleton, eliminating mid-air digging delays, tool wear, and accidental breakage.
  - When enabled, queue scaffold positions for scheduled demolition once target block placement is verified by server telemetry.
- **Directional Face Preference (`scaffoldPreferredFace`)**:
  - Add `ScaffoldPreferredFace` enum:
    - `UNDERSIDE` (Default): Strictly prefers placing scaffolding underneath the target block (`DOWN`).
    - `INTERIOR_AUTO`: Automatically analyzes adjacent positions to find which side borders schematic `AIR` (the hidden side).
    - `TOP`: Places scaffolding above (useful for descending overhangs).
    - `ANY`: Fallback to any valid neighbor.
- **Schematic Collision Guard**:
  - Verify that candidate scaffold positions do not intersect any impending real schematic block: `schematicWorld.getBlockState(candidateScaffoldPos).isAir`.
- **Known Issue & Refinement Track: Scaffolding Decision Accuracy (False Positives & False Negatives)**:
  - *Observation*: Playtesting in v2.0.0 identified that the heuristic deciding when scaffolding is genuinely needed exhibits both false positives (triggering scaffold placement when an alternative valid placement face or reachable vantage point exists) and false negatives (failing to recognize when complex diagonal geometry or thin floating layers genuinely require temporary support, or placing scaffolds in positions that are immediately occluded).
  - *Target Milestone: v2.1.0*:
    1. **Topological Exhaustion Gate**: Ensure scaffolding evaluation only triggers after full multi-angle raycasting across all legal neighbor positions has completely failed.
    2. **Line-of-Sight & Reachability Verification**: Before selecting a candidate scaffold position, simulate whether the subsequent placement against the scaffold face will actually succeed from the player's current and projected vantage points, preventing dead-end scaffolds.
    3. **Contextual Floating Shell Detection**: Distinguish between mid-air floating shells and reachable overhangs to eliminate premature scaffolding deployments.

### Phase 8: Smart Interactions & Block Transformations (`v1.2.0`)
*Local References: [`../noteblockgenerator/reference/printermods/PlacementGuide.java`](~/Documents/Applications/minecraftprojects/noteblockgenerator/reference/printermods/PlacementGuide.java#L162-L175), [`../noteblockgenerator/reference/printermods/Printer.java`](~/Documents/Applications/minecraftprojects/noteblockgenerator/reference/printermods/Printer.java#L810-L827)*
- **Two-Stage Placement & Secondary Tool Transformation Pipeline**:
  - Establish a formal two-stage lifecycle for blocks that require in-place modification or secondary item interaction:
    1. **Stage 1 (Base Block Placement)**: Detect that a schematic block requires in-place modification (stripped log, waterlogged block, waxed copper, tilled soil). Equip and place the valid base block into the world (e.g., raw log, unwaxed copper, waterloggable block like stairs/slabs/trapdoors/leaves/chests).
    2. **Stage 2 (Item Switch & Verification Pause)**: Await placement confirmation in the client world via `ServerTelemetryTracker` / block update. Switch the active hotbar item to the required secondary tool or item (Axe, Honeycomb, Water Bucket, Shovel, Hoe) while respecting the block switch break.
    3. **Stage 3 (Secondary Interaction)**: Dispatch the secondary interaction use packet (`useItemOn`) on the placed block to complete the stripping, waterlogging, waxing, or tilling.
- **Waterlogging Pipeline**:
  - Support blocks requiring `waterlogged=true` (stairs, slabs, fences, trapdoors, chests, leaves):
    - Place the base waterloggable block first.
    - Sequentially switch to a Water Bucket in the hotbar.
    - Right-click the placed block face to waterlog it in-place.
    - Track bucket state transitions (Water Bucket $\to$ Empty Bucket) and restock water buckets as needed.
- **Auto-Stripping Logs**:
  - When the schematic calls for a stripped log/wood variant (e.g. `stripped_oak_log`) but the player only possesses the raw log (`oak_log`) alongside an axe:
    1. Place the unstripped log normally.
    2. Sequentially switch to the axe and send an interaction use packet on the placed log face to strip it in-place.
- **Copper Waxing & Scraping**:
  - Automatically apply honeycomb to wax copper blocks matching schematic waxed states.
  - Automatically scrape wax/oxidation using an axe if placed blocks deviate from schematic oxidation stages.
- **Soil & Flora Transformations**:
  - Right-click dirt with shovels to create `dirt_path`, or with hoes to create `farmland`.
  - Multi-state tuning: Repeaters, comparators, and note block pitch tuning in exact semitone steps.

### Phase 9: Surgical Obstacle Clearing & Tool Safety (`v1.2.0`)
*Local References: [`../modsource/baritone/`](~/Documents/Applications/minecraftprojects/modsource/baritone/) (`ToolSet.java`), [`../noteblockgenerator/reference/printermods/Printer.java`](~/Documents/Applications/minecraftprojects/noteblockgenerator/reference/printermods/Printer.java#L424-L428)*
- **Natural Obstruction Clearing**:
  - Automatically clears soft foliage, grass, vines, and snow layers obstructing placement sites.
  - Never auto-digs structural foundations, claimed blocks, or blocks beneath the player's feet.
- **Auto-Tool Switching (`MiningEngine` & `ToolSet`)**:
  - Inspect hotbar for tools capable of harvesting or efficiently breaking the obstacle.
  - Implement Baritone's `ToolSet` algorithm: pick highest mining speed with `itemSaverThreshold` protection to prevent breaking high-value diamond/netherite tools.
- **Mine-then-Place Lifecycle**:
  - Dispatch start/stop break packets with vanilla break delays.
  - Gate placement until `ServerTelemetryTracker` confirms the position transitioned to `AIR`.

### Phase 10: Redstone Contextual Safety Gates (`v1.3.0`)
*Local References: [`../noteblockgenerator/reference/printermods/Printer.java`](~/Documents/Applications/minecraftprojects/noteblockgenerator/reference/printermods/Printer.java#L1500-L1850)*
- **Contextual Redstone Safety Gates**:
  - Structure safety gates based on `Printer.java`:
    1. **Quasi-Connectivity (BUD) Prevention (`isQCable`)**: Prevent placing redstone blocks diagonally/vertically above unbuilt pistons.
    2. **Observer Misfire Avoidance (`checkObserverOutputs`)**: Prevent placing observers if the 1-tick placement pulse would misfire adjacent unbuilt pistons or note blocks.
    3. **Dust Redirection Protection (`isCorrectDustState`)**: Guard against breaking existing wire shapes when placing neighbor blocks.
- **Pure Sorter Boundary (`src/main`)**:
  - Maintain fast headless unit tests with pure ascending-Y and solid-before-attached ordering in `BuildOrderPlanner`.
  - Execute live world contextual safety evaluations on the client side right before packet dispatch.

### Phase 11: Layer Synchronization (`v1.4.0`)
*Local References: [`../blockbard/`](~/Documents/Applications/minecraftprojects/blockbard/), [`../noteblockgenerator/reference/printermods/Printer.java`](~/Documents/Applications/minecraftprojects/noteblockgenerator/reference/printermods/Printer.java#L540-L541)*
- **Strict Layer-by-Layer Synchronization**:
  - Controlled by `strictLayerSync` configuration flag.
  - When enabled, `PrinterController.planNextAction` gates candidate generation to the lowest active Y level (`minY`), completely halting progression to layer $Y + 1$ until all reachable blocks on layer $Y$ are placed.
  - Integrates with Litematica's active layer range mode (Single Layer, All Below, All Above).
  - *Note*: Shulker box autonomous restocking has been cut from scope by design decision to prioritize surgical reliability without risk of movement interruption or container desync.
