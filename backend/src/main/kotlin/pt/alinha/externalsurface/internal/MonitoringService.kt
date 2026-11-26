package pt.alinha.externalsurface.internal

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pt.alinha.core.audit.AuditTrail
import pt.alinha.core.jobs.JobDispatcher
import pt.alinha.core.jobs.JobRequest
import pt.alinha.core.tenant.CorrelationContext
import pt.alinha.core.tenant.TenantContext
import pt.alinha.externalsurface.ExternalSurfacePlugin
import pt.alinha.externalsurface.internal.domain.HostNames
import pt.alinha.externalsurface.internal.domain.MonitoringStatus
import pt.alinha.externalsurface.internal.domain.Outcome
import pt.alinha.externalsurface.internal.persistence.SurfaceScheduleEntity
import pt.alinha.externalsurface.internal.persistence.SurfaceScheduleRepository
import pt.alinha.externalsurface.internal.persistence.SurfaceEvidenceEntity
import pt.alinha.externalsurface.internal.persistence.SurfaceEvidenceRepository
import pt.alinha.externalsurface.internal.persistence.SurfaceEntity
import pt.alinha.externalsurface.internal.persistence.SurfaceRepository
import pt.alinha.organization.api.OrganizationApi
import pt.alinha.pluginapi.EvidenceCollected
import pt.alinha.pluginapi.EvidenceOutcome
import pt.alinha.pluginapi.EvidenceStrength
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Checks the organization's public domain from the internet: activation by the Designated Officer, extra hosts,
 * checks on request and every day, and what they find. The domain is read from the organization profile each time a
 * check starts, so a changed domain is used from the next check on.
 */
