package kyrielie.printer.client.telemetry

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kyrielie.printer.client.bridge.LitematicaBridge
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class StairDebugEntry(
    val id: Int,
    val timestamp: String,
    val source: String, // "MANUAL" or "PRINTER"
    val item: String,
    val hand: String,
    val sequence: Int,
    val placeAgainstPos: List<Int>,
    val placeAgainstBlock: String,
    val clickedFace: String,
    val hitVec: List<Double>,
    val relativeHitVec: List<Double>,
    val isInside: Boolean,
    val targetPos: List<Int>,
    val playerPos: List<Double>,
    val playerEyePos: List<Double>,
    val playerCameraYaw: Float,
    val playerCameraPitch: Float,
    val playerDirection: String,
    val sentAimYaw: Float,
    val sentAimPitch: Float,
    val reachDistance: Double,
    val vanillaCalculatedFacing: String,
    val vanillaCalculatedHalf: String,
    val schematicState: String?,
    val clientStateBefore: String?,
    var clientStateAfter: String? = null,
    var serverConfirmedState: String? = null,
    var ackLatencyMs: Long? = null
)

object StairPlacementDebugger {

    private val logger = LoggerFactory.getLogger("Printer/StairDebug")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val debugFile: File = FabricLoader.getInstance().configDir.resolve("litematica_printer_stair_debug.json").toFile()

    private val entries = CopyOnWriteArrayList<StairDebugEntry>()
    private val activeByPos = ConcurrentHashMap<BlockPos, StairDebugEntry>()
    private val activeBySeq = ConcurrentHashMap<Int, StairDebugEntry>()
    private var nextId = 1
    private val lock = Any()

    init {
        loadExisting()
    }

