package pt.alinha.externalsurface.internal.web

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/external-surface")
class ExternalSurfaceController {

    /** Walking skeleton: prova que o plugin está carregado de ponta a ponta. */
    @GetMapping("/ping")
    fun ping() = mapOf("plugin" to "external-surface", "status" to "ok")
}