@Service
class MonitoringService(
    private val surfaces: SurfaceRepository,
    private val evidence: SurfaceEvidenceRepository,
    private val schedules: SurfaceScheduleRepository,
    private val organization: OrganizationApi,
    private val jobs: JobDispatcher,
    private val audit: AuditTrail,
    private val events: ApplicationEventPublisher,
    private val tenants: TenantContext,
    private val correlation: CorrelationContext,
    private val json: JsonMapper,
    private val clock: Clock = Clock.systemUTC(),
) {

    @Transactional(readOnly = true)
    fun view(): MonitoringView = toView(surfaces.findFirstBy(), domain())

    @Transactional(readOnly = true)
    fun latestEvidence(): List<EvidenceView> {
        val jobId = surfaces.findFirstBy()?.collectedJobId ?: return emptyList()
        return evidence.findByJobIdOrderByHostAscCheckNameAsc(jobId).map { it.toView() }
    }

    /** The Designated Officer authorizes the checks. Returns the view and whether a first check was started. */
    @Transactional
    fun activate(input: ActivationInput): Pair<MonitoringView, Boolean> {
        if (input.authorized != true) throw AuthorizationRequired()
        val domain = domain() ?: throw DomainMissing()
        val surface = surfaces.findFirstBy() ?: surfaces.save(SurfaceEntity(UUID.randomUUID()))
        if (surface.active) return toView(surface, domain) to false
        val now = Instant.now(clock)
        surface.activate(now)
        audit.record(
            "external-surface.activated", ENTITY_TYPE, surface.id.toString(),
            mapOf("authorized" to true, "domain" to domain, "hosts" to surface.extraHosts),
        )
        // Turning the checks off and on again does not skip the "check now" cooldown: the first check waits for it.
        val cooldownEnd = surface.collectingSince?.plus(MANUAL_COOLDOWN)?.takeIf { it.isAfter(now) }
        val tenantId = tenants.current().value
        if (!schedules.existsById(tenantId)) {
            schedules.save(SurfaceScheduleEntity(tenantId, cooldownEnd ?: now.plus(SCHEDULE_INTERVAL)))
        }
        val started = cooldownEnd == null && !surface.isCollecting(now, COLLECTION_DEADLINE)
        if (started) startCollection(surface, domain, now, scheduled = false)
        // A concurrent activation fails here (unique tenant row, @Version) instead of starting a second check.
        surfaces.flush()
        return toView(surface, domain) to started
    }

    /**
     * Stops the daily checks and abandons one still running. The extra hosts and the results already collected stay.
     */
    @Transactional
    fun deactivate() {
        val surface = surfaces.findFirstBy()?.takeIf { it.active } ?: throw NotActive()
        val abandoned = surface.deactivate()
        schedules.findById(tenants.current().value).ifPresent(schedules::delete)
        audit.record(
            "external-surface.deactivated", ENTITY_TYPE, surface.id.toString(),
            abandoned?.let { mapOf("abandonedJobId" to it.toString()) }.orEmpty(),
        )
    }

    /** Replaces the extra hosts. Each must be a subdomain of the profile's domain; www is always checked anyway. */
    @Transactional
    fun setHosts(input: HostsInput): MonitoringView {
        val domain = domain() ?: throw DomainMissing()
        val typed = input.hosts.orEmpty()
        val hosts = typed.mapIndexed { index, raw ->
            val host = raw?.let(HostNames::normalize) ?: throw InvalidInput("hosts[$index]", InvalidInput.Reason.NOT_A_HOST)
            if (!HostNames.isStrictSubdomain(host, domain)) throw InvalidInput("hosts[$index]", InvalidInput.Reason.OUTSIDE_DOMAIN)
            host
        }.filter { it != HostNames.www(domain) }.distinct()
        if (hosts.size > HostNames.MAX_EXTRA) throw InvalidInput("hosts", InvalidInput.Reason.TOO_MANY)

        val surface = surfaces.findFirstBy() ?: surfaces.save(SurfaceEntity(UUID.randomUUID()))
        val before = surface.extraHosts
        if (before == hosts) return toView(surface, domain)
        surface.replaceHosts(hosts)
        audit.record(
            "external-surface.hosts-changed", ENTITY_TYPE, surface.id.toString(),
            mapOf("added" to (hosts - before.toSet()), "removed" to (before - hosts.toSet())),
        )
        surfaces.flush()
        return toView(surface, domain)
    }

    @Transactional
    fun collectNow(): MonitoringView {
        val surface = surfaces.findFirstBy()?.takeIf { it.active } ?: throw NotActive()
        val now = Instant.now(clock)
        if (surface.isCollecting(now, COLLECTION_DEADLINE)) throw CollectionRunning()
        val last = surface.collectingSince
        if (last != null && last.plus(MANUAL_COOLDOWN).isAfter(now)) {
            throw TooSoon(Duration.between(now, last.plus(MANUAL_COOLDOWN)).withNanos(0).plusSeconds(1))
        }
        val domain = domain() ?: throw DomainMissing()
        audit.record("external-surface.collection-requested", ENTITY_TYPE, surface.id.toString())
        startCollection(surface, domain, now, scheduled = false)
        return toView(surface, domain)
    }

    /**
     * The daily check, run by [SurfaceScheduler] inside `runAs`. Skips a tenant whose check is still running,
     * forgets the schedule of one that is no longer active, and records a missing domain as the check's failure.
     */
    @Transactional
    fun scheduledCollection() {
        val surface = surfaces.findFirstBy()
        if (surface == null || !surface.active) {
            schedules.findById(tenants.current().value).ifPresent(schedules::delete)
            return
        }
        val now = Instant.now(clock)
        if (surface.isCollecting(now, COLLECTION_DEADLINE)) {
            // Someone started one by hand: try again soon instead of a day later (the 26-hour window).
            schedules.findById(tenants.current().value).ifPresent { it.postpone(now.plus(SCHEDULE_RETRY)) }
            return
        }
        val domain = domain()
        if (domain == null) {
            fail(surface, null, DOMAIN_MISSING)
            return
        }
        audit.record("external-surface.collection-scheduled", ENTITY_TYPE, surface.id.toString())
        startCollection(surface, domain, now, scheduled = true)
    }

    /**
     * Stores the evidence of a completed job. Called by [SurfaceResultListener] inside `runAs` and a transaction.
     * Ignores jobs that are not the current one, and jobs already applied.
     */
    @Transactional
    fun applyResult(jobId: UUID, result: Map<String, Any?>?) {
        // Not active: the checks were turned off, nothing more is stored about the organization's hosts.
        val surface = surfaces.findFirstBy()?.takeIf { it.lastJobId == jobId && it.active } ?: return
        // Already applied (stored, or failed as invalid): a replayed event changes nothing.
        if (surface.status != MonitoringStatus.PENDING || evidence.existsByJobId(jobId)) return
        val domain = surface.collectingDomain ?: return
        val parsed = try {
            SurfaceResultParser.parse(result, domain, surface.collectingHosts)
        } catch (_: InvalidSurfaceResult) {
            fail(surface, jobId, INVALID_RESULT)
            return
        }
        val now = Instant.now(clock)
        // Continuously verified: the daily schedule checked it, and the previous check succeeded within a day.
        val previous = surface.lastCollectedAt
        val strength = if (surface.collectingScheduled && previous != null && !previous.plus(CONTINUOUS_WINDOW).isBefore(now)) {
            EvidenceStrength.CONTINUOUSLY_VERIFIED
        } else {
            EvidenceStrength.TECHNICALLY_VERIFIED
        }
        val stored = parsed.outcomes.map { outcome ->
            evidence.save(
                SurfaceEvidenceEntity(
                    id = UUID.randomUUID(),
                    surfaceId = surface.id,
                    jobId = jobId,
                    domain = domain,
                    host = outcome.host,
                    check = outcome.check,
                    outcome = outcome.outcome,
                    details = json.writeValueAsString(outcome.details),
                    controlIds = ControlMapping.controlsFor(outcome.check),
                    strength = strength,
                    mappingVersion = ControlMapping.VERSION,
                    collectedAt = now,
                ),
            )
        }
        surface.collected(jobId, now)
        audit.record(
            "external-surface.evidence-collected", ENTITY_TYPE, surface.id.toString(),
            mapOf(
                "jobId" to jobId.toString(),
                "domain" to domain,
                "evidence" to stored.size,
                "pass" to stored.count { it.outcome == Outcome.PASS },
                "fail" to stored.count { it.outcome == Outcome.FAIL },
                "unknown" to stored.count { it.outcome == Outcome.UNKNOWN },
                "mappingVersion" to ControlMapping.VERSION,
                "strength" to strength.name,
            ),
        )
        // UNKNOWN is not evidence of anything (it could not be checked), and recommendations map to no control.
        stored.filter { it.outcome != Outcome.UNKNOWN && it.controlIds.isNotEmpty() }.forEach {
            events.publishEvent(
                EvidenceCollected(
                    tenantId = tenants.current(),
                    correlationId = correlation.current(),
                    occurredAt = now,
                    provider = ExternalSurfacePlugin.ID,
                    evidenceId = it.id,
                    controlIds = it.controlIds,
                    outcome = if (it.outcome == Outcome.PASS) EvidenceOutcome.SATISFIED else EvidenceOutcome.NOT_SATISFIED,
                    strength = it.strength,
                ),
            )
        }
    }

    /** Records why the current job failed. [errorCode] comes from the worker: only its shape is kept. */
    @Transactional
    fun applyFailure(jobId: UUID, errorCode: String) {
        val surface = surfaces.findFirstBy()?.takeIf { it.lastJobId == jobId } ?: return
        if (surface.status != MonitoringStatus.PENDING) return
        fail(surface, jobId, errorCode.takeIf { HostNames.ERROR_CODE.matches(it) } ?: GENERIC_ERROR)
    }

    @Transactional(readOnly = true)
    fun evidence(evidenceId: UUID): SurfaceEvidenceEntity? = evidence.findById(evidenceId).orElse(null)

    private fun domain(): String? = organization.currentProfile()?.domain?.takeIf(HostNames::isHost)

    private fun fail(surface: SurfaceEntity, jobId: UUID?, code: String) {
        surface.failed(code)
        audit.record(
            "external-surface.collection-failed", ENTITY_TYPE, surface.id.toString(),
            buildMap {
                jobId?.let { put("jobId", it.toString()) }
                put("error", code)
            },
        )
    }

    private fun startCollection(surface: SurfaceEntity, domain: String, now: Instant, scheduled: Boolean) {
        // Extra hosts outside the current domain (the profile's domain changed) are kept but not checked.
        val hosts = surface.extraHosts.filter { HostNames.isStrictSubdomain(it, domain) }
        val jobId = jobs.dispatch(
            JobRequest(
                type = JOB_TYPE,
                payload = mapOf(
                    "provider" to ExternalSurfacePlugin.ID,
                    "connectionRef" to CONNECTION_REF,
                    "scope" to mapOf("domain" to domain, "hosts" to hosts),
                ),
                requestedBy = ExternalSurfacePlugin.ID,
            ),
        )
        surface.collecting(jobId, domain, hosts, now, scheduled)
    }

    private fun toView(surface: SurfaceEntity?, domain: String?) = MonitoringView(
        domain = domain,
        active = surface?.active ?: false,
        activatedAt = surface?.activatedAt,
        status = surface?.status,
        lastError = surface?.lastError,
        lastCollectedAt = surface?.lastCollectedAt,
        collectingSince = surface?.collectingSince,
        hosts = surface?.extraHosts.orEmpty().map { HostView(it, domain != null && HostNames.isStrictSubdomain(it, domain)) },
    )

    @Suppress("UNCHECKED_CAST")
    private fun SurfaceEvidenceEntity.toView() = EvidenceView(
        id = id,
        host = host,
        check = check.id,
        outcome = outcome,
        details = json.readValue(details, Map::class.java) as Map<String, Any?>,
        controlIds = controlIds.sorted(),
        informational = check.informational,
        strength = strength,
        collectedAt = collectedAt,
    )

    companion object {
        const val JOB_TYPE = "evidence.collect"
        const val ENTITY_TYPE = "external_surface"
        /** No credential: the contract still requires a reference (docs/jobs.md, "Credentials for a collector"). */
        const val CONNECTION_REF = "providers/external-surface"
        const val INVALID_RESULT = "invalid_result"
        const val GENERIC_ERROR = "collector_error"
        const val DOMAIN_MISSING = "domain_missing"

        /** How often the schedule checks each active tenant. */
        val SCHEDULE_INTERVAL: Duration = Duration.ofHours(24)

        /** When the daily check found one already running. */
        val SCHEDULE_RETRY: Duration = Duration.ofMinutes(15)

        /** A scheduled check whose previous success is at most this old is CONTINUOUSLY_VERIFIED. */
        val CONTINUOUS_WINDOW: Duration = Duration.ofHours(26)

        /** A check still PENDING after this is treated as lost, so a new one may start. */
        val COLLECTION_DEADLINE: Duration = Duration.ofMinutes(15)

        /** "Check now" at most this often: every check opens connections to the organization's servers. */
        val MANUAL_COOLDOWN: Duration = Duration.ofMinutes(10)
    }
}
