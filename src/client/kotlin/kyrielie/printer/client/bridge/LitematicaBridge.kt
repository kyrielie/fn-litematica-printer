package kyrielie.printer.client.bridge

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import org.slf4j.LoggerFactory

/**
 * Clean bridge to fi.dy.masa.litematica without hard compile-time crashes.
 * Dynamically queries Litematica's SchematicWorldHandler via reflection
 * if Litematica is loaded in the client environment.
 */
object LitematicaBridge {

    private val logger = LoggerFactory.getLogger("Printer/LitematicaBridge")

    @Volatile
    private var litematicaAvailable: Boolean? = null

    private var schematicWorldHandlerClass: Class<*>? = null
    private var getSchematicWorldMethod: java.lang.reflect.Method? = null

    private var dataManagerClass: Class<*>? = null
    private var getRenderLayerRangeMethod: java.lang.reflect.Method? = null
    private var isPositionWithinRangeMethod: java.lang.reflect.Method? = null

    fun isAvailable(): Boolean {
        if (litematicaAvailable == null) {
            try {
                schematicWorldHandlerClass = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler")
                getSchematicWorldMethod = schematicWorldHandlerClass?.getMethod("getSchematicWorld")

                dataManagerClass = Class.forName("fi.dy.masa.litematica.data.DataManager")
                getRenderLayerRangeMethod = dataManagerClass?.getMethod("getRenderLayerRange")

                val layerRangeClass = Class.forName("fi.dy.masa.litematica.util.LayerRange")
                isPositionWithinRangeMethod = layerRangeClass.getMethod("isPositionWithinRange", BlockPos::class.java)

                litematicaAvailable = true
                logger.info("LitematicaBridge: Successfully hooked into active Litematica instance.")
            } catch (t: Throwable) {
                litematicaAvailable = false
                logger.info("LitematicaBridge: Litematica classes not detected in runtime classpath.")
            }
        }
        return litematicaAvailable == true
    }

    /**
     * Returns the active schematic world instance (which implements Level) or null.
     */
    fun getSchematicWorld(): Level? {
        if (!isAvailable()) return null
        return try {
            getSchematicWorldMethod?.invoke(null) as? Level
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Queries whether [pos] is within Litematica's currently active render layer range.
     */
    fun isPositionWithinRenderLayers(pos: BlockPos): Boolean {
        if (!isAvailable()) return true
        return try {
            val range = getRenderLayerRangeMethod?.invoke(null) ?: return true
            isPositionWithinRangeMethod?.invoke(range, pos) as? Boolean ?: true
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Gets the expected BlockState from the active schematic world at [pos].
     */
    fun getSchematicBlockState(pos: BlockPos): BlockState? {
        val world = getSchematicWorld() ?: return null
        return world.getBlockState(pos)
    }
}
