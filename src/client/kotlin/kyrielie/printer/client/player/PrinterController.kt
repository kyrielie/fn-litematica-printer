package kyrielie.printer.client.player

import kyrielie.printer.client.bridge.LitematicaBridge
import kyrielie.printer.client.config.ConfigManager
import kyrielie.printer.client.config.PlacementMode
import kyrielie.printer.client.guides.PlacementAction
import kyrielie.printer.client.guides.PlacementGuideEngine
import kyrielie.printer.client.telemetry.ServerTelemetryTracker
import kyrielie.printer.model.PlacementTarget
import kyrielie.printer.planner.BuildOrderPlanner
import kyrielie.printer.util.deltaToYawPitch
import kyrielie.printer.util.wrapDegrees
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
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

    data class LookOverride(val yaw: Float, val pitch: Float)

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
    private var lastOutOfItemsLogTimestamp = 0L
    private var lastLevel: Level? = null

    // Server rollback & telemetry tracking
    private var lastTrackedSlot = -1
    private var lastExpectedItemCount = -1
    private var rollbackCooldownTicks = 0
    private var lastPlacementRecord: LastPlacementRecord? = null

    // State for Decoupled Packet Mode (Approach C)
    private var pendingAction: PlacementAction? = null
    private var pendingOverride: LookOverride? = null

    fun toggle() {
        isRunning = !isRunning
        if (!isRunning) {
            PlacementCache.clear()
            lastTrackedSlot = -1
            lastExpectedItemCount = -1
            rollbackCooldownTicks = 0
            lastPlacementRecord = null
            pendingAction = null
            pendingOverride = null
        }
    }

    fun setActive(active: Boolean) {
        if (isRunning != active) {
            toggle()
        }
    }

    /**
     * Suppresses modal SignEditScreen popups (G-12) when signs are placed by the printer.
     */
    fun isSignEditorSuppressed(pos: BlockPos): Boolean {
        if (!isRunning) return false
        return PlacementCache.isCached(pos)
    }

    /**
     * Unified operational safety guard (G-2 + Claude review).
     * Prevents placement while typing in chat, menus, screens, or in non-controllable states.
     */
    fun canOperate(mc: Minecraft, player: LocalPlayer): Boolean {
        if (!isRunning || !ConfigManager.config.enabled) return false
        if (mc.gui.screen() != null) return false
        if (player.isSpectator) return false
        if (player.isUsingItem) return false
        if (player.isPassenger) return false
        if (!player.isAlive) return false
        if (mc.isPaused) return false
        if (rollbackCooldownTicks > 0) return false
        return true
    }

    /**
     * F-08 Failure isolation handler. Safely terminates printing and alerts the user
     * if an unexpected error occurs during packet injection.
     */
    fun handleFatalError(context: String, throwable: Throwable) {
        logger.error("Fatal error during $context; automatically disabling printer to prevent crash loop", throwable)
        isRunning = false
        pendingAction = null
        pendingOverride = null
        try {
            val mc = Minecraft.getInstance()
            mc.player?.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§c[Printer] Error in $context: ${throwable.message}. Printer auto-disabled.")
            )
        } catch (_: Throwable) {}
    }

    /**
     * Called at the HEAD of LocalPlayer.sendPosition() via LocalPlayerMixin.
     * Decouples client camera angle from outgoing server movement packet (Approach C).
     */
    @JvmStatic
    fun getActiveLookOverride(player: LocalPlayer): LookOverride? {
        val mc = Minecraft.getInstance()
        if (!canOperate(mc, player)) {
            pendingAction = null
            pendingOverride = null
            return null
        }
        if (ConfigManager.config.placementMode != PlacementMode.DECOUPLED_PACKET) return null

        if (pendingOverride != null) return pendingOverride

        val world = mc.level ?: return null

        val velocity = player.deltaMovement
        val eyeHeight = player.getEyeHeight()
        val eyePos = Vec3(
            player.x + (velocity.x * 0.5),
            player.y + eyeHeight.toDouble() + (velocity.y * 0.5),
            player.z + (velocity.z * 0.5)
        )

        val action = planNextAction(mc, player, world, eyePos) ?: return null
        val aimDelta = when (action) {
            is PlacementAction.PlaceBlock -> action.aimVec.subtract(eyePos)
            is PlacementAction.MultiClick -> action.aimVec.subtract(eyePos)
            is PlacementAction.TransformBlock -> action.aimVec.subtract(eyePos)
            is PlacementAction.ClearObstacle -> action.aimVec.subtract(eyePos)
            is PlacementAction.ScaffoldSequence -> action.site.anchorFace.hitVec.subtract(eyePos)
        }
        val (aimYaw, aimPitch) = deltaToYawPitch(aimDelta.x, aimDelta.y, aimDelta.z)
        pendingAction = action
        pendingOverride = LookOverride(aimYaw, aimPitch)
        return pendingOverride
    }

    /**
     * Called at the RETURN of LocalPlayer.sendPosition() via LocalPlayerMixin.
     * Executes the placement immediately after the movement packet bearing aimYaw/aimPitch has left.
     */
    @JvmStatic
    fun onAfterSendPosition(player: LocalPlayer) {
        val mc = Minecraft.getInstance()
        val world = mc.level ?: return
        val gameMode = mc.gameMode ?: return

        if (!canOperate(mc, player)) {
            pendingAction = null
            pendingOverride = null
            return
        }

        if (ConfigManager.config.placementMode == PlacementMode.DECOUPLED_PACKET) {
            val action = pendingAction
            val override = pendingOverride
            pendingAction = null
            pendingOverride = null

            if (action != null && override != null) {
                executeAction(mc, player, world, gameMode, action, override)

                // Secondary placement batching if budget allows and subsequent target shares similar orientation
                var budget = ConfigManager.config.blocksPerTick - 1
                val activeSlot = player.inventory.selectedSlot
                val activeCount = player.inventory.getItem(activeSlot).count
                if (activeCount <= 0) {
                    budget = 0
                }
                if (budget > 0) {
                    val schematicWorld = LitematicaBridge.getSchematicWorld()
                    val eyePos = Vec3(player.x, player.y + player.getEyeHeight(), player.z)
                    while (budget > 0) {
                        val secondaryAction = planNextAction(mc, player, world, eyePos) ?: break

                        // F-09 Safety Gate: Do not batch directional blocks under another block's look angle.
                        // Server-side facing is derived from the look packet; directional blocks must wait for their own tick.
                        if (secondaryAction is PlacementAction.PlaceBlock && schematicWorld != null) {
                            val schemState = schematicWorld.getBlockState(secondaryAction.targetPos)
                            val isDirectional = schemState.hasProperty(BlockStateProperties.FACING) ||
                                                schemState.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ||
                                                schemState.hasProperty(BlockStateProperties.AXIS) ||
                                                schemState.hasProperty(BlockStateProperties.SLAB_TYPE) ||
                                                schemState.hasProperty(BlockStateProperties.HALF)
                            if (isDirectional) {
                                break
                            }
                        }

                        val secDelta = when (secondaryAction) {
                            is PlacementAction.PlaceBlock -> secondaryAction.aimVec.subtract(eyePos)
                            is PlacementAction.MultiClick -> secondaryAction.aimVec.subtract(eyePos)
                            is PlacementAction.TransformBlock -> secondaryAction.aimVec.subtract(eyePos)
                            is PlacementAction.ClearObstacle -> secondaryAction.aimVec.subtract(eyePos)
                            is PlacementAction.ScaffoldSequence -> secondaryAction.site.anchorFace.hitVec.subtract(eyePos)
                        }
                        val (secYaw, secPitch) = deltaToYawPitch(secDelta.x, secDelta.y, secDelta.z)
                        val yawDiff = abs(wrapDegrees(secYaw - override.yaw))
                        val pitchDiff = abs(secPitch - override.pitch)
                        if (yawDiff <= 5.0f && pitchDiff <= 5.0f) {
                            executeAction(mc, player, world, gameMode, secondaryAction, override)
                            budget--
                            if (player.inventory.getItem(activeSlot).count <= 0) {
                                break
                            }
                        } else {
                            break
                        }
                    }
                }
            }
        }
    }

    /**
     * Primary client tick hook: executed on ClientTickEvents.END_CLIENT_TICK.
     */
    fun onClientTick(mc: Minecraft) {
        val player = mc.player ?: return
        val world = mc.level ?: return
        val gameMode = mc.gameMode ?: return

        // 1. Level change detection clears cached placements
        if (world !== lastLevel) {
            lastLevel = world
            PlacementCache.clear()
            lastTrackedSlot = -1
            lastExpectedItemCount = -1
            lastPlacementRecord = null
            pendingAction = null
            pendingOverride = null
        }

        // 2. Clean expired placement cooldowns
        PlacementCache.cleanup()

        // 3. Update stats counter
        val now = System.currentTimeMillis()
        if (now - lastSecondTimestamp >= 1000L) {
            lastPlacementsPerSec = placementCounterThisSec
            placementCounterThisSec = 0
            lastSecondTimestamp = now
        }

        if (!canOperate(mc, player)) return

        // 4. Server rollback detection (held item count unexpectedly jumping upward)
        val currentSlot = player.inventory.selectedSlot
        val currentCount = player.inventory.getItem(currentSlot).count
        val hasInFlight = ServerTelemetryTracker.hasInFlightPlacements()
        if (currentSlot == lastTrackedSlot && lastExpectedItemCount > 0 && currentCount > lastExpectedItemCount && !hasInFlight) {
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

        // 5. Handle tick-based execution for non-decoupled modes
        when (ConfigManager.config.placementMode) {
            PlacementMode.DECOUPLED_PACKET -> {
                // Handled in LocalPlayerMixin sendPosition hooks
            }
            PlacementMode.AIM_ASSIST -> {
                tickAimAssist(mc, player, world, gameMode)
            }
            PlacementMode.CROSSHAIR_SWEEP -> {
                tickCrosshairSweep(mc, player, world, gameMode)
            }
        }
    }

    /**
     * Mode B: Aim Assist. Smoothly glides player's physical view towards target block.
     */
    private fun tickAimAssist(
        mc: Minecraft,
        player: LocalPlayer,
        world: Level,
        gameMode: MultiPlayerGameMode
    ) {
        val eyePos = Vec3(player.x, player.y + player.getEyeHeight(), player.z)
        val action = planNextAction(mc, player, world, eyePos) ?: return
        val aimDelta = when (action) {
            is PlacementAction.PlaceBlock -> action.aimVec.subtract(eyePos)
            is PlacementAction.MultiClick -> action.aimVec.subtract(eyePos)
            is PlacementAction.TransformBlock -> action.aimVec.subtract(eyePos)
            is PlacementAction.ClearObstacle -> action.aimVec.subtract(eyePos)
            is PlacementAction.ScaffoldSequence -> action.site.anchorFace.hitVec.subtract(eyePos)
        }
        val (aimYaw, aimPitch) = deltaToYawPitch(aimDelta.x, aimDelta.y, aimDelta.z)
        val yawDiff = wrapDegrees(aimYaw - player.yRot)
        val pitchDiff = aimPitch - player.xRot
        val angularDelta = sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff)

        // Smooth physical camera aim towards target (up to 12° per tick)
        val maxTurn = 12.0f
        val stepYaw = yawDiff.coerceIn(-maxTurn, maxTurn)
        val stepPitch = pitchDiff.coerceIn(-maxTurn, maxTurn)
        player.setYRot(player.yRot + stepYaw)
        player.setXRot((player.xRot + stepPitch).coerceIn(-90f, 90f))

        // When aligned within allowable limit, execute placement
        if (angularDelta <= ConfigManager.config.maxAngleDelta) {
            executeAction(mc, player, world, gameMode, action, LookOverride(aimYaw, aimPitch))
        }
    }

    /**
     * Mode A: Crosshair Sweep. Passive placement only when crosshair sweeps over target faces.
     */
    private fun tickCrosshairSweep(
        mc: Minecraft,
        player: LocalPlayer,
        world: Level,
        gameMode: MultiPlayerGameMode
    ) {
        val eyePos = Vec3(player.x, player.y + player.getEyeHeight(), player.z)
        val action = planNextAction(mc, player, world, eyePos) ?: return
        val aimDelta = when (action) {
            is PlacementAction.PlaceBlock -> action.aimVec.subtract(eyePos)
            is PlacementAction.MultiClick -> action.aimVec.subtract(eyePos)
            is PlacementAction.TransformBlock -> action.aimVec.subtract(eyePos)
            is PlacementAction.ClearObstacle -> action.aimVec.subtract(eyePos)
            is PlacementAction.ScaffoldSequence -> action.site.anchorFace.hitVec.subtract(eyePos)
        }
        val (aimYaw, aimPitch) = deltaToYawPitch(aimDelta.x, aimDelta.y, aimDelta.z)
        val yawDiff = abs(wrapDegrees(aimYaw - player.yRot))
        val pitchDiff = abs(aimPitch - player.xRot)
        val angularDelta = sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff)
        val dist = when (action) {
            is PlacementAction.PlaceBlock -> action.aimVec.distanceTo(eyePos)
            is PlacementAction.MultiClick -> action.hitResult.location.distanceTo(eyePos)
            is PlacementAction.TransformBlock -> action.aimVec.distanceTo(eyePos)
            is PlacementAction.ClearObstacle -> action.aimVec.distanceTo(eyePos)
            is PlacementAction.ScaffoldSequence -> action.site.anchorFace.hitVec.distanceTo(eyePos)
        }
        val blockAngularRadius = Math.toDegrees(kotlin.math.atan2(0.65, dist)).toFloat()
        val allowedAngle = minOf(ConfigManager.config.maxAngleDelta.toFloat(), blockAngularRadius)

        val crosshairHit = mc.hitResult as? BlockHitResult
        val crosshairBlock = crosshairHit?.blockPos
        val targetPos = when (action) {
            is PlacementAction.PlaceBlock -> action.targetPos
            is PlacementAction.MultiClick -> action.targetPos
            is PlacementAction.TransformBlock -> action.targetPos
            is PlacementAction.ClearObstacle -> action.targetPos
            is PlacementAction.ScaffoldSequence -> action.targetPos
        }
        val isDirectCrosshair = crosshairBlock != null && (
            crosshairBlock == targetPos ||
            crosshairBlock == (action as? PlacementAction.PlaceBlock)?.hitResult?.blockPos
        )

        if (isDirectCrosshair || angularDelta <= allowedAngle) {
            executeAction(mc, player, world, gameMode, action, LookOverride(aimYaw, aimPitch))
        }
    }

    /**
     * Evaluates candidates and determines the next valid PlacementAction.
     */
    private fun planNextAction(
        mc: Minecraft,
        player: LocalPlayer,
        world: Level,
        eyePos: Vec3
    ): PlacementAction? {
        val schematicWorld = LitematicaBridge.getSchematicWorld() ?: return null
        val reach = ConfigManager.config.survivalReachLimit
        val reachCeil = ceil(reach).toInt()
        val pPos = player.blockPosition()

        val rawCandidates = ArrayList<PlacementTarget>()
        val now = System.currentTimeMillis()
        val shouldLog = (now - lastDebugLogTimestamp >= 1000L) && ConfigManager.config.debugLogging
        if (shouldLog) lastDebugLogTimestamp = now

        for (dx in -reachCeil..reachCeil) {
            for (dy in -reachCeil..reachCeil) {
                for (dz in -reachCeil..reachCeil) {
                    val pos = BlockPos(pPos.x + dx, pPos.y + dy, pPos.z + dz)

                    // F-01 Fix: Player AABB intersection check allows blocks under feet while strictly rejecting head & body
                    val blockAabb = net.minecraft.world.phys.AABB(pos)
                    if (player.boundingBox.intersects(blockAabb)) continue

                    if (pos.distToCenterSqr(eyePos.x, eyePos.y, eyePos.z) > (reach * reach)) continue
                    if (!LitematicaBridge.isPositionWithinRenderLayers(pos)) continue

                    val schemState = schematicWorld.getBlockState(pos)
                    val worldState = world.getBlockState(pos)
                    if (schemState.isAir || schemState == worldState) continue
                    if (PlacementCache.isCached(pos)) continue

                    // F-07 Fix: Extract clean registered block key instead of Block.toString()
                    val blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(schemState.block).toString()
                    rawCandidates.add(
                        PlacementTarget(
                            pos.x, pos.y, pos.z,
                            blockId
                        )
                    )
                }
            }
        }

        if (rawCandidates.isEmpty()) return null

        // Phase 11: Strict Layer-by-Layer Synchronization
        val candidatesToProcess = if (ConfigManager.config.strictLayerSync) {
            val minY = rawCandidates.minOf { it.y }
            rawCandidates.filter { it.y == minY }
        } else {
            rawCandidates
        }

        val crosshairHit = mc.hitResult as? BlockHitResult
        val crosshairBlock = crosshairHit?.blockPos

        val sorted = BuildOrderPlanner.sortCandidates(
            candidatesToProcess,
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

        for (candidate in sorted) {
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

            return action
        }

        if (rawCandidates.isNotEmpty() && now - lastOutOfItemsLogTimestamp >= 5000L) {
            lastOutOfItemsLogTimestamp = now
            val first = rawCandidates.first()
            val state = schematicWorld.getBlockState(BlockPos(first.x, first.y, first.z))
            logger.info("[Printer] Paused: Waiting for materials (${state.block.name.string}) in inventory.")
        }

        return null
    }

    /**
     * Dispatches placement packet to server and tracks telemetry.
     */
    private fun executeAction(
        mc: Minecraft,
        player: LocalPlayer,
        world: Level,
        gameMode: MultiPlayerGameMode,
        action: PlacementAction,
        override: LookOverride
    ) {
        when (action) {
            is PlacementAction.PlaceBlock -> {
                // G-3: Prepare item on hotbar strictly inside executeAction
                val preparedHand = InventoryRestockManager.prepareItem(mc, player, action.requiredItem) ?: action.hand
                val ping = mc.connection?.getPlayerInfo(player.uuid)?.latency ?: 50
                val dynamicLease = PlacementCache.computeDynamicLease(ping)

                // G-4: Explicitly notify telemetry of intended targetPos and aim angle
                ServerTelemetryTracker.setNextExpectedPlacement(action.targetPos, override.yaw, override.pitch)

                val dist = action.aimVec.distanceTo(player.eyePosition)
                val yawDiff = abs(wrapDegrees(override.yaw - player.yRot))
                val pitchDiff = abs(override.pitch - player.xRot)
                val angularDelta = sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff)

                val result = gameMode.useItemOn(player, preparedHand, action.hitResult)

                if (ConfigManager.config.debugLogging) {
                    logger.info(
                        "[Printer Place] pos=${action.targetPos} face=${action.hitResult.direction} on ${action.hitResult.blockPos} | " +
                        "mode=${ConfigManager.config.placementMode} dist=${"%.2f".format(dist)} aim=(${"%.1f".format(override.yaw)}, ${"%.1f".format(override.pitch)}) " +
                        "cam=(${"%.1f".format(player.yRot)}, ${"%.1f".format(player.xRot)}) delta=${"%.1f".format(angularDelta)}° -> Result=$result"
                    )
                } else {
                    logger.info("[Printer Place] ${action.targetPos} (${action.hitResult.direction} on ${action.hitResult.blockPos})")
                }

                lastPlacementRecord = LastPlacementRecord(
                    pos = action.targetPos,
                    face = action.hitResult.direction,
                    placeAgainst = action.hitResult.blockPos,
                    playerYaw = player.yRot,
                    playerPitch = player.xRot,
                    aimYaw = override.yaw,
                    aimPitch = override.pitch,
                    yawDelta = yawDiff,
                    pitchDelta = pitchDiff,
                    angularDelta = angularDelta,
                    dist = dist,
                    timestamp = System.currentTimeMillis()
                )

                if (result.consumesAction()) {
                    PlacementCache.markPlaced(action.targetPos, dynamicLease)
                    player.swing(preparedHand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)
                    totalPlaced++
                    placementCounterThisSec++

                    val activeSlot = player.inventory.selectedSlot
                    val activeCount = player.inventory.getItem(activeSlot).count
                    lastTrackedSlot = activeSlot
                    lastExpectedItemCount = activeCount
                } else {
                    PlacementCache.markPlaced(action.targetPos, dynamicLease.coerceAtMost(600L))
                }
            }
            is PlacementAction.MultiClick -> {
                if (ConfigManager.config.enableMultiClick) {
                    // F-06 Fix: Rate limit multi-clicks per tick to avoid packet bursts
                    val clicksToExecute = min(action.clickCount, 4)
                    for (i in 0 until clicksToExecute) {
                        val result = gameMode.useItemOn(player, action.hand, action.hitResult)
                        if (result.consumesAction()) {
                            player.swing(action.hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)
                        }
                    }
                    val remainingClicks = action.clickCount - clicksToExecute
                    if (remainingClicks > 0) {
                        PlacementCache.markPlaced(action.targetPos, 150L)
                    } else {
                        PlacementCache.markPlaced(action.targetPos, PlacementCache.DEFAULT_TIMEOUT_MS)
                    }
                    totalPlaced++
                    placementCounterThisSec++
                }
            }
            is PlacementAction.TransformBlock -> {
                // Phase 8: Equip tool matching predicate (axe/hoe) and interact in-place
                val preparedHand = InventoryRestockManager.prepareItem(mc, player, action.toolPredicate) ?: action.hand
                ServerTelemetryTracker.setNextExpectedPlacement(action.targetPos, override.yaw, override.pitch)
                val result = gameMode.useItemOn(player, preparedHand, action.hitResult)
                if (result.consumesAction()) {
                    player.swing(preparedHand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)
                    PlacementCache.markPlaced(action.targetPos, 250L)
                    totalPlaced++
                    placementCounterThisSec++
                }
            }
            is PlacementAction.ClearObstacle -> {
                // Phase 9: Switch to best tool on hotbar and dispatch instant break for soft debris
                if (player.inventory.selectedSlot != action.bestSlot) {
                    player.inventory.selectedSlot = action.bestSlot
                    player.connection.send(net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(action.bestSlot))
                }
                player.connection.send(
                    net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                        net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                        action.targetPos,
                        net.minecraft.core.Direction.UP
                    )
                )
                player.connection.send(
                    net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                        net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                        action.targetPos,
                        net.minecraft.core.Direction.UP
                    )
                )
                player.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)
                PlacementCache.markPlaced(action.targetPos, 200L)
            }
            is PlacementAction.ScaffoldSequence -> {
                // Phase 7: Two-step scaffolding placement execution
                val scaffoldName = ConfigManager.config.scaffoldBlockItem
                val scaffoldId = net.minecraft.resources.Identifier.tryParse(scaffoldName)
                    ?: net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "dirt")
                val scaffoldOptional = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(scaffoldId)
                val scaffoldItem = scaffoldOptional.map { it.value() }.orElse(Items.DIRT)
                val preparedHand = InventoryRestockManager.prepareItem(mc, player, scaffoldItem) ?: action.scaffoldHand
                val scaffoldHit = BlockHitResult(
                    action.site.anchorFace.hitVec,
                    action.site.anchorFace.clickedFace,
                    action.site.anchorFace.placeAgainstPos,
                    false
                )
                val result = gameMode.useItemOn(player, preparedHand, scaffoldHit)
                if (result.consumesAction()) {
                    player.swing(preparedHand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)
                    PlacementCache.markPlaced(action.site.scaffoldPos, 300L)
                    PlacementCache.markPlaced(action.targetPos, 150L)
                    totalPlaced++
                    placementCounterThisSec++
                } else {
                    PlacementCache.markPlaced(action.targetPos, 500L)
                }
            }
        }
    }
}
