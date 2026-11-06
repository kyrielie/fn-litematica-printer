package kyrielie.printer.client.bridge

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import org.slf4j.LoggerFactory

/**
 * Clean bridge to Litematica supporting both Masa and Sakura-Ryoko / modern Fabric package hierarchies.
 */
object LitematicaBridge {

    val logger = LoggerFactory.getLogger("Printer/LitematicaBridge")

    @Volatile
    private var litematicaAvailable: Boolean? = null

    private var schematicWorldHandlerClass: Class<*>? = null
    private var getSchematicWorldMethod: java.lang.reflect.Method? = null

    private var dataManagerClass: Class<*>? = null
    private var getRenderLayerRangeMethod: java.lang.reflect.Method? = null
    private var isPositionWithinRangeMethod: java.lang.reflect.Method? = null

    // Fallback packages candidate list
    private val SCHEMATIC_HANDLER_CANDIDATES = listOf(
        "fi.dy.masa.litematica.world.SchematicWorldHandler",
        "fi.dy.masa.litematica.schematic.util.SchematicWorldHandler",
        "litematica.world.SchematicWorldHandler"
    )

    private val DATA_MANAGER_CANDIDATES = listOf(
        "fi.dy.masa.litematica.data.DataManager",
        "litematica.data.DataManager"
    )

    private val LAYER_RANGE_CANDIDATES = listOf(
        "fi.dy.masa.malilib.util.LayerRange",
        "fi.dy.masa.litematica.util.LayerRange",
        "litematica.util.LayerRange"
    )

    fun isAvailable(): Boolean {
        if (litematicaAvailable == null) {
            // 1. Try resolving SchematicWorldHandler
            for (className in SCHEMATIC_HANDLER_CANDIDATES) {
                try {
                    val cls = Class.forName(className)
                    val method = cls.methods.firstOrNull { it.name == "getSchematicWorld" && it.parameterCount == 0 }
                    if (method != null) {
                        schematicWorldHandlerClass = cls
                        getSchematicWorldMethod = method
                        logger.info("[Printer] Found SchematicWorldHandler at $className")
                        break
                    }
                } catch (_: ClassNotFoundException) {}
            }

            // 2. Try resolving DataManager
            for (className in DATA_MANAGER_CANDIDATES) {
                try {
                    val cls = Class.forName(className)
                    val method = cls.methods.firstOrNull { it.name == "getRenderLayerRange" && it.parameterCount == 0 }
                    if (method != null) {
                        dataManagerClass = cls
                        getRenderLayerRangeMethod = method
                        logger.info("[Printer] Found DataManager at $className")
                        break
                    }
                } catch (_: ClassNotFoundException) {}
            }

            // 3. Try resolving LayerRange
            for (className in LAYER_RANGE_CANDIDATES) {
                try {
                    val cls = Class.forName(className)
                    val method = cls.methods.firstOrNull { it.name == "isPositionWithinRange" && it.parameterCount == 1 }
                    if (method != null) {
                        isPositionWithinRangeMethod = method
                        logger.info("[Printer] Found LayerRange at $className")
                        break
                    }
                } catch (_: ClassNotFoundException) {}
            }

            litematicaAvailable = (getSchematicWorldMethod != null)
            if (litematicaAvailable == true) {
                logger.info("[Printer] Successfully linked to active Litematica instance!")
            } else {
                logger.warn("[Printer] Could not locate Litematica classes in current classpath!")
            }
        }
        return litematicaAvailable == true
    }

    fun getSchematicWorld(): Level? {
        if (!isAvailable()) return null
        return try {
            val world = getSchematicWorldMethod?.invoke(null) as? Level
            if (world == null) {
                logger.warn("[Printer] SchematicWorldHandler.getSchematicWorld() returned null (is a placement created?)")
            }
            world
        } catch (e: Exception) {
            logger.error("[Printer] Exception invoking getSchematicWorld: ${e.message}", e)
            null
        }
    }

    fun isPositionWithinRenderLayers(pos: BlockPos): Boolean {
        if (!isAvailable()) return true
        return try {
            val range = getRenderLayerRangeMethod?.invoke(null) ?: return true
            var method = isPositionWithinRangeMethod
            if (method == null) {
                // Dynamically resolve on runtime instance class (G-6)
                method = range.javaClass.methods.firstOrNull {
                    it.name == "isPositionWithinRange" && it.parameterCount == 1
                }
                isPositionWithinRangeMethod = method
            }
            method?.invoke(range, pos) as? Boolean ?: true
        } catch (_: Exception) {
            true
        }
    }

    fun getSchematicBlockState(pos: BlockPos): BlockState? {
        val world = getSchematicWorld() ?: return null
        return world.getBlockState(pos)
    }
}
