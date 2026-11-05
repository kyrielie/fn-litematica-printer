package kyrielie.templatemod

import kyrielie.templatemod.client.config.TemplateModConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TemplateModTest {

    @Test
    fun configSanitize_resetsNonPositiveRadius() {
        val cfg = TemplateModConfig(scanRadius = -5)
        cfg.sanitize()
        assertEquals(10, cfg.scanRadius)
    }

    @Test
    fun configSanitize_preservesPositiveRadius() {
        val cfg = TemplateModConfig(scanRadius = 16)
        cfg.sanitize()
        assertEquals(16, cfg.scanRadius)
    }

    @Test
    fun testPlatform_pureLogicRunsCleanlyInJvm() {
        val values = listOf(1, 2, 3, 4, 5)
        val sum = values.sum()
        assertEquals(15, sum)
        assertTrue(sum > 10)
    }
}
