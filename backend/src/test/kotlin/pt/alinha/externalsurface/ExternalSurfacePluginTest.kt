package pt.alinha.externalsurface

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class ExternalSurfacePluginTest {
    private val manifest = ExternalSurfacePlugin().manifest

    @Test
    fun `manifest has the plugin id and its permissions`() {
        assertThat(manifest.id).isEqualTo("external-surface")
        assertThat(manifest.permissions).containsExactlyInAnyOrder(
            "external-surface:read", "external-surface:manage", "external-surface:activate",
        )
    }

    @Test
    fun `only the responsible person may activate the checks`() {
        assertThat(manifest.rolePermissions.filterValues { "external-surface:activate" in it }.keys).containsExactly("responsible")
    }

    @Test
    fun `plugin yaml lists the same events and permissions`() {
        val yaml = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("plugin.yaml") }.first { it.exists() }.readText()
        (manifest.permissions + manifest.publishes + manifest.consumes).forEach { assertThat(yaml).contains(it) }
    }
}
