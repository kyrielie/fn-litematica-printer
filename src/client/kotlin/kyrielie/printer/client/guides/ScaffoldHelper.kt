package kyrielie.printer.client.guides

import kyrielie.printer.client.bridge.LitematicaBridge
import kyrielie.printer.client.config.ConfigManager
import kyrielie.printer.client.config.ScaffoldPreferredFace
import kyrielie.printer.client.player.FaceRaycaster
import kyrielie.printer.client.player.FaceRaycastResult
import kyrielie.printer.client.player.InventoryRestockManager
import kyrielie.printer.client.player.VisiblePlacementFace
import kyrielie.printer.planner.BuildOrderPlanner
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Result of scaffold site evaluation.
 *
 * [scaffoldPos]: The temporary position where scaffold (e.g. Dirt) will be placed.
 * [anchorFace]: The visible face of an existing world block to place the scaffold against.
 * [targetFaceFromScaffold]: The face on the scaffold to click on when placing the target block.
 */
data class ScaffoldSite(
    val scaffoldPos: BlockPos,
    val anchorFace: VisiblePlacementFace,
    val targetFaceFromScaffold: VisiblePlacementFace
)

object ScaffoldHelper {

    private val JUNK_BLOCK_FALLBACKS = listOf(
        "minecraft:dirt",
        "minecraft:cobblestone",
        "minecraft:cobbled_deepslate",
        "minecraft:stone",
        "minecraft:netherrack",
        "minecraft:scaffolding",
        "minecraft:oak_planks",
        "minecraft:spruce_planks",
        "minecraft:birch_planks",
        "minecraft:andesite",
        "minecraft:diorite",
        "minecraft:granite",
        "minecraft:sandstone",
        "minecraft:tuff"
    )

    /**
     * Resolves the active scaffolding block from user configuration with dynamic
     * fallback to available inventory junk blocks (Phase 7).
     */
    fun resolveScaffoldItem(player: LocalPlayer): Item? {
        // 1. Primary configured scaffold item
        val configuredName = ConfigManager.config.scaffoldBlockItem
        val configuredId = Identifier.tryParse(configuredName)
        if (configuredId != null) {
            val configuredItem = BuiltInRegistries.ITEM.get(configuredId).map { it.value() }.orElse(null)
            if (configuredItem != null && InventoryRestockManager.hasItem(player, configuredItem)) {
                return configuredItem
            }
        }

        // 2. User configured whitelist items
        val whitelistItems = ConfigManager.config.scaffoldBlockWhitelist
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (whitelistName in whitelistItems) {
            val id = Identifier.tryParse(whitelistName) ?: continue
            val item = BuiltInRegistries.ITEM.get(id).map { it.value() }.orElse(null) ?: continue
            if (InventoryRestockManager.hasItem(player, item)) {
                return item
            }
        }

        // If strict whitelist is enabled, DO NOT use any unapproved junk blocks (e.g. cobblestone)
        if (ConfigManager.config.strictScaffoldWhitelist) {
            return null
        }

        for (fallbackName in JUNK_BLOCK_FALLBACKS) {
            val id = Identifier.tryParse(fallbackName) ?: continue
            val item = BuiltInRegistries.ITEM.get(id).map { it.value() }.orElse(null) ?: continue
            if (InventoryRestockManager.hasItem(player, item)) {
                return item
            }
        }

        // Dynamic inventory scan fallback: any full solid block item in inventory not required by schematic
        val inventory = player.inventory
        for (slot in 0..35) {
            val stack = inventory.getItem(slot)
            if (stack.isEmpty || stack.count <= 0) continue
            val item = stack.item
            if (item is net.minecraft.world.item.BlockItem) {
                val block = item.block
                val blockId = BuiltInRegistries.BLOCK.getKey(block).toString()
                if (!BuildOrderPlanner.isGravityBlock(blockId) &&
                    !BuildOrderPlanner.isAttachedBlock(blockId) &&
                    !FaceRaycaster.isInteractiveBlock(block.defaultBlockState())) {
                    return item
                }
            }
        }

        return null
    }

