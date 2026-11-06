package kyrielie.printer.client.telemetry

import kyrielie.printer.client.player.PlacementCache
import kyrielie.printer.client.player.PrinterController
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

data class InFlightPlacement(
    val sequence: Int,
    val targetPos: BlockPos,
    val placeAgainst: BlockPos,
    val face: Direction,
    val hitVec: Vec3,
    val eyePos: Vec3,
    val sentAimYaw: Float,
    val sentAimPitch: Float,
    val cameraYaw: Float,
    val cameraPitch: Float,
    val distance: Double,
    val sentTimestamp: Long,
    val clientPingMs: Int
)

object ServerTelemetryTracker {

    private val logger = LoggerFactory.getLogger("Printer/ServerTelemetry")

    private val inFlightBySeq = ConcurrentHashMap<Int, InFlightPlacement>()
    private val inFlightByPos = ConcurrentHashMap<BlockPos, InFlightPlacement>()

    @Volatile
    private var pendingExplicitTarget: Pair<BlockPos, Pair<Float, Float>>? = null

    fun setNextExpectedPlacement(targetPos: BlockPos, aimYaw: Float, aimPitch: Float) {
        pendingExplicitTarget = Pair(targetPos, Pair(aimYaw, aimPitch))
    }

    fun onOutgoingUseItemOn(packet: ServerboundUseItemOnPacket) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val seq = packet.sequence
        val hit = packet.hitResult
        val placeAgainst = hit.blockPos
        val face = hit.direction

        // G-4: Use explicit targetPos if set by executeAction; otherwise handle replaceable blocks
        val explicit = pendingExplicitTarget
        pendingExplicitTarget = null
        val targetPos = explicit?.first ?: run {
            val worldState = mc.level?.getBlockState(placeAgainst)
            if (worldState != null && worldState.canBeReplaced()) placeAgainst else placeAgainst.relative(face)
        }
        val aimYaw = explicit?.second?.first ?: player.yRot
        val aimPitch = explicit?.second?.second ?: player.xRot

        val eyePos = player.eyePosition
        val hitVec = hit.location
        val dist = hitVec.distanceTo(eyePos)
        val ping = mc.connection?.getPlayerInfo(player.uuid)?.latency ?: -1

        val info = InFlightPlacement(
            sequence = seq,
            targetPos = targetPos,
            placeAgainst = placeAgainst,
            face = face,
            hitVec = hitVec,
            eyePos = eyePos,
            sentAimYaw = aimYaw,
            sentAimPitch = aimPitch,
            cameraYaw = player.yRot,
            cameraPitch = player.xRot,
            distance = dist,
            sentTimestamp = System.currentTimeMillis(),
            clientPingMs = ping
        )

        inFlightBySeq[seq] = info
        inFlightByPos[targetPos] = info

        // Clean stale entries older than 5000ms
        val now = System.currentTimeMillis()
        inFlightBySeq.entries.removeIf { now - it.value.sentTimestamp > 5000L }
        inFlightByPos.entries.removeIf { now - it.value.sentTimestamp > 5000L }
    }

    fun onBlockChangedAck(sequence: Int) {
        val info = inFlightBySeq.remove(sequence) ?: return
        val latency = System.currentTimeMillis() - info.sentTimestamp
        if (kyrielie.printer.client.config.ConfigManager.config.debugLogging) {
            logger.info(
                "[Server ACK] seq=$sequence confirmed in ${latency}ms for target pos=${info.targetPos} " +
                "(clicked ${info.face} on ${info.placeAgainst}, reach=${"%.2f".format(info.distance)}, ping=${info.clientPingMs}ms)"
            )
        }
    }

    fun onBlockUpdate(pos: BlockPos, state: BlockState) {
        val info = inFlightByPos[pos] ?: return
        val latency = System.currentTimeMillis() - info.sentTimestamp

        if (state.isAir) {
            // Paper server rejected placement and forced position back to AIR!
            logger.warn(
                "[Server REJECT] Server forced pos=$pos back to AIR! (Took ${latency}ms, seq=${info.sequence}). " +
                "Diagnostics: clicked ${info.face} on ${info.placeAgainst}, hitVec=${info.hitVec}, " +
                "reach=${"%.2f".format(info.distance)}, playerAim=(${"%.1f".format(info.sentAimYaw)}, ${"%.1f".format(info.sentAimPitch)}), " +
                "ping=${info.clientPingMs}ms"
            )
            inFlightByPos.remove(pos)

            // Auto-heal: mark placement cache with cooldown
            PlacementCache.markPlaced(pos, 2000L)
        } else {
            // Server confirmed placement
            if (kyrielie.printer.client.config.ConfigManager.config.debugLogging) {
                logger.info(
                    "[Server CONFIRMED] Server confirmed $pos as ${state.block} in ${latency}ms (seq=${info.sequence})!"
                )
            }
            inFlightByPos.remove(pos)
        }
    }

    fun onContainerSetSlot(containerId: Int, slot: Int, itemStack: ItemStack) {
        if (!kyrielie.printer.client.config.ConfigManager.config.debugLogging) return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (containerId == 0) { // Player inventory container
            val selectedSlot = player.inventory.selectedSlot
            val hotbarContainerSlot = 36 + selectedSlot
            if (slot == hotbarContainerSlot) {
                logger.info(
                    "[Server SlotSync] Server sync on active hotbar slot $selectedSlot (containerSlot $slot): " +
                    "count=${itemStack.count} (${itemStack.item})"
                )
            }
        }
    }

    fun onPlayerPosition(packet: ClientboundPlayerPositionPacket) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (!PrinterController.isRunning) return

        val currentValues = PositionMoveRotation.of(player)
        val newValues = PositionMoveRotation.calculateAbsolute(currentValues, packet.change, packet.relatives)
        val dx = newValues.position.x - player.x
        val dy = newValues.position.y - player.y
        val dz = newValues.position.z - player.z
        val distSqr = dx * dx + dy * dy + dz * dz

        if (distSqr > 0.04) { // > 0.2 blocks offset
            logger.warn(
                "[Server RUBBERBAND] Server teleported/corrected player position! " +
                "Delta=(dx=${"%.2f".format(dx)}, dy=${"%.2f".format(dy)}, dz=${"%.2f".format(dz)}), " +
                "teleportId=${packet.id}"
            )
        }
    }

    fun onSystemChat(content: Component, overlay: Boolean) {
        if (!PrinterController.isRunning) return
        val type = if (overlay) "ActionBar" else "Chat"
        logger.info("[Server $type] ${content.string}")
    }
}
