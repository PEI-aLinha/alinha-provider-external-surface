package pt.alinha.externalsurface.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.web.context.request.RequestContextHolder
import pt.alinha.core.jobs.JobCompleted
import pt.alinha.core.jobs.JobError
import pt.alinha.core.jobs.JobFailed
import pt.alinha.core.jobs.JobResult
import pt.alinha.core.jobs.JobStatus
import pt.alinha.core.tenant.TenantContext
import pt.alinha.externalsurface.api.ExternalSurfaceApi
import pt.alinha.pluginapi.EvidenceCollected
import pt.alinha.pluginapi.EvidenceOutcome
import pt.alinha.pluginapi.EvidenceStrength
import pt.alinha.pluginapi.TenantId
import java.time.Instant
import java.util.UUID

private const val BASE = "/api/external-surface"
private const val DOMAIN = "acme.pt"

@SpringBootTest(classes = [ExternalSurfaceTestApplication::class])
@AutoConfigureMockMvc
@RecordApplicationEvents
class ExternalSurfaceIntegrationTest {
    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) = ExternalSurfaceDatabase.register(registry)
    }

    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var jobs: FakeJobDispatcher
    @Autowired private lateinit var results: FakeJobResults
    @Autowired private lateinit var organization: FakeOrganizationApi
    @Autowired private lateinit var listener: SurfaceResultListener
    @Autowired private lateinit var scheduler: SurfaceScheduler
    @Autowired private lateinit var api: ExternalSurfaceApi
    @Autowired private lateinit var tenants: TenantContext
    @Autowired private lateinit var events: ApplicationEvents

    private lateinit var tenant: String

    @BeforeEach
    fun newTenant() {
        tenant = UUID.randomUUID().toString()
        organization.domains[tenantId()] = DOMAIN
    }

    // -- state and activation -----------------------------------------------------------------------------------

    @Test
    fun `before activation the page sees the profile's domain and nothing running`() {
        call("analyst") { mvc.get("$BASE/monitoring") { it() } }.andExpect {
            status { isOk() }
            jsonPath("$.domain") { value(DOMAIN) }
            jsonPath("$.active") { value(false) }
            jsonPath("$.status") { value(null) }
            jsonPath("$.hosts.length()") { value(0) }
        }
        call("responsible") { mvc.get("$BASE/evidence") { it() } }.andExpect { jsonPath("$.length()") { value(0) } }
    }

    @Test
    fun `activating needs the authorization, starts a check and schedules the daily one`() {
        activate(authorized = false).andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("AUTHORIZATION_REQUIRED") }
            jsonPath("$.errors[0].field") { value("authorized") }
        }
        assertThat(myJobs()).isEmpty()

        activate().andExpect {
            status { isAccepted() }
            jsonPath("$.active") { value(true) }
            jsonPath("$.status") { value("PENDING") }
        }

        val job = lastJob()
        assertThat(job.request.type).isEqualTo("evidence.collect")
        assertThat(job.request.requestedBy).isEqualTo("external-surface")
        assertThat(job.request.payload).isEqualTo(
            mapOf(
                "provider" to "external-surface",
                "connectionRef" to "providers/external-surface",
                "scope" to mapOf("domain" to DOMAIN, "hosts" to emptyList<String>()),
            ),
        )
        assertThat(audit("external-surface.activated")).singleElement().asString()
            .contains("\"authorized\": true").contains(DOMAIN)
        assertThat(scheduleCount()).isOne()
    }

    @Test
    fun `activating twice starts nothing new`() {
        activate()
        activate().andExpect { status { isOk() } }
        assertThat(myJobs()).hasSize(1)
        assertThat(audit("external-surface.activated")).hasSize(1)
    }

    @Test
    fun `without a domain in the profile nothing can be activated or checked`() {
        organization.domains.remove(tenantId())

        call("responsible") { mvc.get("$BASE/monitoring") { it() } }.andExpect { jsonPath("$.domain") { value(null) } }
        activate().andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.code") { value("DOMAIN_MISSING") }
        }
        assertThat(myJobs()).isEmpty()
    }

    @Test
    fun `only the responsible person may activate, the consultant may manage, the analyst only reads`() {
        activate(role = "analyst").andExpect { status { isForbidden() } }
        activate(role = "consultant").andExpect { status { isForbidden() } }
        hosts("loja.acme.pt", role = "analyst").andExpect { status { isForbidden() } }
        hosts("loja.acme.pt", role = "consultant").andExpect { status { isOk() } }
        activate().andExpect { status { isAccepted() } }
        call("analyst") { mvc.post("$BASE/collections") { it() } }.andExpect { status { isForbidden() } }
        call("consultant") { mvc.get("$BASE/monitoring") { it() } }.andExpect { jsonPath("$.active") { value(true) } }
    }

    @Test
    fun `a role without the plugin's permissions sees nothing`() {
        call("registrant") { mvc.get("$BASE/monitoring") { it() } }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `deactivating stops the schedule and keeps the results`() {
        activate()
        complete(lastJob().id, result())

        call("consultant") { mvc.delete("$BASE/monitoring") { it() } }.andExpect { status { isNoContent() } }

        assertThat(scheduleCount()).isZero()
        call("responsible") { mvc.get("$BASE/monitoring") { it() } }.andExpect { jsonPath("$.active") { value(false) } }
        call("responsible") { mvc.get("$BASE/evidence") { it() } }.andExpect { jsonPath("$.length()") { value(3) } }
        assertThat(audit("external-surface.deactivated")).hasSize(1)
        call("responsible") { mvc.delete("$BASE/monitoring") { it() } }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_ACTIVE") }
        }
    }

    // -- extra hosts --------------------------------------------------------------------------------------------

    @Test
    fun `extra hosts are normalised, kept inside the domain and sent with the next check`() {
        hosts("https://Loja.Acme.pt/", "www.acme.pt", "portal.acme.pt", "loja.acme.pt").andExpect {
            status { isOk() }
            jsonPath("$.hosts[0].hostname") { value("loja.acme.pt") }
            jsonPath("$.hosts[1].hostname") { value("portal.acme.pt") }
            jsonPath("$.hosts.length()") { value(2) }
            jsonPath("$.hosts[0].inScope") { value(true) }
        }
        assertThat(audit("external-surface.hosts-changed")).singleElement().asString().contains("loja.acme.pt")

        activate()

        assertThat(lastJob().request.payload["scope"]).isEqualTo(
            mapOf("domain" to DOMAIN, "hosts" to listOf("loja.acme.pt", "portal.acme.pt")),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["10.0.0.1", "acme.pt", "notacme.pt", "evil.example", "acme.pt.evil.example", "a b.acme.pt"])
    fun `a host outside the domain, or not a host, is refused on its field`(host: String) {
        hosts("loja.acme.pt", host).andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.errors[0].field") { value("hosts[1]") }
        }
    }

    @Test
    fun `at most ten extra hosts`() {
        hosts(*Array(11) { "h$it.acme.pt" }).andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.errors[0].field") { value("hosts") }
        }
    }

    @Test
    fun `when the profile's domain changes, the old hosts are kept but not checked`() {
        hosts("loja.acme.pt")
        organization.domains[tenantId()] = "acme.com"

        call("responsible") { mvc.get("$BASE/monitoring") { it() } }.andExpect {
            jsonPath("$.hosts[0].inScope") { value(false) }
        }
        activate()
        assertThat(lastJob().request.payload["scope"]).isEqualTo(mapOf("domain" to "acme.com", "hosts" to emptyList<String>()))
    }

    // -- results ------------------------------------------------------------------------------------------------

    @Test
    fun `a completed check stores the results and announces only what is evidence for a control`() {
        activate()

        complete(lastJob().id, result())

        call("responsible") { mvc.get("$BASE/monitoring") { it() } }.andExpect {
            jsonPath("$.status") { value("ACTIVE") }
            jsonPath("$.lastCollectedAt") { exists() }
        }
        call("analyst") { mvc.get("$BASE/evidence") { it() } }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(3) }
            jsonPath("$[0].host") { value(DOMAIN) }
            jsonPath("$[0].check") { value("dnssec") }
            jsonPath("$[0].informational") { value(true) }
            jsonPath("$[0].controlIds.length()") { value(0) }
            jsonPath("$[1].check") { value("spf") }
            jsonPath("$[1].details.policy") { value("fail") }
            jsonPath("$[2].host") { value("www.acme.pt") }
            jsonPath("$[2].check") { value("tls_certificate") }
            jsonPath("$[2].outcome") { value("FAIL") }
            jsonPath("$[2].strength") { value("TECHNICALLY_VERIFIED") }
        }
        val announced = announcedFor(tenant)
        assertThat(announced).hasSize(2)
        assertThat(announced.map { it.outcome }).containsExactlyInAnyOrder(EvidenceOutcome.SATISFIED, EvidenceOutcome.NOT_SATISFIED)
        assertThat(announced).allSatisfy { assertThat(it.provider).isEqualTo("external-surface") }
        val certificate = announced.single { it.outcome == EvidenceOutcome.NOT_SATISFIED }
        val read = tenants.runAs(TenantId(UUID.fromString(tenant))) { api.evidence(certificate.evidenceId) }
        assertThat(read?.check).isEqualTo("tls_certificate")
        assertThat(read?.controlIds).containsExactly("PR.SD-2-B")
        assertThat(audit("external-surface.evidence-collected")).singleElement().asString()
            .contains("\"pass\": 2").contains("\"fail\": 1")
    }

    @Test
    fun `a replayed result changes nothing`() {
        activate()
        val job = lastJob().id
        complete(job, result())
        complete(job, result())

        assertThat(countEvidence()).isEqualTo(3)
        assertThat(announcedFor(tenant)).hasSize(2)
    }

    @Test
    fun `a result about another domain or host is refused as a whole`() {
        activate()
        complete(lastJob().id, result(domain = "evil.example"))

        call("responsible") { mvc.get("$BASE/monitoring") { it() } }.andExpect {
            jsonPath("$.status") { value("FAILED") }
            jsonPath("$.lastError") { value("invalid_result") }
        }
        assertThat(countEvidence()).isZero()
    }

    @Test
    fun `another tenant sees nothing and cannot apply this tenant's job`() {
        activate()
        val job = lastJob().id
        val mine = tenant

        newTenant()
        call("responsible") { mvc.get("$BASE/monitoring") { it() } }.andExpect { jsonPath("$.active") { value(false) } }
        complete(job, result())
        assertThat(countEvidence()).isZero()
        call("consultant") { mvc.get("$BASE/monitoring") { header("X-Alinha-Tenant", mine); it() } }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `another plugin's job is ignored`() {
        activate()
        val job = lastJob().id
        put(job, result())
        outsideRequest { listener.on(completed(job, requestedBy = "code-repository")) }

        assertThat(countEvidence()).isZero()
    }

    @Test
    fun `a failed job keeps the worker's code, or a generic one when it is not a plain code`() {
        activate()
        fail(lastJob().id, "domain_not_found")
        call("responsible") { mvc.get("$BASE/monitoring") { it() } }.andExpect { jsonPath("$.lastError") { value("domain_not_found") } }

        jdbc.update("update externalsurface.surface set collecting_since = now() - interval '1 hour' where tenant_id = ?::uuid", tenant)
        call("responsible") { mvc.post("$BASE/collections") { it() } }.andExpect { status { isAccepted() } }
        fail(lastJob().id, "Not A Code <script>")
        call("responsible") { mvc.get("$BASE/monitoring") { it() } }.andExpect { jsonPath("$.lastError") { value("collector_error") } }
    }

    // -- check now and the schedule -----------------------------------------------------------------------------

    @Test
    fun `check now refuses while one runs and right after the last one`() {
        call("responsible") { mvc.post("$BASE/collections") { it() } }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOT_ACTIVE") }
        }
        activate()
        call("responsible") { mvc.post("$BASE/collections") { it() } }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("COLLECTION_RUNNING") }
        }
        complete(lastJob().id, result())
        call("responsible") { mvc.post("$BASE/collections") { it() } }.andExpect {
            status { isTooManyRequests() }
            jsonPath("$.code") { value("TOO_SOON") }
            header { exists("Retry-After") }
        }
    }

    @Test
    fun `the daily check of an active tenant is continuously verified`() {
        activate()
        complete(lastJob().id, result())
        makeDue()

        outsideRequest { scheduler.runDue() }

        assertThat(audit("external-surface.collection-scheduled")).hasSize(1)
        assertThat(nextRunAt()).isAfter(Instant.now().plusSeconds(23 * 3600))
        complete(lastJob().id, result())
        assertThat(strengths().last()).isEqualTo(EvidenceStrength.CONTINUOUSLY_VERIFIED)
    }

    @Test
    fun `a daily check after a long gap is only technically verified`() {
        activate()
        complete(lastJob().id, result())
        jdbc.update("update externalsurface.surface set last_collected_at = now() - interval '3 days' where tenant_id = ?::uuid", tenant)
        makeDue()

        outsideRequest { scheduler.runDue() }
        complete(lastJob().id, result())

        assertThat(strengths().last()).isEqualTo(EvidenceStrength.TECHNICALLY_VERIFIED)
    }

    @Test
    fun `the schedule tries again soon when a check is running`() {
        activate()
        val running = lastJob()
        makeDue()

        outsideRequest { scheduler.runDue() }

        assertThat(lastJob()).isEqualTo(running)
        assertThat(nextRunAt()).isBetween(Instant.now().plusSeconds(10 * 60), Instant.now().plusSeconds(20 * 60))
    }

    @Test
    fun `a daily check without a domain fails with a code the page explains`() {
        activate()
        complete(lastJob().id, result())
        organization.domains.remove(tenantId())
        makeDue()

        outsideRequest { scheduler.runDue() }

        call("responsible") { mvc.get("$BASE/monitoring") { it() } }.andExpect { jsonPath("$.lastError") { value("domain_missing") } }
        assertThat(scheduleCount()).isOne()
    }

    @Test
    fun `a schedule left without active checks is forgotten`() {
        val orphan = UUID.randomUUID()
        jdbc.update("insert into externalsurface.collection_schedule (tenant_id, next_run_at) values (?, now() - interval '1 minute')", orphan)

        outsideRequest { scheduler.runDue() }

        assertThat(jdbc.queryForObject("select count(*) from externalsurface.collection_schedule where tenant_id = ?", Int::class.java, orphan)).isZero()
    }

    // -- helpers ------------------------------------------------------------------------------------------------

    private fun call(role: String, request: ((MockHttpServletRequestDsl.() -> Unit)) -> ResultActionsDsl) =
        request {
            header("Authorization", "Bearer $role.${UUID.randomUUID()}.$tenant")
            if (role == "consultant") header("X-Alinha-Tenant", tenant)
        }

    private fun activate(role: String = "responsible", authorized: Boolean = true) = call(role) { auth ->
        mvc.put("$BASE/monitoring") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = """{"authorized": $authorized}"""
        }
    }

    private fun hosts(vararg hosts: String, role: String = "responsible") = call(role) { auth ->
        mvc.put("$BASE/hosts") {
            auth()
            contentType = MediaType.APPLICATION_JSON
            content = """{"hosts": [${hosts.joinToString { "\"$it\"" }}]}"""
        }
    }

    private fun tenantId() = TenantId(UUID.fromString(tenant))

    private fun myJobs() = jobs.dispatched.filter { it.tenant.value.toString() == tenant }

    private fun lastJob() = myJobs().last()

    private fun result(domain: String = DOMAIN): Map<String, Any?> = mapOf(
        "schema" to "external-surface.result.v1",
        "domain" to domain,
        "truncated" to false,
        "domainChecks" to listOf(
            mapOf("check" to "spf", "outcome" to "PASS", "details" to mapOf("policy" to "fail")),
            mapOf("check" to "dnssec", "outcome" to "PASS", "details" to emptyMap<String, Any>()),
        ),
        "hosts" to listOf(
            mapOf(
                "host" to "www.$domain",
                "role" to "www",
                "checks" to listOf(
                    mapOf("check" to "tls_certificate", "outcome" to "FAIL", "details" to mapOf("reason" to "expired")),
                ),
            ),
        ),
    )

    private fun put(jobId: UUID, result: Map<String, Any?>) {
        results.results[tenantId() to jobId] =
            JobResult(jobId, "evidence.collect", JobStatus.COMPLETED, 1, result, null, Instant.now())
    }

    private fun completed(jobId: UUID, requestedBy: String = "external-surface") =
        JobCompleted(tenantId(), UUID.randomUUID(), Instant.now(), jobId, "evidence.collect", requestedBy, 1)

    private fun complete(jobId: UUID, result: Map<String, Any?>) {
        put(jobId, result)
        outsideRequest { listener.on(completed(jobId)) }
    }

    private fun fail(jobId: UUID, code: String) = outsideRequest {
        listener.on(
            JobFailed(tenantId(), UUID.randomUUID(), Instant.now(), jobId, "evidence.collect", "external-surface", 1,
                JobError(code, "untrusted text")),
        )
    }

    /** In the app the listener is @Async, off any request; MockMvc leaves the last request bound to this thread. */
    private fun <T> outsideRequest(block: () -> T): T {
        RequestContextHolder.resetRequestAttributes()
        return block()
    }

    private fun announcedFor(tenant: String) =
        events.stream(EvidenceCollected::class.java).filter { it.tenantId.value.toString() == tenant }.toList()

    private fun strengths() = jdbc.queryForList(
        "select strength from externalsurface.evidence where tenant_id = ?::uuid order by collected_at", String::class.java, tenant,
    ).map(EvidenceStrength::valueOf)

    private fun audit(action: String): List<String> = jdbc.queryForList(
        "select details::text from audit.audit_event where tenant_id = ?::uuid and action = ?", String::class.java, tenant, action,
    )

    private fun countEvidence(): Int = jdbc.queryForObject(
        "select count(*) from externalsurface.evidence where tenant_id = ?::uuid", Int::class.java, tenant,
    )!!

    private fun scheduleCount(): Int = jdbc.queryForObject(
        "select count(*) from externalsurface.collection_schedule where tenant_id = ?::uuid", Int::class.java, tenant,
    )!!

    private fun makeDue() = jdbc.update(
        "update externalsurface.collection_schedule set next_run_at = now() - interval '1 minute' where tenant_id = ?::uuid",
        tenant,
    )

    private fun nextRunAt(): Instant = jdbc.queryForObject(
        "select next_run_at from externalsurface.collection_schedule where tenant_id = ?::uuid", java.sql.Timestamp::class.java, tenant,
    )!!.toInstant()
}
