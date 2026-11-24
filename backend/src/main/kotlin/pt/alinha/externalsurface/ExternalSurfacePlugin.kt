package pt.alinha.externalsurface

import org.springframework.stereotype.Component
import pt.alinha.pluginapi.AlinhaPlugin
import pt.alinha.pluginapi.NavArea
import pt.alinha.pluginapi.NavEntry
import pt.alinha.pluginapi.PluginKind
import pt.alinha.pluginapi.PluginManifest

/** Registers the plugin in the core's Module Registry. Keep in sync with plugin.yaml. */
@Component
class ExternalSurfacePlugin : AlinhaPlugin {
    override val manifest = PluginManifest(
        id = ID,
        name = "External Surface",
        kind = PluginKind.PROVIDER,
        version = "0.1.0",
        publishes = setOf("EvidenceCollected"),
        consumes = setOf("JobCompleted", "JobFailed"),
        permissions = setOf(READ, MANAGE, ACTIVATE),
        rolePermissions = mapOf(
            // Only the organization's own responsible person vouches that the domain is theirs and may be checked.
            "responsible" to setOf(READ, MANAGE, ACTIVATE),
            "analyst" to setOf(READ),
            "consultant" to setOf(READ, MANAGE),
        ),
        nav = listOf(
            NavEntry("Superfície externa", PATH, NavArea.CLIENT, resource = RESOURCE, order = 71),
            NavEntry("Superfície externa", PATH, NavArea.CONSULTANT, resource = RESOURCE, order = 71),
        ),
    )

    companion object {
        const val ID = "external-surface"
        const val RESOURCE = "external-surface"
        const val READ = "external-surface:read"
        const val MANAGE = "external-surface:manage"
        const val ACTIVATE = "external-surface:activate"
        private const val PATH = "integrations/external-surface"
    }
}
