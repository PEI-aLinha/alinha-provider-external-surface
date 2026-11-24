package pt.alinha.externalsurface.internal

import pt.alinha.externalsurface.internal.domain.MonitoringStatus
import pt.alinha.externalsurface.internal.domain.Outcome
import pt.alinha.pluginapi.EvidenceStrength
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** What the page shows about the checks (contracts/openapi/alinha.yaml, `ExternalSurfaceMonitoring`). */
data class MonitoringView(
    /** From the organization profile; null when it has none. */
    val domain: String?,
    val active: Boolean,
    val activatedAt: Instant?,
    val status: MonitoringStatus?,
    /** A job error code (`domain_not_found`, ...); the page turns it into a sentence. */
    val lastError: String?,
    val lastCollectedAt: Instant?,
    val collectingSince: Instant?,
    val hosts: List<HostView>,
)

/** An extra host. [inScope]: still a subdomain of the profile's domain, so it is checked. */
data class HostView(val hostname: String, val inScope: Boolean)

data class EvidenceView(
    val id: UUID,
    val host: String,
    val check: String,
    val outcome: Outcome,
    val details: Map<String, Any?>,
    val controlIds: List<String>,
    val informational: Boolean,
    val strength: EvidenceStrength,
    val collectedAt: Instant,
)

/** `PUT /api/external-surface/monitoring`. */
data class ActivationInput(val authorized: Boolean? = null)

/** `PUT /api/external-surface/hosts`. */
data class HostsInput(val hosts: List<String?>? = null)

/** An invalid request field; [field] is its JSON path (`hosts[2]`). */
class InvalidInput(val field: String, val reason: Reason) : RuntimeException(field) {
    enum class Reason { NOT_A_HOST, OUTSIDE_DOMAIN, TOO_MANY }
}

class AuthorizationRequired : RuntimeException()
class DomainMissing : RuntimeException()
class NotActive : RuntimeException()
class CollectionRunning : RuntimeException()
class TooSoon(val retryAfter: Duration) : RuntimeException()
