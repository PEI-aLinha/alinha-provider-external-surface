package pt.alinha.externalsurface.internal.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pt.alinha.core.access.RequiresPermission
import pt.alinha.externalsurface.internal.ActivationInput
import pt.alinha.externalsurface.internal.EvidenceView
import pt.alinha.externalsurface.internal.HostsInput
import pt.alinha.externalsurface.internal.MonitoringService
import pt.alinha.externalsurface.internal.MonitoringView

private const val RESOURCE = "external-surface"

/** The tenant always comes from the token (TenantContext), never from these requests. */
@RestController
@RequestMapping("/api/external-surface")
class ExternalSurfaceController(private val service: MonitoringService) {

    @RequiresPermission(RESOURCE, "read")
    @GetMapping("/monitoring")
    fun monitoring(): MonitoringView = service.view()

    /** 202 when the first check was queued, 200 when the checks were already active. */
    @RequiresPermission(RESOURCE, "activate")
    @PutMapping("/monitoring")
    fun activate(@RequestBody body: ActivationInput): ResponseEntity<MonitoringView> {
        val (view, started) = service.activate(body)
        return ResponseEntity.status(if (started) HttpStatus.ACCEPTED else HttpStatus.OK).body(view)
    }

    @RequiresPermission(RESOURCE, "manage")
    @DeleteMapping("/monitoring")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deactivate() = service.deactivate()

    @RequiresPermission(RESOURCE, "manage")
    @PutMapping("/hosts")
    fun hosts(@RequestBody body: HostsInput): MonitoringView = service.setHosts(body)

    @RequiresPermission(RESOURCE, "manage")
    @PostMapping("/collections")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun collect(): MonitoringView = service.collectNow()

    /** The results of the last completed check. */
    @RequiresPermission(RESOURCE, "read")
    @GetMapping("/evidence")
    fun evidence(): List<EvidenceView> = service.latestEvidence()
}
