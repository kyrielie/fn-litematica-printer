package kyrielie.printer.client.player

import kyrielie.printer.client.bridge.LitematicaBridge
import kyrielie.printer.client.config.ConfigManager
import kyrielie.printer.client.guides.PlacementAction
import kyrielie.printer.client.guides.PlacementGuideEngine
import kyrielie.printer.model.PlacementTarget
import kyrielie.printer.planner.BuildOrderPlanner
import kyrielie.printer.util.deltaToYawPitch
import kyrielie.printer.util.wrapDegrees
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

data class LastPlacementRecord(
    val pos: BlockPos,
    val face: Direction,
    val placeAgainst: BlockPos,
    val playerYaw: Float,
    val playerPitch: Float,
    val aimYaw: Float,
    val aimPitch: Float,
    val yawDelta: Float,
    val pitchDelta: Float,
    val angularDelta: Float,
    val dist: Double,
    val timestamp: Long
)

object PrinterController {

    val logger = LoggerFactory.getLogger("Printer/Controller")

    var isRunning: Boolean = false
        private set

    var totalPlaced: Int = 0
        private set

    var lastPlacementsPerSec: Int = 0
        private set

    private var placementCounterThisSec = 0
    private var lastSecondTimestamp = System.currentTimeMillis()
    private var lastDebugLogTimestamp = 0L
    private var lastLevel: Level? = null

    // Server rollback & telemetry tracking
    private var lastTrackedSlot = -1
    private var lastExpectedItemCount = -1
    private var rollbackCooldownTicks = 0
    private var lastPlacementRecord: LastPlacementRecord? = null

    fun toggle() {
        isRunning = !isRunning
        if (!isRunning) {
            PlacementCache.clear()
            lastTrackedSlot = -1
            lastExpectedItemCount = -1
            rollbackCooldownTicks = 0
            lastPlacementRecord = null
        }
    }

