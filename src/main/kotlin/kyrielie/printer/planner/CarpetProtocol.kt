package kyrielie.printer.planner

/**
 * Pure helper for Carpet Extra's accurateBlockPlacement protocol (v2 and v3).
 * Encodes desired block facing and properties into click coordinate fractions.
 */
object CarpetProtocol {

    /**
     * Minecraft Direction enum IDs:
     * DOWN=0, UP=1, NORTH=2, SOUTH=3, WEST=4, EAST=5
     */
    fun getFacingId(facing: String): Int = when (facing.lowercase()) {
        "down" -> 0
        "up" -> 1
        "north" -> 2
        "south" -> 3
        "west" -> 4
        "east" -> 5
        else -> -1
    }

    /**
     * Applies Carpet accurateBlockPlacement protocol v2 encoding.
     * Rule: hitX is modified as: hitX = blockX + rawHitX + 2 + (2 * facingId)
     */
    fun applyCarpetProtocolV2(blockX: Int, rawHitX: Double, facingId: Int): Double {
        if (facingId < 0) return blockX + rawHitX
        return blockX.toDouble() + rawHitX + 2.0 + (2.0 * facingId)
    }

    /**
     * Decodes Carpet accurateBlockPlacement protocol v2 hit vector to extract facingId.
     */
    fun decodeCarpetProtocolV2Facing(blockX: Int, hitX: Double): Int {
        val offset = hitX - blockX.toDouble()
        if (offset < 2.0) return -1
        val code = ((offset - 2.0) / 2.0).toInt()
        return if (code in 0..5) code else -1
    }

    /**
     * Applies Carpet protocol v3 encoding: supports facing + additional property (e.g. repeater delay or half).
     * offset = 2.0 + 2.0 * (facingId + 16 * propertyValue)
     */
    fun applyCarpetProtocolV3(blockX: Int, rawHitX: Double, facingId: Int, propertyValue: Int): Double {
        if (facingId < 0) return blockX + rawHitX
        val code = facingId + (16 * propertyValue)
        return blockX.toDouble() + rawHitX + 2.0 + (2.0 * code)
    }
}
