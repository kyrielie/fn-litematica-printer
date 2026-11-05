package kyrielie.printer.client.player

import kyrielie.printer.client.bridge.LitematicaBridge
import kyrielie.printer.client.config.ConfigManager
import kyrielie.printer.client.guides.PlacementAction
import kyrielie.printer.client.guides.PlacementGuideEngine
import kyrielie.printer.model.PlacementTarget
import kyrielie.printer.planner.BuildOrderPlanner
import kyrielie.printer.util.deltaToYawPitch
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import kotlin.math.ceil

object PrinterController {

    private val logger = LoggerFactory.getLogger("Printer/PrinterController")

    var isRunning: Boolean = false
        private set

    var totalPlaced: Int = 0
        private set

    var lastPlacementsPerSec: Int = 0
        private set

    private var placementCounterThisSec = 0
    private var lastSecondTimestamp = System.currentTimeMillis()

    fun toggle() {
        isRunning = !isRunning
        logger.info("Printer toggled: $isRunning")
    }

    /**
     * Primary tick-driven controller: executed on ClientTickEvents.END_CLIENT_TICK.
     */
    fun onClientTick(mc: Minecraft) {
        val player = mc.player ?: return
        val world = mc.level ?: return
        val gameMode = mc.gameMode ?: return

        // Update stats counter
        val now = System.currentTimeMillis()
        if (now - lastSecondTimestamp >= 1000L) {
            lastPlacementsPerSec = placementCounterThisSec
            placementCounterThisSec = 0
            lastSecondTimestamp = now
        }

        if (!isRunning || !ConfigManager.config.enabled) return

        // 1. Moving player eye compensation
        // Forward project eye by small tick fraction based on player velocity
        val velocity = player.deltaMovement
        val eyeHeight = player.getEyeHeight()
        val eyePos = Vec3(
            player.x + (velocity.x * 0.5),
            player.y + eyeHeight.toDouble() + (velocity.y * 0.5),
            player.z + (velocity.z * 0.5)
        )

        // 2. Query candidate positions
        val schematicWorld = LitematicaBridge.getSchematicWorld() ?: return
        val reach = ConfigManager.config.survivalReachLimit
        val reachCeil = ceil(reach).toInt()

        val pPos = player.blockPosition()
        val rawCandidates = ArrayList<PlacementTarget>()

        for (dx in -reachCeil..reachCeil) {
            for (dy in -reachCeil..reachCeil) {
                for (dz in -reachCeil..reachCeil) {
                    val pos = BlockPos(pPos.x + dx, pPos.y + dy, pPos.z + dz)

                    // Skip positions inside player collision body (avoids getting stuck)
                    if (pos.distToCenterSqr(player.x, player.y, player.z) <= 0.8) continue

                    // Must be within sphere reach of live eye
                    if (pos.distToCenterSqr(eyePos.x, eyePos.y, eyePos.z) > (reach * reach)) continue

                    // Obey Litematica render layer selection
                    if (!LitematicaBridge.isPositionWithinRenderLayers(pos)) continue

                    val schemState = schematicWorld.getBlockState(pos)
                    val worldState = world.getBlockState(pos)
                    if (schemState.isAir) continue
                    if (schemState == worldState) continue

                    rawCandidates.add(
                        PlacementTarget(
                            pos.x, pos.y, pos.z,
                            schemState.block.toString()
                        )
                    )
                }
            }
        }

        if (rawCandidates.isEmpty()) return

        // 3. Sort candidates using BuildOrderPlanner (bottom-up ascending Y, nearest horizontally)
        val sorted = BuildOrderPlanner.sortCandidates(rawCandidates, player.x, player.y, player.z)

        var budget = ConfigManager.config.blocksPerTick

        for (candidate in sorted) {
            if (budget <= 0) break

            val targetPos = BlockPos(candidate.x, candidate.y, candidate.z)
            val schemState = schematicWorld.getBlockState(targetPos)

            val action = PlacementGuideEngine.evaluate(
                mc,
                player,
                world,
                eyePos,
                targetPos,
                schemState,
                useCarpetProtocol = ConfigManager.config.carpetProtocol,
                enableScaffolding = ConfigManager.config.enableScaffolding,
                maxReach = reach
            ) ?: continue

            when (action) {
                is PlacementAction.PlaceBlock -> {
                    // Send look packet to face
                    val hitVec = action.hitResult.location
                    val delta = hitVec.subtract(eyePos)
                    val (yaw, pitch) = deltaToYawPitch(delta.x, delta.y, delta.z)
                    ProtocolMovementGuard.sendLookUpdate(player.connection.connection, yaw, pitch)

                    // Dispatch placement
                    val result = gameMode.useItemOn(player, action.hand, action.hitResult)
                    if (result.consumesAction()) {
                        player.swing(action.hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)
                        budget--
                        totalPlaced++
                        placementCounterThisSec++
                    }
                }
                is PlacementAction.MultiClick -> {
                    if (ConfigManager.config.enableMultiClick) {
                        for (i in 0 until action.clickCount) {
                            val result = gameMode.useItemOn(player, action.hand, action.hitResult)
                            if (result.consumesAction()) {
                                player.swing(action.hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)
                            }
                        }
                        budget--
                    }
                }
                is PlacementAction.ScaffoldSequence -> {
                    // Phase 1: Place scaffold
                    val sHit = BlockHitResult(
                        action.site.anchorFace.hitVec,
                        action.site.anchorFace.clickedFace,
                        action.site.anchorFace.placeAgainstPos,
                        false
                    )
                    gameMode.useItemOn(player, action.scaffoldHand, sHit)
                    player.swing(action.scaffoldHand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)

                    // Phase 2: Place target
                    val tHit = BlockHitResult(
                        action.site.targetFaceFromScaffold.hitVec,
                        action.site.targetFaceFromScaffold.clickedFace,
                        action.site.targetFaceFromScaffold.placeAgainstPos,
                        false
                    )
                    gameMode.useItemOn(player, action.targetHand, tHit)
                    player.swing(action.targetHand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)

                    // Phase 3: Mine scaffold
                    player.connection.send(
                        ServerboundPlayerActionPacket(
                            ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                            action.site.scaffoldPos,
                            Direction.UP
                        )
                    )
                    player.connection.send(
                        ServerboundPlayerActionPacket(
                            ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                            action.site.scaffoldPos,
                            Direction.UP
                        )
                    )

                    budget--
                    totalPlaced++
                    placementCounterThisSec++
                }
            }
        }
    }
}