    private fun loadExisting() {
        try {
            if (debugFile.exists()) {
                debugFile.reader().use { reader ->
                    val loaded = gson.fromJson(reader, Array<StairDebugEntry>::class.java)
                    if (loaded != null) {
                        entries.addAll(loaded)
                        nextId = (entries.maxOfOrNull { it.id } ?: 0) + 1
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Could not load existing stair debug entries: ${e.message}")
        }
    }

    fun onOutgoingUseItemOn(
        packet: ServerboundUseItemOnPacket,
        isManual: Boolean,
        sentAimYaw: Float?,
        sentAimPitch: Float?
    ) {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        val level = mc.level ?: return
        val stack = player.getItemInHand(packet.hand)
        val hit = packet.hitResult
        val placeAgainst = hit.blockPos
        val clickedFace = hit.direction

        val isStairItem = (stack.item is net.minecraft.world.item.BlockItem && (stack.item as net.minecraft.world.item.BlockItem).block is StairBlock) ||
            BuiltInRegistries.ITEM.getKey(stack.item).path.contains("stairs")

        val targetPos = if (level.getBlockState(placeAgainst).canBeReplaced()) placeAgainst else placeAgainst.relative(clickedFace)
        val schemWorld = LitematicaBridge.getSchematicWorld()
        val schemState = schemWorld?.getBlockState(targetPos)
        val isSchemStair = schemState?.block is StairBlock

        // Only track if placed item or schematic target is a stair
        if (!isStairItem && !isSchemStair) return

        val eyePos = player.eyePosition
        val hitVec = hit.location
        val relHit = Vec3(
            hitVec.x - placeAgainst.x,
            hitVec.y - placeAgainst.y,
            hitVec.z - placeAgainst.z
        )

        val yawUsed = sentAimYaw ?: player.yRot
        val pitchUsed = sentAimPitch ?: player.xRot
        val playerDir = Direction.fromYRot(yawUsed.toDouble())

        // Calculate vanilla getStateForPlacement expectations
        val vanillaHalf = if (clickedFace != Direction.DOWN && (clickedFace == Direction.UP || !(relHit.y > 0.5))) {
            Half.BOTTOM
        } else {
            Half.TOP
        }

        val entry = StairDebugEntry(
            id = nextId++,
            timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
            source = if (isManual) "MANUAL" else "PRINTER",
            item = BuiltInRegistries.ITEM.getKey(stack.item).toString(),
            hand = packet.hand.name,
            sequence = packet.sequence,
            placeAgainstPos = listOf(placeAgainst.x, placeAgainst.y, placeAgainst.z),
            placeAgainstBlock = level.getBlockState(placeAgainst).toString(),
            clickedFace = clickedFace.name,
            hitVec = listOf(hitVec.x, hitVec.y, hitVec.z),
            relativeHitVec = listOf(relHit.x, relHit.y, relHit.z),
            isInside = hit.isInside,
            targetPos = listOf(targetPos.x, targetPos.y, targetPos.z),
            playerPos = listOf(player.x, player.y, player.z),
            playerEyePos = listOf(eyePos.x, eyePos.y, eyePos.z),
            playerCameraYaw = player.yRot,
            playerCameraPitch = player.xRot,
            playerDirection = playerDir.name,
            sentAimYaw = yawUsed,
            sentAimPitch = pitchUsed,
            reachDistance = hitVec.distanceTo(eyePos),
            vanillaCalculatedFacing = playerDir.name,
            vanillaCalculatedHalf = vanillaHalf.name,
            schematicState = schemState?.toString(),
            clientStateBefore = level.getBlockState(targetPos).toString()
        )

        // Capture immediately after client use
        entry.clientStateAfter = level.getBlockState(targetPos).toString()

        activeByPos[targetPos] = entry
        activeBySeq[packet.sequence] = entry
        addAndPersist(entry)

        logger.info(
            """
            [STAIR DEBUG - ${entry.source}] Packet #${entry.sequence}
              Item: ${entry.item} (${entry.hand})
              PlaceAgainst: ${entry.placeAgainstPos} face=${entry.clickedFace} (${entry.placeAgainstBlock})
              RelHit: (${"%.4f".format(relHit.x)}, ${"%.4f".format(relHit.y)}, ${"%.4f".format(relHit.z)})
              AimAngles: yaw=${"%.2f".format(yawUsed)}°, pitch=${"%.2f".format(pitchUsed)}° (camYaw=${"%.2f".format(player.yRot)}°, camPitch=${"%.2f".format(player.xRot)}°)
              FacingDerived: ${playerDir.name} | HalfDerived: ${vanillaHalf.name}
              TargetPos: ${entry.targetPos}
              SchematicWanted: ${schemState?.toString() ?: "None"}
              ClientPredictedState: ${entry.clientStateAfter}
            """.trimIndent()
        )
    }

    fun onBlockUpdate(pos: BlockPos, state: BlockState) {
        val entry = activeByPos.remove(pos) ?: return
        entry.serverConfirmedState = state.toString()
        val match = if (entry.schematicState != null) {
            if (state.toString() == entry.schematicState) "MATCHES_SCHEMATIC" else "MISMATCH"
        } else "NO_SCHEMATIC"

        logger.info(
            "[STAIR DEBUG - ${entry.source} ACK] pos=$pos resultingState=$state ($match) for packet #${entry.sequence}"
        )
        persist()
    }

    fun onBlockChangedAck(sequence: Int) {
        val entry = activeBySeq.remove(sequence) ?: return
        if (entry.serverConfirmedState == null) {
            val mc = Minecraft.getInstance()
            val pos = BlockPos(entry.targetPos[0], entry.targetPos[1], entry.targetPos[2])
            entry.serverConfirmedState = mc.level?.getBlockState(pos)?.toString()
        }
        persist()
    }

    private fun addAndPersist(entry: StairDebugEntry) {
        entries.add(entry)
        while (entries.size > 50) {
            entries.removeAt(0)
        }
        persist()
    }

    private fun persist() {
        synchronized(lock) {
            try {
                debugFile.parentFile?.mkdirs()
                debugFile.writer().use { writer ->
                    gson.toJson(entries, writer)
                }
            } catch (e: Exception) {
                logger.error("Failed to write stair debug dump to ${debugFile.absolutePath}", e)
            }
        }
    }
}
