package kyrielie.printer.client.player

import kyrielie.printer.client.bridge.LitematicaBridge
import kyrielie.printer.client.config.ConfigManager
import kyrielie.printer.client.config.PlacementMode
import kyrielie.printer.client.guides.EvaluationResult
import kyrielie.printer.client.guides.PlacementAction
import kyrielie.printer.client.guides.PlacementGuideEngine
import kyrielie.printer.client.guides.PlacementRejection
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
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Input
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

data class CandidateDiagnostic(
    val pos: BlockPos,
    val schemState: net.minecraft.world.level.block.state.BlockState,
    val worldState: net.minecraft.world.level.block.state.BlockState,
    val dist: Double,
    val rejection: PlacementRejection
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
    private var lastDiagnosticLogTimestamp = 0L
    private var lastLevel: Level? = null

    // Server rollback & telemetry tracking
    private var lastTrackedSlot = -1
    private var lastExpectedItemCount = -1
    private var rollbackCooldownTicks = 0
    private var lastPlacementRecord: LastPlacementRecord? = null

    // Block switch pause (Anti-Desync Hotbar Switch Pause - Phase 6)
    private var lastPlacedItem: net.minecraft.world.item.Item? = null
    private var switchCooldownTicksRemaining: Int = 0

    // 5-Second Stall Detection & In-Game Alerts
    private var lastSuccessfulPlacementTime: Long = System.currentTimeMillis()
    private var lastStallLogTime: Long = 0L
    private var lastStallChatTime: Long = 0L
    private var lastStallOverlayTime: Long = 0L
    private var last30sStallAlertTime: Long = 0L

    // Scaffolding tracking for scheduled demolition (removeScaffolding = true - Phase 7)
    private val placedScaffolds = java.util.concurrent.ConcurrentHashMap<BlockPos, BlockPos>()

    // State for Decoupled Packet Mode (Approach C)
    private var pendingAction: PlacementAction? = null
    private var pendingOverride: LookOverride? = null
    private var lastPlayerPos: Vec3? = null

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
            lastPlacedItem = null
            switchCooldownTicksRemaining = 0
            placedScaffolds.clear()
            lastStallLogTime = 0L
            lastStallChatTime = 0L
            lastStallOverlayTime = 0L
            last30sStallAlertTime = 0L
        } else {
            lastSuccessfulPlacementTime = System.currentTimeMillis()
            last30sStallAlertTime = 0L
            lastStallLogTime = 0L
            lastStallChatTime = 0L
            lastStallOverlayTime = 0L
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
     * Inspects operational gates and returns exact human-readable reason if printing is paused.
     */
    fun getOperationalBlockReason(mc: Minecraft, player: LocalPlayer): String? {
        if (!isRunning || !ConfigManager.config.enabled) return "Printer is disabled"
        if (mc.gui.screen() != null) return "Screen open (${mc.gui.screen()?.javaClass?.simpleName})"
        if (player.isSpectator) return "Player is in spectator mode"
        if (player.isUsingItem) return "Player is currently using an item"
        if (player.isPassenger) return "Player is riding a vehicle/mount"
        if (!player.isAlive) return "Player is dead"
        if (mc.isPaused) return "Game is paused"
        if (rollbackCooldownTicks > 0) return "Rollback cooldown active ($rollbackCooldownTicks ticks)"
        if (switchCooldownTicksRemaining > 0) return "Tool/slot switch break active ($switchCooldownTicksRemaining ticks)"
        if (ProtocolMovementGuard.isPacketThrottled()) return "Packet velocity throttled (>45 packets/100ms)"
        return null
    }

    /**
     * Unified operational safety guard (G-2 + Claude review).
     * Prevents placement while typing in chat, menus, screens, or in non-controllable states.
     */
    fun canOperate(mc: Minecraft, player: LocalPlayer): Boolean {
        return getOperationalBlockReason(mc, player) == null
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
        val (rawAimYaw, rawAimPitch) = deltaToYawPitch(aimDelta.x, aimDelta.y, aimDelta.z)
        val aimYaw = if (action is PlacementAction.PlaceBlock && action.forcedYaw != null) action.forcedYaw else rawAimYaw
        val aimPitch = if (action is PlacementAction.PlaceBlock && action.forcedPitch != null) action.forcedPitch else rawAimPitch
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
                val activeItem = player.inventory.getItem(activeSlot).item
                val activeCount = player.inventory.getItem(activeSlot).count
                if (activeCount <= 0) {
                    budget = 0
                }
                if (budget > 0) {
                    val schematicWorld = LitematicaBridge.getSchematicWorld()
                    val eyePos = Vec3(player.x, player.y + player.getEyeHeight(), player.z)
                    while (budget > 0) {
                        val secondaryAction = planNextAction(mc, player, world, eyePos) ?: break

                        // Strictly restrict secondary same-tick batching to PlaceBlock only (never batch obstacle breaks or tool interactions)
                        if (secondaryAction !is PlacementAction.PlaceBlock) {
                            break
                        }

                        // F-09 Safety Gate: Do not batch directional blocks or different block items under another block's look angle.
                        // Server-side facing is derived from the look packet; directional and distinct blocks must wait for their own tick.
                        if (secondaryAction.requiredItem != activeItem) {
                            break
                        }
                        if (schematicWorld != null) {
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

                        val secDelta = secondaryAction.aimVec.subtract(eyePos)
                        val (rawSecYaw, rawSecPitch) = deltaToYawPitch(secDelta.x, secDelta.y, secDelta.z)
                        val secYaw = if (secondaryAction.forcedYaw != null) secondaryAction.forcedYaw else rawSecYaw
                        val secPitch = if (secondaryAction.forcedPitch != null) secondaryAction.forcedPitch else rawSecPitch
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

        // 2b. Movement-triggered failure cache purge: if player moves perspective, retry failing blocks
        val pPos = player.position()
        val prevPos = lastPlayerPos
        if (prevPos == null || pPos.distanceToSqr(prevPos) > 0.64) {
            lastPlayerPos = pPos
            PlacementCache.clearFailures()
        }

        // 3. Update stats counter
        val now = System.currentTimeMillis()
        if (now - lastSecondTimestamp >= 1000L) {
            lastPlacementsPerSec = placementCounterThisSec
            placementCounterThisSec = 0
            lastSecondTimestamp = now
        }

        // 4. Tick-based countdown timers: MUST decrement every client tick unconditionally
        if (rollbackCooldownTicks > 0) {
            rollbackCooldownTicks--
        }
        if (switchCooldownTicksRemaining > 0) {
            switchCooldownTicksRemaining--
        }

        val opBlockReason = getOperationalBlockReason(mc, player)
        if (opBlockReason != null) {
            val stallDurationMs = now - lastSuccessfulPlacementTime
            if (isRunning && stallDurationMs >= 5000L) {
                val stallSeconds = stallDurationMs / 1000
                if (now - lastStallOverlayTime >= 1500L) {
                    lastStallOverlayTime = now
                    try {
                        player.sendOverlayMessage(
                            net.minecraft.network.chat.Component.literal(
                                "§c[Printer Paused ${stallSeconds}s] §e$opBlockReason"
                            )
                        )
                    } catch (_: Throwable) {}
                }
                if (now - lastStallLogTime >= 2500L) {
                    lastStallLogTime = now
                    logger.warn("[Printer Paused ${stallSeconds}s] Blocked by operational condition: $opBlockReason")
                }
            }
            return
        }

        // 5. Server rollback detection (held item count unexpectedly jumping upward)
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
                PlacementCache.markPlaced(rec.pos, 350L)
            }
            rollbackCooldownTicks = 2
        }
        lastTrackedSlot = currentSlot
        lastExpectedItemCount = currentCount

        // 6. Handle tick-based execution for non-decoupled modes
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
        val (rawAimYaw, rawAimPitch) = deltaToYawPitch(aimDelta.x, aimDelta.y, aimDelta.z)
        val aimYaw = if (action is PlacementAction.PlaceBlock && action.forcedYaw != null) action.forcedYaw else rawAimYaw
        val aimPitch = if (action is PlacementAction.PlaceBlock && action.forcedPitch != null) action.forcedPitch else rawAimPitch
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
        val (rawAimYaw, rawAimPitch) = deltaToYawPitch(aimDelta.x, aimDelta.y, aimDelta.z)
        val aimYaw = if (action is PlacementAction.PlaceBlock && action.forcedYaw != null) action.forcedYaw else rawAimYaw
        val aimPitch = if (action is PlacementAction.PlaceBlock && action.forcedPitch != null) action.forcedPitch else rawAimPitch
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
        val now = System.currentTimeMillis()
        val schematicWorld = LitematicaBridge.getSchematicWorld()
        if (schematicWorld == null) {
            if (now - lastDiagnosticLogTimestamp >= 3000L) {
                lastDiagnosticLogTimestamp = now
                logger.info("[Printer Paused] No active Litematica schematic loaded or bridge unlinked.")
            }
            return null
        }

        val reach = ConfigManager.config.survivalReachLimit
        val reachCeil = ceil(reach).toInt()
        val reachSq = reach * reach
        val pPos = player.blockPosition()
        val eyeX = eyePos.x
        val eyeY = eyePos.y
        val eyeZ = eyePos.z
        val playerBox = player.boundingBox.deflate(1e-4)
        val mutPos = BlockPos.MutableBlockPos()

        val rawCandidates = ArrayList<PlacementTarget>()
        val isStalled5s = isRunning && (now - lastSuccessfulPlacementTime >= 5000L)
        val shouldLog = ((now - lastDebugLogTimestamp >= 1000L) && ConfigManager.config.debugLogging) || (isStalled5s && now - lastDebugLogTimestamp >= 1500L)
        if (shouldLog) lastDebugLogTimestamp = now

        // Phase 7: Surgical Scaffolding Cleanup Queue (removeScaffolding = true)
        if (ConfigManager.config.removeScaffolding && placedScaffolds.isNotEmpty()) {
            val iterator = placedScaffolds.entries.iterator()
            while (iterator.hasNext()) {
                val (scaffoldPos, targetPos) = iterator.next()
                val scaffoldState = world.getBlockState(scaffoldPos)
                if (scaffoldState.isAir) {
                    iterator.remove()
                    continue
                }
                val targetState = world.getBlockState(targetPos)
                if (!targetState.isAir && !targetState.canBeReplaced()) {
                    val sX = scaffoldPos.x + 0.5 - eyeX
                    val sY = scaffoldPos.y + 0.5 - eyeY
                    val sZ = scaffoldPos.z + 0.5 - eyeZ
                    if (sX * sX + sY * sY + sZ * sZ <= reachSq && !PlacementCache.isCached(scaffoldPos)) {
                        val toolSet = kyrielie.printer.client.mining.ToolSet(
                            player,
                            ConfigManager.config.itemSaver,
                            ConfigManager.config.itemSaverThreshold
                        )
                        val bestSlot = toolSet.getBestSlot(scaffoldState)
                        val scaffoldAim = Vec3(scaffoldPos.x + 0.5, scaffoldPos.y + 0.5, scaffoldPos.z + 0.5)
                        return PlacementAction.ClearObstacle(scaffoldPos, scaffoldAim, bestSlot)
                    }
                }
            }
        }

        var totalMismatchedInReach = 0
        var layerMaskFilteredCount = 0
        var playerCollisionCount = 0
        var cooldownCachedCount = 0

        for (dx in -reachCeil..reachCeil) {
            for (dy in -reachCeil..reachCeil) {
                for (dz in -reachCeil..reachCeil) {
                    val targetX = pPos.x + dx
                    val targetY = pPos.y + dy
                    val targetZ = pPos.z + dz

                    // 1. Distance pre-filter: center of block to eyePos (Zero-alloc before checking box or registry)
                    val cX = targetX + 0.5 - eyeX
                    val cY = targetY + 0.5 - eyeY
                    val cZ = targetZ + 0.5 - eyeZ
                    if (cX * cX + cY * cY + cZ * cZ > reachSq) continue

                    mutPos.set(targetX, targetY, targetZ)
                    val schemState = schematicWorld.getBlockState(mutPos)
                    val worldState = world.getBlockState(mutPos)
                    if (schemState.isAir || schemState == worldState) continue

                    totalMismatchedInReach++

                    // 2. Litematica render layer filtering
                    if (!LitematicaBridge.isPositionWithinRenderLayers(mutPos)) {
                        layerMaskFilteredCount++
                        continue
                    }

                    // 3. Zero-allocation Player AABB intersection check (G-11):
                    // Allows blocks under feet while strictly rejecting head & body without allocating AABB objects
                    if (playerBox.minX < targetX + 1.0 && playerBox.maxX > targetX &&
                        playerBox.minY < targetY + 1.0 && playerBox.maxY > targetY &&
                        playerBox.minZ < targetZ + 1.0 && playerBox.maxZ > targetZ) {
                        playerCollisionCount++
                        continue
                    }

                    if (PlacementCache.isCached(mutPos)) {
                        cooldownCachedCount++
                        continue
                    }

                    // F-07 Fix: Extract clean registered block key instead of Block.toString()
                    val blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(schemState.block).toString()
                    rawCandidates.add(
                        PlacementTarget(
                            targetX, targetY, targetZ,
                            blockId
                        )
                    )
                }
            }
        }

        if (rawCandidates.isEmpty()) {
            val emptyReason = when {
                totalMismatchedInReach == 0 -> "All blocks in survival reach (${reach}m) match schematic."
                layerMaskFilteredCount > 0 -> {
                    val layerMode = LitematicaBridge.getLayerMode()
                    "$layerMaskFilteredCount mismatching block(s) in reach are outside active Litematica layer range (LayerMode=$layerMode)."
                }
                playerCollisionCount > 0 -> "$playerCollisionCount candidate block(s) in reach are blocked: player body is standing inside target position."
                cooldownCachedCount > 0 -> "$cooldownCachedCount candidate block(s) in reach are currently cooling down / leased from recent attempts."
                else -> "No candidate blocks in range."
            }

            val stallDurationMs = now - lastSuccessfulPlacementTime
            if (isRunning && stallDurationMs >= 5000L) {
                val stallSeconds = stallDurationMs / 1000
                if (now - lastStallOverlayTime >= 1500L) {
                    lastStallOverlayTime = now
                    try {
                        val color = if (totalMismatchedInReach == 0) "§6[Printer Idle]" else "§c[Printer Stalled ${stallSeconds}s]"
                        player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("$color §e$emptyReason"))
                    } catch (_: Throwable) {}
                }
                if (now - lastStallLogTime >= 2500L) {
                    lastStallLogTime = now
                    if (totalMismatchedInReach == 0) {
                        logger.info("[Printer Idle ${stallSeconds}s] $emptyReason")
                    } else {
                        logger.warn("[Printer Stalled ${stallSeconds}s] $emptyReason (playerPos=${player.blockPosition()}, reach=${reach}m, mismatched=$totalMismatchedInReach, layerFiltered=$layerMaskFilteredCount, bodyCollision=$playerCollisionCount, cached=$cooldownCachedCount)")
                    }
                }
                if (now - lastStallChatTime >= 8000L && totalMismatchedInReach > 0) {
                    lastStallChatTime = now
                    try {
                        player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c[Printer Stalled ${stallSeconds}s] §e$emptyReason"))
                    } catch (_: Throwable) {}
                }
            } else if (now - lastDiagnosticLogTimestamp >= 3000L) {
                lastDiagnosticLogTimestamp = now
                if (totalMismatchedInReach == 0) {
                    logger.info("[Printer Idle] $emptyReason")
                } else {
                    logger.warn("[Printer Paused] $emptyReason")
                }
            }
            return null
        }

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

        val rejections = ArrayList<PlacementRejection>()
        val candidateDiagnostics = ArrayList<CandidateDiagnostic>()

        for (candidate in sorted) {
            val targetPos = BlockPos(candidate.x, candidate.y, candidate.z)
            if (PlacementCache.isCached(targetPos)) continue

            val schemState = schematicWorld.getBlockState(targetPos)
            val isDecoupled = ConfigManager.config.placementMode == PlacementMode.DECOUPLED_PACKET
            val evalResult = PlacementGuideEngine.evaluateDetailed(
                mc,
                player,
                world,
                eyePos,
                targetPos,
                schemState,
                useCarpetProtocol = ConfigManager.config.carpetProtocol,
                enableScaffolding = ConfigManager.config.enableScaffolding,
                maxReach = reach,
                debug = shouldLog,
                allowDecoupledBridge = isDecoupled
            )

            when (evalResult) {
                is EvaluationResult.Success -> return evalResult.action
                is EvaluationResult.Rejected -> {
                    rejections.add(evalResult.rejection)
                    val dist = kotlin.math.sqrt(targetPos.distToCenterSqr(eyePos.x, eyePos.y, eyePos.z))
                    candidateDiagnostics.add(
                        CandidateDiagnostic(
                            targetPos,
                            schemState,
                            world.getBlockState(targetPos),
                            dist,
                            evalResult.rejection
                        )
                    )
                }
            }
        }

        // Auto-heal watchdog: If candidates exist in reach but no placement succeeded for 1.5s,
        // automatically purge stale placement leases and reset cooldowns (simulating toggle reset).
        if (now - lastSuccessfulPlacementTime >= 1500L) {
            if (cooldownCachedCount > 0 || rollbackCooldownTicks > 0 || switchCooldownTicksRemaining > 0) {
                PlacementCache.clear()
                rollbackCooldownTicks = 0
                switchCooldownTicksRemaining = 0
                lastTrackedSlot = -1
                lastExpectedItemCount = -1
                lastPlacedItem = null
                if (shouldLog) {
                    logger.info("[Printer Auto-Heal] Flushed placement cache and reset cooldowns to force retries.")
                }
            }
        }

        val stallSummary = formatStallSummary(rejections, sorted.size)

        if (now - lastDiagnosticLogTimestamp >= 3000L) {
            lastDiagnosticLogTimestamp = now
            if (stallSummary != null && !isStalled5s) {
                logger.info("[Printer Stalled] $stallSummary")
            }
        }

        handleStallDiagnostics(player, now, candidateDiagnostics, sorted.size)

        return null
    }

    private fun handleStallDiagnostics(
        player: LocalPlayer,
        now: Long,
        diagnostics: List<CandidateDiagnostic>,
        totalCandidates: Int
    ) {
        if (!isRunning) return
        val stallDurationMs = now - lastSuccessfulPlacementTime
        if (stallDurationMs < 5000L) return

        val stallSeconds = stallDurationMs / 1000

        // Format concise 1-line reason for action bar overlay
        val topDiag = diagnostics.firstOrNull()
        val primaryReason = if (topDiag != null) {
            formatConciseReason(topDiag)
        } else {
            "No reachable candidate blocks found"
        }

        // Action bar overlay every 1.5s while stalled
        if (now - lastStallOverlayTime >= 1500L) {
            lastStallOverlayTime = now
            try {
                player.sendOverlayMessage(
                    net.minecraft.network.chat.Component.literal(
                        "§c[Printer Stalled ${stallSeconds}s] §e$primaryReason"
                    )
                )
            } catch (_: Throwable) {}
        }

        // Detailed console/file log every 2.5s while stalled
        if (now - lastStallLogTime >= 2500L) {
            lastStallLogTime = now
            val logReport = buildDetailedStallReport(stallSeconds, diagnostics, totalCandidates, player)
            logger.warn(logReport)
        }

        // In-game chat alert every 8s while stalled
        if (now - lastStallChatTime >= 8000L) {
            lastStallChatTime = now
            val chatReport = buildChatStallReport(stallSeconds, diagnostics)
            try {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(chatReport))
            } catch (_: Throwable) {}
        }
    }

    private fun formatConciseReason(diag: CandidateDiagnostic): String {
        val posStr = "(${diag.pos.x}, ${diag.pos.y}, ${diag.pos.z})"
        val blockName = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(diag.schemState.block).path
        return when (val rej = diag.rejection) {
            is PlacementRejection.MissingItem -> {
                val itemName = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(rej.item).path
                "Missing $itemName for $posStr"
            }
            is PlacementRejection.InfeasibleOrientation -> {
                "Orientation infeasible: $blockName at $posStr"
            }
            is PlacementRejection.RaycastFailed -> {
                "Raycast occluded: $blockName at $posStr"
            }
            is PlacementRejection.NoSolidSupport -> {
                "No solid support: $blockName at $posStr"
            }
            is PlacementRejection.BlockOccupied -> {
                "Occupied by ${rej.existingBlock.descriptionId} at $posStr"
            }
            is PlacementRejection.SafetyGateBlocked -> {
                "Safety gate blocked: ${rej.reason} at $posStr"
            }
            is PlacementRejection.MultiBlockSlave -> {
                "Multi-block upper half at $posStr"
            }
            is PlacementRejection.AwaitingWaterlogging -> "Awaiting waterlogging at $posStr"
            is PlacementRejection.AlreadyPlaced -> "Already placed at $posStr"
        }
    }

    private fun buildDetailedStallReport(
        stallSeconds: Long,
        diagnostics: List<CandidateDiagnostic>,
        totalCandidates: Int,
        player: LocalPlayer
    ): String {
        val sb = StringBuilder()
        sb.append("[Printer Stalled ${stallSeconds}s] No blocks placed for ${stallSeconds}s! ")
        sb.append("Found $totalCandidates candidate(s) in reach. Showing top ${minOf(5, diagnostics.size)} blocking candidates:\n")
        val pPos = player.blockPosition()
        val heldItem = player.mainHandItem
        val heldItemStr = if (heldItem.isEmpty) "Empty" else "${heldItem.item} x${heldItem.count}"
        sb.append("  [Player State] pos=(${pPos.x}, ${pPos.y}, ${pPos.z}), look=(${"%.1f".format(player.yRot)}, ${"%.1f".format(player.xRot)}), held=$heldItemStr, slot=${player.inventory.selectedSlot}\n")

        for ((index, diag) in diagnostics.take(5).withIndex()) {
            val schemName = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(diag.schemState.block).toString()
            val worldName = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(diag.worldState.block).toString()
            sb.append("  Candidate #${index + 1} @ (${diag.pos.x}, ${diag.pos.y}, ${diag.pos.z}) dist=${"%.2f".format(diag.dist)}m: schem=$schemName, world=$worldName\n")
            when (val rej = diag.rejection) {
                is PlacementRejection.MissingItem -> {
                    val itemName = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(rej.item).toString()
                    val hasItem = InventoryRestockManager.hasItem(player, rej.item)
                    sb.append("     -> MissingItem: $itemName (in inventory=$hasItem, ${rej.details ?: "required for placement/transformation"})\n")
                }
                is PlacementRejection.InfeasibleOrientation -> {
                    sb.append("     -> InfeasibleOrientation: ${rej.details}\n")
                }
                is PlacementRejection.RaycastFailed -> {
                    sb.append("     -> RaycastFailed: ${rej.details}\n")
                    if (rej.faceAttempts.isNotEmpty()) {
                        sb.append("        Faces tested:\n")
                        for (attempt in rej.faceAttempts.take(6)) {
                            sb.append("          * $attempt\n")
                        }
                    }
                }
                is PlacementRejection.NoSolidSupport -> {
                    sb.append("     -> NoSolidSupport: ${rej.details}\n")
                }
                is PlacementRejection.BlockOccupied -> {
                    val occName = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(rej.existingBlock).toString()
                    sb.append("     -> BlockOccupied: $occName (not air/replaceable)\n")
                }
                is PlacementRejection.SafetyGateBlocked -> {
                    sb.append("     -> SafetyGateBlocked: ${rej.reason}\n")
                }
                else -> {
                    sb.append("     -> $rej\n")
                }
            }
        }
        return sb.toString().trimEnd()
    }

    private fun buildChatStallReport(
        stallSeconds: Long,
        diagnostics: List<CandidateDiagnostic>
    ): String {
        val sb = StringBuilder()
        sb.append("§c[Printer Stalled ${stallSeconds}s] §eTop blocking candidates in reach:\n")
        for ((index, diag) in diagnostics.take(3).withIndex()) {
            val blockName = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(diag.schemState.block).path
            val posStr = "(${diag.pos.x}, ${diag.pos.y}, ${diag.pos.z})"
            val reasonStr = when (val rej = diag.rejection) {
                is PlacementRejection.MissingItem -> {
                    val item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(rej.item).path
                    "Missing item: §f$item"
                }
                is PlacementRejection.InfeasibleOrientation -> {
                    "Orientation: §f${rej.details.take(60)}"
                }
                is PlacementRejection.RaycastFailed -> {
                    "Occluded: §f${rej.details.take(60)}"
                }
                is PlacementRejection.NoSolidSupport -> {
                    "No support: §f${rej.details.take(50)}"
                }
                is PlacementRejection.BlockOccupied -> {
                    "Occupied by §f${rej.existingBlock.descriptionId}"
                }
                is PlacementRejection.SafetyGateBlocked -> {
                    "Safety gate: §f${rej.reason}"
                }
                else -> rej.toString()
            }
            sb.append("§7#${index + 1} §e$posStr §6$blockName §7(§f${"%.1f".format(diag.dist)}m§7): §c$reasonStr\n")
        }
        return sb.toString().trimEnd()
    }

    private fun check30sStall(player: LocalPlayer, now: Long, reason: String) {
        if (!isRunning) return
        if (now - lastSuccessfulPlacementTime >= 30_000L) {
            if (now - last30sStallAlertTime >= 10_000L) {
                last30sStallAlertTime = now
                logger.warn("[Printer Stall Alert] No placement for 30s! Reason: $reason")
                try {
                    player.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal("§c[Printer] Stalled (30s): §e$reason")
                    )
                } catch (_: Throwable) {}
            }
        }
    }

    private fun formatStallSummary(rejections: List<PlacementRejection>, totalCandidates: Int): String? {
        val missingItems = LinkedHashMap<String, Int>()
        var noSupportCount = 0
        var noSupportReason: String? = null
        var orientationCount = 0
        var orientationReason: String? = null
        var raycastCount = 0
        var raycastReason: String? = null
        var safetyCount = 0
        var occupiedCount = 0

        for (rej in rejections) {
            when (rej) {
                is PlacementRejection.MissingItem -> {
                    val name = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(rej.item).toString()
                    missingItems[name] = (missingItems[name] ?: 0) + 1
                }
                is PlacementRejection.NoSolidSupport -> {
                    noSupportCount++
                    if (noSupportReason == null) noSupportReason = rej.details
                }
                is PlacementRejection.InfeasibleOrientation -> {
                    orientationCount++
                    if (orientationReason == null) orientationReason = rej.details
                }
                is PlacementRejection.RaycastFailed -> {
                    raycastCount++
                    if (raycastReason == null) raycastReason = rej.details
                }
                is PlacementRejection.SafetyGateBlocked -> safetyCount++
                is PlacementRejection.BlockOccupied -> occupiedCount++
                else -> {}
            }
        }

        val details = ArrayList<String>()
        if (missingItems.isNotEmpty()) {
            val itemsFormatted = missingItems.entries.joinToString(", ") { "${it.key} (x${it.value})" }
            details.add("Missing materials: $itemsFormatted")
        }
        if (noSupportCount > 0) {
            details.add("No solid support (need scaffolding): $noSupportCount (${noSupportReason ?: "infeasible site"})")
        }
        if (orientationCount > 0) {
            details.add("Orientation infeasible: $orientationCount (${orientationReason ?: "angle infeasible"})")
        }
        if (raycastCount > 0) {
            details.add("Occluded or out of reach: $raycastCount")
        }
        if (safetyCount > 0) {
            details.add("Safety gate blocked: $safetyCount")
        }
        if (occupiedCount > 0) {
            details.add("Target position occupied: $occupiedCount")
        }

        if (details.isNotEmpty()) {
            return "$totalCandidates candidate block(s) in reach cannot be placed:\n  * " + details.joinToString("\n  * ")
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
                // Pre-flight Gate 1: Check target position is still replaceable
                val currentTargetState = world.getBlockState(action.targetPos)
                val isWaterlogging = action.requiredItem == net.minecraft.world.item.Items.WATER_BUCKET
                if (!isWaterlogging && !currentTargetState.canBeReplaced()) {
                    PlacementCache.markPlaced(action.targetPos, 500L)
                    return
                }

                // Pre-flight Gate 2: Re-verify schematic still requires this block (including waterlogging & two-stage log stripping)
                val schematicWorld = LitematicaBridge.getSchematicWorld()
                val schemState = schematicWorld?.getBlockState(action.targetPos)
                if (schemState != null) {
                    val directItemMatch = schemState.block.asItem() == action.requiredItem
                    val waterlogMatch = action.requiredItem == net.minecraft.world.item.Items.WATER_BUCKET &&
                                        schemState.hasProperty(BlockStateProperties.WATERLOGGED) &&
                                        schemState.getValue(BlockStateProperties.WATERLOGGED)
                    val rawLogMatch = kyrielie.printer.client.interaction.BlockTransformationRegistry.getStrippedVariant(
                        net.minecraft.world.level.block.Block.byItem(action.requiredItem)
                    ) == schemState.block
                    if (!directItemMatch && !waterlogMatch && !rawLogMatch) {
                        return
                    }
                }

                // Pre-flight Gate 3: Check support block is still solid
                val placeAgainstState = world.getBlockState(action.hitResult.blockPos)
                if (placeAgainstState.isAir) {
                    return
                }

                // Pre-flight Gate 4: Movement reach validation against current player position
                val currentReach = action.hitResult.location.distanceTo(player.eyePosition)
                if (currentReach > ConfigManager.config.survivalReachLimit) {
                    return
                }

                // Pre-flight Gate 5: Player bounding box collision check (deflated by 1e-4 to allow floor placement)
                val targetAabb = net.minecraft.world.phys.AABB(action.targetPos)
                if (player.boundingBox.deflate(1e-4).intersects(targetAabb)) {
                    return
                }

                // G-3: Prepare item on hotbar strictly inside executeAction
                val slotBefore = player.inventory.selectedSlot
                val preparedHand = InventoryRestockManager.prepareItem(mc, player, action.requiredItem) ?: return
                val slotAfter = player.inventory.selectedSlot

                // Phase 6: Block Switch Placement Break (Anti-Desync Hotbar Switch Pause)
                // Only enforce delay if the hotbar slot actually changed or a different item is held
                if (lastPlacedItem != null && (action.requiredItem != lastPlacedItem || slotBefore != slotAfter) && ConfigManager.config.blockSwitchDelayTicks > 0) {
                    switchCooldownTicksRemaining = ConfigManager.config.blockSwitchDelayTicks
                    lastPlacedItem = action.requiredItem
                    resetRollbackTracking()
                    return
                }
                lastPlacedItem = action.requiredItem

                // Pre-flight Gate 6: Strict held item identity check
                val heldStack = player.getItemInHand(preparedHand)
                if (heldStack.isEmpty || heldStack.item != action.requiredItem) {
                    logger.warn("[Printer Safety] Blocked placement at ${action.targetPos}: hand $preparedHand has ${heldStack.item}, but required ${action.requiredItem}!")
                    return
                }
                val ping = mc.connection?.getPlayerInfo(player.uuid)?.latency ?: 50
                val dynamicLease = PlacementCache.computeDynamicLease(ping)

                // G-4: Explicitly notify telemetry of intended targetPos and aim angle
                ServerTelemetryTracker.setNextExpectedPlacement(action.targetPos, override.yaw, override.pitch)

                val dist = action.aimVec.distanceTo(player.eyePosition)
                val yawDiff = abs(wrapDegrees(override.yaw - player.yRot))
                val pitchDiff = abs(override.pitch - player.xRot)
                val angularDelta = sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff)

                val needsShift = FaceRaycaster.isInteractiveBlock(placeAgainstState)

                val result = if (action.requiredItem is net.minecraft.world.item.PlaceOnWaterBlockItem) {
                    gameMode.useItem(player, preparedHand)
                } else if (needsShift) {
                    withShift(player, true) {
                        gameMode.useItemOn(player, preparedHand, action.hitResult)
                    }
                } else {
                    gameMode.useItemOn(player, preparedHand, action.hitResult)
                }

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

                val isStage1Log = schematicWorld?.getBlockState(action.targetPos)?.let { schem ->
                    kyrielie.printer.client.interaction.BlockTransformationRegistry.getStrippedVariant(
                        net.minecraft.world.level.block.Block.byItem(action.requiredItem)
                    ) == schem.block
                } ?: false

                if (result.consumesAction()) {
                    lastSuccessfulPlacementTime = System.currentTimeMillis()
                    PlacementCache.recordSuccess(action.targetPos)
                    if (!isStage1Log) {
                        PlacementCache.markPlaced(action.targetPos, dynamicLease)
                    }
                    player.swing(preparedHand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)
                    totalPlaced++
                    placementCounterThisSec++
                    lastSuccessfulPlacementTime = System.currentTimeMillis()

                    val activeSlot = player.inventory.selectedSlot
                    val activeCount = player.inventory.getItem(activeSlot).count
                    lastTrackedSlot = activeSlot
                    lastExpectedItemCount = activeCount

                    // Immediate in-tick stripping if axe is available
                    if (isStage1Log) {
                        val axePredicate = kyrielie.printer.client.interaction.BlockTransformationRegistry.AXES
                        val hasAxe = InventoryRestockManager.hasItem(player) { !it.isEmpty && it.item in axePredicate }
                        if (hasAxe) {
                            val axeHand = InventoryRestockManager.prepareItem(mc, player) { !it.isEmpty && it.item in axePredicate }
                            if (axeHand != null) {
                                val faceToClick = Direction.entries
                                    .filter { dir ->
                                        val adjacent = world.getBlockState(action.targetPos.relative(dir))
                                        adjacent.isAir || adjacent.canBeReplaced()
                                    }
                                    .maxByOrNull { dir ->
                                        val faceVec = Vec3(dir.stepX.toDouble(), dir.stepY.toDouble(), dir.stepZ.toDouble())
                                        val toEye = player.eyePosition.subtract(Vec3(action.targetPos.x + 0.5, action.targetPos.y + 0.5, action.targetPos.z + 0.5)).normalize()
                                        faceVec.dot(toEye)
                                    } ?: Direction.UP

                                val stripAim = Vec3(
                                    action.targetPos.x + 0.5 + faceToClick.stepX * 0.5,
                                    action.targetPos.y + 0.5 + faceToClick.stepY * 0.5,
                                    action.targetPos.z + 0.5 + faceToClick.stepZ * 0.5
                                )
                                val stripHit = BlockHitResult(stripAim, faceToClick, action.targetPos, false)
                                ServerTelemetryTracker.setNextExpectedPlacement(action.targetPos, override.yaw, override.pitch)
                                val stripResult = gameMode.useItemOn(player, axeHand, stripHit)
                                if (ConfigManager.config.debugLogging) {
                                    logger.info("[Printer Instant Strip] pos=${action.targetPos} face=$faceToClick with tool=${player.getItemInHand(axeHand).item} -> Result=$stripResult")
                                }
                                if (stripResult.consumesAction()) {
                                    player.swing(axeHand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)
                                    PlacementCache.markPlaced(action.targetPos, 250L)
                                }
                            }
                        }
                    }
                } else {
                    PlacementCache.recordFailure(action.targetPos)
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
                    lastSuccessfulPlacementTime = System.currentTimeMillis()
                }
            }
            is PlacementAction.TransformBlock -> {
                // Phase 8: Equip tool matching predicate (axe/hoe) and interact in-place
                val slotBefore = player.inventory.selectedSlot
                val preparedHand = InventoryRestockManager.prepareItem(mc, player, action.toolPredicate) ?: return
                val slotAfter = player.inventory.selectedSlot

                if (slotBefore != slotAfter && ConfigManager.config.blockSwitchDelayTicks > 0) {
                    switchCooldownTicksRemaining = ConfigManager.config.blockSwitchDelayTicks
                    resetRollbackTracking()
                    return
                }

                val equippedItem = player.getItemInHand(preparedHand).item
                lastPlacedItem = equippedItem

                ServerTelemetryTracker.setNextExpectedPlacement(action.targetPos, override.yaw, override.pitch)
                val placeAgainstState = world.getBlockState(action.hitResult.blockPos)
                val needsShift = FaceRaycaster.isInteractiveBlock(placeAgainstState)

                val result = if (needsShift) {
                    withShift(player, true) {
                        gameMode.useItemOn(player, preparedHand, action.hitResult)
                    }
                } else {
                    gameMode.useItemOn(player, preparedHand, action.hitResult)
                }

                if (ConfigManager.config.debugLogging) {
                    logger.info("[Printer Transform] pos=${action.targetPos} face=${action.hitResult.direction} with tool=$equippedItem -> Result=$result")
                }

                if (result.consumesAction()) {
                    player.swing(preparedHand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)
                    PlacementCache.markPlaced(action.targetPos, 250L)
                    PlacementCache.recordSuccess(action.targetPos)
                    totalPlaced++
                    placementCounterThisSec++
                    lastSuccessfulPlacementTime = System.currentTimeMillis()
                } else {
                    PlacementCache.recordFailure(action.targetPos)
                }
            }
            is PlacementAction.ClearObstacle -> {
                // Phase 9: Switch to best tool on hotbar and dispatch instant break for soft debris
                ServerTelemetryTracker.setNextExpectedPlacement(action.targetPos, override.yaw, override.pitch)
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
                // Phase 7: Two-step scaffolding placement execution with dynamic item resolution
                val preparedHand = InventoryRestockManager.prepareItem(mc, player, action.scaffoldItem) ?: return

                if (lastPlacedItem != null && action.scaffoldItem != lastPlacedItem && ConfigManager.config.blockSwitchDelayTicks > 0) {
                    switchCooldownTicksRemaining = ConfigManager.config.blockSwitchDelayTicks
                    lastPlacedItem = action.scaffoldItem
                    resetRollbackTracking()
                    return
                }
                lastPlacedItem = action.scaffoldItem

                val scaffoldHit = BlockHitResult(
                    action.site.anchorFace.hitVec,
                    action.site.anchorFace.clickedFace,
                    action.site.anchorFace.placeAgainstPos,
                    false
                )
                val placeAgainstState = world.getBlockState(action.site.anchorFace.placeAgainstPos)
                val needsShift = FaceRaycaster.isInteractiveBlock(placeAgainstState)

                ServerTelemetryTracker.setNextExpectedPlacement(action.site.scaffoldPos, override.yaw, override.pitch)

                val result = if (needsShift) {
                    withShift(player, true) {
                        gameMode.useItemOn(player, preparedHand, scaffoldHit)
                    }
                } else {
                    gameMode.useItemOn(player, preparedHand, scaffoldHit)
                }

                logger.info("[Printer Place Scaffold] pos=${action.site.scaffoldPos} (${action.site.anchorFace.clickedFace} on ${action.site.anchorFace.placeAgainstPos}) using ${action.scaffoldItem} -> Result=$result")
                if (result.consumesAction()) {
                    player.swing(preparedHand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true)
                    PlacementCache.markPlaced(action.site.scaffoldPos, 300L)
                    PlacementCache.markPlaced(action.targetPos, 150L)
                    if (ConfigManager.config.removeScaffolding) {
                        placedScaffolds[action.site.scaffoldPos] = action.targetPos
                    }
                    totalPlaced++
                    placementCounterThisSec++
                    lastSuccessfulPlacementTime = System.currentTimeMillis()
                } else {
                    PlacementCache.markPlaced(action.targetPos, 500L)
                }
            }
        }
    }

    /**
     * Executes placement with shift-click (sneaking) active so that right-clicking
     * interactive blocks (noteblocks, chests, crafting tables, repeaters) places the
     * held block instead of opening/tuning the container.
     */
    fun withShift(player: LocalPlayer, shift: Boolean, action: () -> InteractionResult): InteractionResult {
        val previous = player.input.keyPresses
        if (previous.shift() == shift) {
            return action()
        }
        val temporary = Input(
            previous.forward(),
            previous.backward(),
            previous.left(),
            previous.right(),
            previous.jump(),
            shift,
            previous.sprint()
        )
        return try {
            player.input.keyPresses = temporary
            player.setShiftKeyDown(shift)
            player.connection.send(ServerboundPlayerInputPacket(temporary))
            action()
        } finally {
            player.input.keyPresses = previous
            player.setShiftKeyDown(previous.shift())
            player.connection.send(ServerboundPlayerInputPacket(previous))
        }
    }

    /**
     * Resets expected item count tracking to avoid false-positive rollback detection
     * immediately after an intentional inventory swap or hotbar switch.
     */
    fun resetRollbackTracking() {
        lastExpectedItemCount = -1
    }
}