    /**
     * Primary tick-driven controller: executed on ClientTickEvents.END_CLIENT_TICK.
     */
    fun onClientTick(mc: Minecraft) {
        val player = mc.player ?: return
        val world = mc.level ?: return
        val gameMode = mc.gameMode ?: return

        // Level change detection clears cached placements
        if (world !== lastLevel) {
            lastLevel = world
            PlacementCache.clear()
            lastTrackedSlot = -1
            lastExpectedItemCount = -1
            lastPlacementRecord = null
        }

        // Clean expired placement cooldowns
        PlacementCache.cleanup()

        // Update stats counter
        val now = System.currentTimeMillis()
        if (now - lastSecondTimestamp >= 1000L) {
            lastPlacementsPerSec = placementCounterThisSec
            placementCounterThisSec = 0
            lastSecondTimestamp = now
        }

        if (!isRunning || !ConfigManager.config.enabled) return

        // Monitor for server inventory rollbacks (item count unexpectedly jumping upward)
        val currentSlot = player.inventory.selectedSlot
        val currentCount = player.inventory.getItem(currentSlot).count
        if (currentSlot == lastTrackedSlot && lastExpectedItemCount != -1 && currentCount > lastExpectedItemCount) {
            val rec = lastPlacementRecord
            val recDetails = if (rec != null) {
                "Last placement at ${rec.pos} (clicked ${rec.face} on ${rec.placeAgainst}) " +
                "had angularDelta=${"%.1f".format(rec.angularDelta)}° [yawDelta=${"%.1f".format(rec.yawDelta)}°, pitchDelta=${"%.1f".format(rec.pitchDelta)}°], " +
                "playerLook=(${"%.1f".format(rec.playerYaw)}, ${"%.1f".format(rec.playerPitch)}), " +
                "aimNeeded=(${"%.1f".format(rec.aimYaw)}, ${"%.1f".format(rec.aimPitch)}), " +
                "dist=${"%.2f".format(rec.dist)}, occurred ${now - rec.timestamp}ms ago."
            } else {
                "No previous placement recorded."
            }

            logger.warn(
                "[Printer ROLLBACK] Server rejected placement! Slot $currentSlot count jumped $lastExpectedItemCount -> $currentCount. $recDetails"
            )
            if (rec != null) {
                PlacementCache.markPlaced(rec.pos, 2000L)
            }
            rollbackCooldownTicks = 2
        }
        lastTrackedSlot = currentSlot
        lastExpectedItemCount = currentCount

        if (rollbackCooldownTicks > 0) {
            rollbackCooldownTicks--
            return
        }

        val shouldLog = (now - lastDebugLogTimestamp >= 1000L)
        if (shouldLog) lastDebugLogTimestamp = now

        // 1. Moving player eye compensation
        val velocity = player.deltaMovement
        val eyeHeight = player.getEyeHeight()
        val eyePos = Vec3(
            player.x + (velocity.x * 0.5),
            player.y + eyeHeight.toDouble() + (velocity.y * 0.5),
            player.z + (velocity.z * 0.5)
        )

        // 2. Query schematic world
        val schematicWorld = LitematicaBridge.getSchematicWorld()
        if (schematicWorld == null) {
            if (shouldLog) {
                logger.warn("[Printer] Schematic world is NULL. Is a schematic placement loaded in Litematica?")
            }
            return
        }

        val reach = ConfigManager.config.survivalReachLimit
        val reachCeil = ceil(reach).toInt()

        val pPos = player.blockPosition()
        val rawCandidates = ArrayList<PlacementTarget>()
        var skippedDistance = 0
        var skippedLayer = 0
        var skippedSameOrAir = 0
        var skippedCooldown = 0

        for (dx in -reachCeil..reachCeil) {
            for (dy in -reachCeil..reachCeil) {
                for (dz in -reachCeil..reachCeil) {
                    val pos = BlockPos(pPos.x + dx, pPos.y + dy, pPos.z + dz)

                    // Skip positions inside player collision body
                    if (pos.distToCenterSqr(player.x, player.y, player.z) <= 0.8) continue

                    // Must be within sphere reach of live eye
                    if (pos.distToCenterSqr(eyePos.x, eyePos.y, eyePos.z) > (reach * reach)) {
                        skippedDistance++
                        continue
                    }

                    // Obey Litematica render layer selection
                    if (!LitematicaBridge.isPositionWithinRenderLayers(pos)) {
                        skippedLayer++
                        continue
                    }

                    val schemState = schematicWorld.getBlockState(pos)
                    val worldState = world.getBlockState(pos)
                    if (schemState.isAir || schemState == worldState) {
                        skippedSameOrAir++
                        continue
                    }

                    // Avoid duplicate placement spam while server confirms in-flight packet
                    if (PlacementCache.isCached(pos)) {
                        skippedCooldown++
                        continue
                    }

                    rawCandidates.add(
                        PlacementTarget(
                            pos.x, pos.y, pos.z,
                            schemState.block.toString()
                        )
                    )
                }
            }
        }

        if (rawCandidates.isEmpty()) {
            if (shouldLog && skippedCooldown > 0) {
                logger.debug("[Printer] All in-reach candidates ($skippedCooldown) waiting on server placement cooldown.")
            }
            return
        }

        if (shouldLog) {
            logger.info("[Printer] Found ${rawCandidates.size} candidate blocks to place around $pPos (cooling down: $skippedCooldown).")
        }

        val crosshairHit = mc.hitResult as? BlockHitResult
        val crosshairBlock = crosshairHit?.blockPos

        // 3. Sort candidates using BuildOrderPlanner (direct crosshair match first, then bottom-up ascending Y, nearest to crosshair)
        val sorted = BuildOrderPlanner.sortCandidates(
            rawCandidates,
            player.x,
            player.y,
            player.z,
            player.yRot,
            player.xRot,
            eyePos.y,
            crosshairBlock?.x,
            crosshairBlock?.y,
            crosshairBlock?.z
        )
        var budget = ConfigManager.config.blocksPerTick

        for (candidate in sorted) {
            if (budget <= 0) break

            val targetPos = BlockPos(candidate.x, candidate.y, candidate.z)
            if (PlacementCache.isCached(targetPos)) continue

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
                maxReach = reach,
                debug = shouldLog
            ) ?: continue

            when (action) {
                is PlacementAction.PlaceBlock -> {
                    // Compute telemetry: angles, delta, and crosshair state
                    val aimDelta = action.aimVec.subtract(eyePos)
                    val (aimYaw, aimPitch) = deltaToYawPitch(aimDelta.x, aimDelta.y, aimDelta.z)
                    val yawDiff = abs(wrapDegrees(aimYaw - player.yRot))
                    val pitchDiff = abs(aimPitch - player.xRot)
                    val angularDelta = sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff)
                    val dist = action.aimVec.distanceTo(eyePos)

                    val isDirectCrosshair = crosshairBlock != null &&
                        (crosshairBlock == action.hitResult.blockPos || crosshairBlock == targetPos)

                    // Calculate maximum allowable angle: bounded by block physical angular radius at distance
                    val blockAngularRadius = Math.toDegrees(kotlin.math.atan2(0.65, dist)).toFloat()
                    val allowedAngle = minOf(ConfigManager.config.maxAngleDelta.toFloat(), blockAngularRadius)

                    // Check angular limit: defer candidates outside current view cone
                    if (!isDirectCrosshair && angularDelta > allowedAngle) {
                        if (shouldLog) {
                            logger.debug(
                                "[Printer Angle] Deferring candidate $targetPos: angularDelta=${"%.1f".format(angularDelta)}° " +
                                "> allowed=${"%.1f".format(allowedAngle)}° (dist=${"%.2f".format(dist)}, crosshairOn=$crosshairBlock)"
                            )
                        }
                        continue
                    }

                    // Dispatch placement directly without modifying client camera view or injecting fake movement packets
                    val result = gameMode.useItemOn(player, action.hand, action.hitResult)

                    logger.info(
                        "[Printer Place] pos=$targetPos face=${action.hitResult.direction} on ${action.hitResult.blockPos} | " +
                        "dist=${"%.2f".format(dist)} delta=${"%.1f".format(angularDelta)}° (limit=${"%.1f".format(allowedAngle)}°) " +
                        "crosshairOn=$crosshairBlock directHit=$isDirectCrosshair -> Result=$result"
                    )

                    lastPlacementRecord = LastPlacementRecord(
                        pos = targetPos,
                        face = action.hitResult.direction,
                        placeAgainst = action.hitResult.blockPos,
                        playerYaw = player.yRot,
                        playerPitch = player.xRot,
                        aimYaw = aimYaw,
                        aimPitch = aimPitch,
                        yawDelta = yawDiff,
                        pitchDelta = pitchDiff,
                        angularDelta = angularDelta,
                        dist = dist,
                        timestamp = System.currentTimeMillis()
                    )

                    if (result.consumesAction()) {
                        PlacementCache.markPlaced(targetPos, PlacementCache.DEFAULT_TIMEOUT_MS)
                        player.swing(action.hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)
                        budget--
                        totalPlaced++
                        placementCounterThisSec++

                        // Track predicted item decrement
                        val activeSlot = player.inventory.selectedSlot
                        val activeCount = player.inventory.getItem(activeSlot).count
                        lastTrackedSlot = activeSlot
                        lastExpectedItemCount = activeCount
                    } else {
                        // Action did not consume item; back off briefly on this position
                        PlacementCache.markPlaced(targetPos, 400L)
                    }
                }
                is PlacementAction.MultiClick -> {
                    if (ConfigManager.config.enableMultiClick) {
                        val dist = action.hitResult.location.distanceTo(eyePos)
                        val aimDelta = action.hitResult.location.subtract(eyePos)
                        val (aimYaw, aimPitch) = deltaToYawPitch(aimDelta.x, aimDelta.y, aimDelta.z)
                        val yawDiff = abs(wrapDegrees(aimYaw - player.yRot))
                        val pitchDiff = abs(aimPitch - player.xRot)
                        val angularDelta = sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff)
                        val isDirectCrosshair = crosshairBlock != null && crosshairBlock == action.hitResult.blockPos
                        val blockAngularRadius = Math.toDegrees(kotlin.math.atan2(0.65, dist)).toFloat()
                        val allowedAngle = minOf(ConfigManager.config.maxAngleDelta.toFloat(), blockAngularRadius)

                        if (!isDirectCrosshair && angularDelta > allowedAngle) continue

                        for (i in 0 until action.clickCount) {
                            val result = gameMode.useItemOn(player, action.hand, action.hitResult)
                            if (result.consumesAction()) {
                                player.swing(action.hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)
                            }
                        }
                        PlacementCache.markPlaced(targetPos, PlacementCache.DEFAULT_TIMEOUT_MS)
                        budget--
                    }
                }
                is PlacementAction.ScaffoldSequence -> {
                    val sHit = BlockHitResult(
                        action.site.anchorFace.hitVec,
                        action.site.anchorFace.clickedFace,
                        action.site.anchorFace.placeAgainstPos,
                        false
                    )
                    gameMode.useItemOn(player, action.scaffoldHand, sHit)
                    player.swing(action.scaffoldHand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)

                    val tHit = BlockHitResult(
                        action.site.targetFaceFromScaffold.hitVec,
                        action.site.targetFaceFromScaffold.clickedFace,
                        action.site.targetFaceFromScaffold.placeAgainstPos,
                        false
                    )
                    gameMode.useItemOn(player, action.targetHand, tHit)
                    player.swing(action.targetHand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)

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

                    PlacementCache.markPlaced(action.targetPos, PlacementCache.DEFAULT_TIMEOUT_MS)
                    PlacementCache.markPlaced(action.site.scaffoldPos, PlacementCache.DEFAULT_TIMEOUT_MS)

                    budget--
                    totalPlaced++
                    placementCounterThisSec++
                }
            }
        }
    }
}
