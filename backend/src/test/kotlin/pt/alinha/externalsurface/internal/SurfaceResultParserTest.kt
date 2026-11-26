package pt.alinha.externalsurface.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import pt.alinha.externalsurface.internal.domain.Outcome
import pt.alinha.externalsurface.internal.domain.SurfaceCheck

private const val DOMAIN = "acme.pt"

fun check(name: String = "hsts", outcome: String = "PASS", details: Map<String, Any?> = emptyMap()) =
    mapOf("check" to name, "outcome" to outcome, "details" to details)

fun host(name: String = "www.acme.pt", role: String = "www", checks: List<Any?> = listOf(check())) =
    mapOf("host" to name, "role" to role, "checks" to checks)

fun result(
    hosts: List<Any?> = listOf(host()),
    domainChecks: List<Any?> = listOf(check("spf", details = mapOf("policy" to "fail"))),
    domain: Any? = DOMAIN,
    schema: Any? = "external-surface.result.v1",
    truncated: Any? = false,
): Map<String, Any?> =
    mapOf("schema" to schema, "domain" to domain, "truncated" to truncated, "domainChecks" to domainChecks, "hosts" to hosts)

class SurfaceResultParserTest {

    private fun parse(result: Map<String, Any?>?, extras: List<String> = listOf("loja.acme.pt")) =
        SurfaceResultParser.parse(result, DOMAIN, extras)

    @Test
    fun `reads the domain checks and every host's checks`() {
        val parsed = parse(
            result(
                hosts = listOf(
                    host(DOMAIN, "primary", listOf(check("security_txt", "FAIL", mapOf("reason" to "missing")))),
                    host(checks = listOf(check("tls_certificate", "PASS", mapOf("daysLeft" to 40, "expiresSoon" to false)))),
                    host("loja.acme.pt", "extra", listOf(check("hsts", "UNKNOWN", mapOf("reason" to "timeout")))),
                ),
            ),
        )

        assertThat(parsed.domain).isEqualTo(DOMAIN)
        assertThat(parsed.outcomes.map { Triple(it.host, it.check, it.outcome) }).containsExactly(
            Triple(DOMAIN, SurfaceCheck.SPF, Outcome.PASS),
            Triple(DOMAIN, SurfaceCheck.SECURITY_TXT, Outcome.FAIL),
            Triple("www.acme.pt", SurfaceCheck.TLS_CERTIFICATE, Outcome.PASS),
            Triple("loja.acme.pt", SurfaceCheck.HSTS, Outcome.UNKNOWN),
        )
        assertThat(parsed.outcomes[2].details).isEqualTo(mapOf("daysLeft" to 40, "expiresSoon" to false))
    }

    @Test
    fun `numbers come back from JSON as longs`() {
        val parsed = parse(result(hosts = listOf(host(checks = listOf(check("hsts", details = mapOf("maxAgeDays" to 365L)))))))
        assertThat(parsed.outcomes.last().details).isEqualTo(mapOf("maxAgeDays" to 365))
    }

    companion object {
        @JvmStatic
        fun invalid(): List<Map<String, Any?>?> = listOf(
            null,
            result(schema = "external-surface.result.v2"),
            result(domain = "evil.example"),
            result(truncated = "no"),
            result().plus("hosts" to "x"),
            result().plus("domainChecks" to null),
            result(hosts = listOf(host("evil.example", "extra"))),
            result(hosts = listOf(host("other.acme.pt", "extra"))),
            result(hosts = listOf(host("www.acme.pt", "primary"))),
            result(hosts = listOf(host(role = "admin"))),
            result(hosts = listOf("www.acme.pt")),
            result(hosts = listOf(host(), host())),
            result(hosts = List(13) { host() }),
            result(hosts = listOf(host(checks = listOf(check("spf"))))),
            result(domainChecks = listOf(check("hsts"))),
            result(hosts = listOf(host("loja.acme.pt", "extra", listOf(check("security_txt"))))),
            result(hosts = listOf(host(checks = listOf(check("port_scan"))))),
            result(hosts = listOf(host(checks = listOf(check(outcome = "MAYBE"))))),
            result(hosts = listOf(host(checks = listOf("hsts")))),
            result(hosts = listOf(host(checks = List(17) { check() }))),
            result(hosts = listOf(host(checks = listOf(check(details = mapOf("raw" to "max-age=31536000")))))),
            result(hosts = listOf(host(checks = listOf(check(details = mapOf("days" to -1)))))),
            result(hosts = listOf(host(checks = listOf(check(details = mapOf("days" to 1_000_000L)))))),
            result(hosts = listOf(host(checks = listOf(check(details = mapOf("bad key" to true)))))),
            result(hosts = listOf(host(checks = listOf(check(details = mapOf("nested" to mapOf("a" to 1))))))),
            result(hosts = listOf(host(checks = listOf(check(details = (1..9).associate { "k" + ('a' + it) to it }))))),
        )
    }

    @ParameterizedTest
    @MethodSource("invalid")
    fun `anything outside the expected shape or scope is refused as a whole`(result: Map<String, Any?>?) {
        assertThatThrownBy { parse(result) }.isInstanceOf(InvalidSurfaceResult::class.java)
    }
}
