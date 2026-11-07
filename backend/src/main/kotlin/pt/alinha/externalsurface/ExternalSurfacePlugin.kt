package pt.alinha.externalsurface

import org.springframework.stereotype.Component
import pt.alinha.pluginapi.AlinhaPlugin
import pt.alinha.pluginapi.PluginKind
import pt.alinha.pluginapi.PluginManifest

/** Registers the plugin in the core's Module Registry. Keep in sync with plugin.yaml. */
@Component
class ExternalSurfacePlugin : AlinhaPlugin {
    override val manifest = PluginManifest(
        id = "external-surface",
        name = "External Surface",
        kind = PluginKind.PROVIDER,
        version = "0.1.0",
        publishes = setOf(),
        consumes = setOf(),
        permissions = setOf(),
    )
}
