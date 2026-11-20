package pt.alinha.externalsurface.internal.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pt.alinha.core.access.RequiresPermission

@RestController
@RequestMapping("/api/external-surface")
class ExternalSurfaceController {

    /** Walking skeleton: proves the plugin is wired end to end. Every role may call it (`plugins:read`). */
    @RequiresPermission("plugins")
    @GetMapping("/ping")
    fun ping() = mapOf("plugin" to "external-surface", "status" to "ok")
}
