package pt.alinha.externalsurface

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ExternalSurfacePluginTest {
    @Test
    fun `manifest has the plugin id`() {
        assertEquals("external-surface", ExternalSurfacePlugin().manifest.id)
    }
}
