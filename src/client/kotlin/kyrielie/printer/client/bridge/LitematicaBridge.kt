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
    private var isPositionWithinRangeIntMethod: java.lang.reflect.Method? = null
    private var isPositionWithinRangeBlockPosMethod: java.lang.reflect.Method? = null
    private var lastLayerErrorLogTime: Long = 0L

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
        "fi.dy.masa.malilib.util.position.LayerRange",
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
                    // Prefer isPositionWithinRange(int, int, int) for zero boxing and zero overload ambiguity
                    val intMethod = cls.methods.firstOrNull {
                        it.name == "isPositionWithinRange" &&
                        it.parameterTypes.size == 3 &&
                        it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        it.parameterTypes[1] == Int::class.javaPrimitiveType &&
                        it.parameterTypes[2] == Int::class.javaPrimitiveType
                    }
                    val blockPosMethod = cls.methods.firstOrNull {
                        it.name == "isPositionWithinRange" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0].name.endsWith("BlockPos")
                    }

                    if (intMethod != null || blockPosMethod != null) {
                        isPositionWithinRangeIntMethod = intMethod
                        isPositionWithinRangeBlockPosMethod = blockPosMethod
                        logger.info("[Printer] Found LayerRange at $className (intMethod=${intMethod != null}, blockPosMethod=${blockPosMethod != null})")
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

    private var cachedRenderLayerRange: Any? = null
    private var isAllLayersActive: Boolean = true
    private var lastRangeQueryTime: Long = 0L

    fun isPositionWithinRenderLayers(pos: BlockPos): Boolean {
        if (!isAvailable()) return true
        return try {
            val now = System.currentTimeMillis()
            val range = if (now - lastRangeQueryTime < 50L && cachedRenderLayerRange != null) {
                cachedRenderLayerRange
            } else {
                val fetched = getRenderLayerRangeMethod?.invoke(null)
                cachedRenderLayerRange = fetched
                lastRangeQueryTime = now
                if (fetched != null) {
                    try {
                        val modeMethod = fetched.javaClass.methods.firstOrNull { it.name == "getLayerMode" && it.parameterCount == 0 }
                        val modeObj = modeMethod?.invoke(fetched)
                        val modeName = when (modeObj) {
                            is Enum<*> -> modeObj.name
                            else -> modeObj?.toString()
                        }
                        isAllLayersActive = modeName == null || modeName.equals("ALL", ignoreCase = true)
                    } catch (_: Exception) {
                        isAllLayersActive = false
                    }
                } else {
                    isAllLayersActive = true
                }
                fetched
            } ?: return true

            // G-11: Fast zero-reflection path when full layer rendering is active (ALL mode)
            if (isAllLayersActive) return true

            // Invoke (int, int, int) overload if available, otherwise fallback to (BlockPos)
            val intMethod = isPositionWithinRangeIntMethod
            if (intMethod != null) {
                return intMethod.invoke(range, pos.x, pos.y, pos.z) as? Boolean ?: true
            }

            val bpMethod = isPositionWithinRangeBlockPosMethod
            if (bpMethod != null) {
                return bpMethod.invoke(range, pos) as? Boolean ?: true
            }

            // Dynamic runtime resolution if initial candidates were somehow missed
            val runtimeIntMethod = range.javaClass.methods.firstOrNull {
                it.name == "isPositionWithinRange" &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            if (runtimeIntMethod != null) {
                isPositionWithinRangeIntMethod = runtimeIntMethod
                return runtimeIntMethod.invoke(range, pos.x, pos.y, pos.z) as? Boolean ?: true
            }

            val runtimeBpMethod = range.javaClass.methods.firstOrNull {
                it.name == "isPositionWithinRange" &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0].name.endsWith("BlockPos")
            }
            if (runtimeBpMethod != null) {
                isPositionWithinRangeBlockPosMethod = runtimeBpMethod
                return runtimeBpMethod.invoke(range, pos) as? Boolean ?: true
            }

            true
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastLayerErrorLogTime > 5000L) {
                lastLayerErrorLogTime = now
                logger.warn("[Printer] Layer range evaluation failed: ${e.message}", e)
            }
            // If full layer mode is NOT active, do NOT allow placement through on error!
            if (!isAllLayersActive) false else true
        }
    }

    fun getSchematicBlockState(pos: BlockPos): BlockState? {
        val world = getSchematicWorld() ?: return null
        return world.getBlockState(pos)
    }

    fun getLayerMode(): String? {
        if (!isAvailable()) return null
        return try {
            val fetched = getRenderLayerRangeMethod?.invoke(null) ?: return null
            val modeMethod = fetched.javaClass.methods.firstOrNull { it.name == "getLayerMode" && it.parameterCount == 0 }
            val modeObj = modeMethod?.invoke(fetched)
            when (modeObj) {
                is Enum<*> -> modeObj.name
                else -> modeObj?.toString()
            }
        } catch (_: Exception) {
            null
        }
    }
}
