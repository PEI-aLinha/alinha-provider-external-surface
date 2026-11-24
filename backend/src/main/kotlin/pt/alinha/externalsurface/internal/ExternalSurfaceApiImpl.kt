package pt.alinha.externalsurface.internal

import org.springframework.stereotype.Component
import pt.alinha.externalsurface.api.ExternalSurfaceApi
import pt.alinha.externalsurface.api.SurfaceEvidence
import java.util.UUID

@Component
class ExternalSurfaceApiImpl(private val service: MonitoringService) : ExternalSurfaceApi {
    override fun evidence(evidenceId: UUID): SurfaceEvidence? = service.evidence(evidenceId)?.let {
        SurfaceEvidence(
            id = it.id,
            domain = it.domain,
            host = it.host,
            check = it.check.id,
            outcome = it.outcome.name,
            controlIds = it.controlIds,
            strength = it.strength,
            mappingVersion = it.mappingVersion,
            collectedAt = it.collectedAt,
        )
    }
}
