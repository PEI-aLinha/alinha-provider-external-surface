package pt.alinha.externalsurface.api

import pt.alinha.pluginapi.EvidenceStrength
import java.time.Instant
import java.util.UUID

/**
 * This plugin's «api» contract: the ONLY synchronous way for other plugins
 * to ask it for data. Everything under pt.alinha.externalsurface.internal is private.
 */
interface ExternalSurfaceApi {
    /** The evidence an `EvidenceCollected` event points to, in the current tenant; null if there is none. */
    fun evidence(evidenceId: UUID): SurfaceEvidence?
}

/**
 * One check of the organization's domain or of one of its web hosts, seen from the internet. [outcome] is `PASS` or
 * `FAIL` for every evidence announced by an event. [check] is one of `tls_version`, `tls_legacy`, `tls_certificate`,
 * `https_redirect`, `hsts`, `csp`, `content_type_options`, `frame_protection`, `referrer_policy`, `security_txt`,
 * `spf`, `dkim`, `dmarc`, `mta_sts`; [host] is the domain itself for the email checks.
 */
data class SurfaceEvidence(
    val id: UUID,
    val domain: String,
    val host: String,
    val check: String,
    val outcome: String,
    val controlIds: Set<String>,
    val strength: EvidenceStrength,
    val mappingVersion: Int,
    val collectedAt: Instant,
)
