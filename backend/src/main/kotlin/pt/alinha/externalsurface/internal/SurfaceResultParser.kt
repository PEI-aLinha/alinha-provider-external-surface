package pt.alinha.externalsurface.internal

import pt.alinha.externalsurface.internal.domain.CheckKind
import pt.alinha.externalsurface.internal.domain.HostNames
import pt.alinha.externalsurface.internal.domain.HostRole
import pt.alinha.externalsurface.internal.domain.Outcome
import pt.alinha.externalsurface.internal.domain.SurfaceCheck

/** One validated (host, check) outcome from the worker. [details] holds only small scalars. */
data class CheckOutcome(
    val host: String,
    val check: SurfaceCheck,
    val outcome: Outcome,
    val details: Map<String, Any>,
)

data class SurfaceResult(val domain: String, val outcomes: List<CheckOutcome>)

class InvalidSurfaceResult(message: String) : RuntimeException(message)

/**
 * Reads the worker's `external-surface.result.v1` (worker/src/alinha_externalsurface/collector.py). The result is
 * untrusted data (docs/jobs.md): anything outside the expected shape, or about a host the job did not ask for, is
 * refused as a whole, and only known checks and small scalars are kept. Nothing in it is ever an instruction.
 */
object SurfaceResultParser {
    const val SCHEMA = "external-surface.result.v1"
    private const val MAX_DETAILS = 8
    private const val MAX_NUMBER = 100_000
    private val DETAIL_KEY = Regex("^[a-zA-Z]{1,40}$")
    private val DETAIL_TEXT = Regex("^[a-z0-9_]{1,40}$")

    fun parse(result: Map<String, Any?>?, domain: String, extraHosts: List<String>): SurfaceResult {
        if (result == null) fail("no result")
        if (result["schema"] != SCHEMA) fail("unknown result schema")
        if (result["domain"] != domain) fail("another domain")
        if (result["truncated"] !is Boolean) fail("truncated is not a boolean")
        val domainChecks = result["domainChecks"] as? List<*> ?: fail("domainChecks is not a list")
        val hosts = result["hosts"] as? List<*> ?: fail("hosts is not a list")
        if (hosts.size > HostNames.MAX_HOSTS) fail("too many hosts")

        val allowed = mapOf(domain to HostRole.PRIMARY, HostNames.www(domain) to HostRole.WWW) +
            extraHosts.associateWith { HostRole.EXTRA }
        val outcomes = checks(domainChecks, domain, HostRole.PRIMARY, CheckKind.DOMAIN) +
            hosts.flatMap { host(it, allowed) }
        if (outcomes.distinctBy { it.host to it.check }.size != outcomes.size) fail("a host or check is repeated")
        return SurfaceResult(domain, outcomes)
    }

    private fun host(value: Any?, allowed: Map<String, HostRole>): List<CheckOutcome> {
        val entry = value as? Map<*, *> ?: fail("host entry is not an object")
        val name = entry["host"] as? String ?: fail("host is not a string")
        val role = (entry["role"] as? String)?.let(HostRole::of) ?: fail("unknown role")
        // A host the job did not ask for is not this tenant's to report on.
        if (allowed[name] != role) fail("host outside the job's scope")
        val checks = entry["checks"] as? List<*> ?: fail("checks is not a list")
        return checks(checks, name, role, CheckKind.WEB)
    }

    private fun checks(values: List<*>, host: String, role: HostRole, kind: CheckKind): List<CheckOutcome> {
        if (values.size > SurfaceCheck.entries.size) fail("too many checks")
        return values.map { check(it, host, role, kind) }
    }

    private fun check(value: Any?, host: String, role: HostRole, kind: CheckKind): CheckOutcome {
        val entry = value as? Map<*, *> ?: fail("check is not an object")
        val check = (entry["check"] as? String)?.let(SurfaceCheck::of) ?: fail("unknown check")
        if (check.kind != kind) fail("check in the wrong list")
        if (check.mainSiteOnly && role == HostRole.EXTRA) fail("main-site check on an extra host")
        val outcome = (entry["outcome"] as? String)?.let { runCatching { Outcome.valueOf(it) }.getOrNull() }
            ?: fail("unknown outcome")
        val details = entry["details"] as? Map<*, *> ?: emptyMap<Any, Any>()
        if (details.size > MAX_DETAILS) fail("too many details")
        return CheckOutcome(host, check, outcome, details.entries.associate { (key, detail) -> detail(key, detail) })
    }

    private fun detail(key: Any?, value: Any?): Pair<String, Any> {
        val name = (key as? String)?.takeIf { DETAIL_KEY.matches(it) } ?: fail("invalid detail key")
        val clean: Any = when (value) {
            is Boolean -> value
            is Int -> value.takeIf { it in 0..MAX_NUMBER } ?: fail("detail out of range")
            is Long -> value.takeIf { it in 0..MAX_NUMBER }?.toInt() ?: fail("detail out of range")
            is String -> value.takeIf { DETAIL_TEXT.matches(it) } ?: fail("invalid detail text")
            else -> fail("invalid detail value")
        }
        return name to clean
    }

    private fun fail(reason: String): Nothing = throw InvalidSurfaceResult(reason)
}