    /**
     * Searches adjacent cardinal neighbors of [targetPos] for a location where a temporary
     * block can be legally placed against existing solid geometry (Phase 7).
     * Honors gravity block requirements, directional preferences, and schematic collision guards.
     */
    fun findScaffoldSite(
        world: Level,
        player: Player,
        eyePos: Vec3,
        targetPos: BlockPos,
        schematicState: BlockState? = null,
        maxReach: Double = 4.5,
        allowDecoupledBridge: Boolean = true
    ): ScaffoldSite? {
        val schemWorld = LitematicaBridge.getSchematicWorld()

        // Phase 7: Order candidate directions according to block gravity and user preference
        val blockId = schematicState?.let { BuiltInRegistries.BLOCK.getKey(it.block).toString() } ?: ""
        val isGravity = BuildOrderPlanner.isGravityBlock(blockId)

        val dirs = if (isGravity) {
            // Gravity blocks strictly require solid support directly beneath (pos.down())
            listOf(Direction.DOWN)
        } else if (schematicState != null && schematicState.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)) {
            // Vanilla log/pillar orientation: placing against face F sets pillar axis to F.axis
            // To produce reqAxis, the clicked contact face (dir.opposite) must have axis == reqAxis!
            val reqAxis = schematicState.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)
            when (reqAxis) {
                Direction.Axis.Y -> listOf(Direction.DOWN, Direction.UP)
                Direction.Axis.X -> listOf(Direction.WEST, Direction.EAST)
                Direction.Axis.Z -> listOf(Direction.NORTH, Direction.SOUTH)
            }
        } else {
            when (ConfigManager.config.scaffoldPreferredFace) {
                ScaffoldPreferredFace.UNDERSIDE -> listOf(Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP)
                ScaffoldPreferredFace.TOP -> listOf(Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN)
                ScaffoldPreferredFace.INTERIOR_AUTO -> {
                    Direction.entries.sortedByDescending { dir ->
                        val checkPos = targetPos.relative(dir)
                        if (schemWorld != null) {
                            Direction.entries.count { schemWorld.getBlockState(checkPos.relative(it)).isAir }
                        } else 0
                    }
                }
                ScaffoldPreferredFace.ANY -> Direction.entries.toList()
            }
        }

        for (dir in dirs) {
            val candidateScaffoldPos = targetPos.relative(dir)

            // 1. Scaffold position must be empty/air in the real world
            val currentScaffoldState = world.getBlockState(candidateScaffoldPos)
            if (!currentScaffoldState.isAir && !currentScaffoldState.canBeReplaced()) continue

            // 2. Schematic collision guard: Strictly only allowed to place scaffolding where air would be in schematic
            if (schemWorld != null) {
                val schemState = schemWorld.getBlockState(candidateScaffoldPos)
                if (!schemState.isAir) continue
            }

            // 3. Must NOT collide with player deflated bounding box
            if (player.boundingBox.deflate(1e-4).intersects(AABB(candidateScaffoldPos))) continue

            // 4. Can the scaffold block be placed against existing solid geometry (NOT targetPos)?
            val anchorResult = FaceRaycaster.findPlacementFaceWithDiagnostics(
                world,
                player,
                eyePos,
                candidateScaffoldPos,
                maxReach,
                allowDecoupledBridge = allowDecoupledBridge
            )
            val anchorFace = (anchorResult as? FaceRaycastResult.Success)?.face ?: continue

            if (anchorFace.placeAgainstPos == targetPos) continue // Cannot place scaffold against nonexistent target

            // 5. Does the scaffold provide a legal contact face toward targetPos?
            val faceToTarget = dir.opposite
            if (schematicState != null) {
                if (schematicState.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)) {
                    val reqAxis = schematicState.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)
                    if (faceToTarget.axis != reqAxis) continue
                }
                if (schematicState.block is net.minecraft.world.level.block.DiodeBlock ||
                    schematicState.block is net.minecraft.world.level.block.RedstoneWireBlock ||
                    schematicState.block is net.minecraft.world.level.block.PressurePlateBlock ||
                    schematicState.block is net.minecraft.world.level.block.RailBlock) {
                    if (faceToTarget != Direction.UP) continue
                }
            }
            val hitVecOnScaffold = when (faceToTarget) {
                Direction.UP    -> Vec3(candidateScaffoldPos.x + 0.5, candidateScaffoldPos.y + 1.0, candidateScaffoldPos.z + 0.5)
                Direction.DOWN  -> Vec3(candidateScaffoldPos.x + 0.5, candidateScaffoldPos.y.toDouble(), candidateScaffoldPos.z + 0.5)
                Direction.NORTH -> Vec3(candidateScaffoldPos.x + 0.5, candidateScaffoldPos.y + 0.5, candidateScaffoldPos.z.toDouble())
                Direction.SOUTH -> Vec3(candidateScaffoldPos.x + 0.5, candidateScaffoldPos.y + 0.5, candidateScaffoldPos.z + 1.0)
                Direction.WEST  -> Vec3(candidateScaffoldPos.x.toDouble(), candidateScaffoldPos.y + 0.5, candidateScaffoldPos.z + 0.5)
                Direction.EAST  -> Vec3(candidateScaffoldPos.x + 1.0, candidateScaffoldPos.y + 0.5, candidateScaffoldPos.z + 0.5)
            }

            if (hitVecOnScaffold.distanceTo(eyePos) > maxReach) continue

            val targetPlacement = VisiblePlacementFace(candidateScaffoldPos, faceToTarget, hitVecOnScaffold)
            return ScaffoldSite(candidateScaffoldPos, anchorFace, targetPlacement)
        }

        return null
    }
}
