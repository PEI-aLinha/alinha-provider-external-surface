package pt.alinha.externalsurface.internal

import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionOperations
import pt.alinha.core.tenant.TenantContext
import pt.alinha.externalsurface.internal.persistence.SurfaceScheduleRepository
import pt.alinha.pluginapi.TenantId
import java.time.Clock
import java.time.Instant
import java.util.UUID

private val log = LoggerFactory.getLogger(SurfaceScheduler::class.java)

/**
 * Checks every active tenant's domain once a day, so its evidence stays current (CONTINUOUSLY_VERIFIED, see
 * [MonitoringService.applyResult]). Each due tenant is claimed first (its next run moved a day ahead, `@Version`), so
 * two app instances never start the same check twice; then the check runs as that tenant.
 */
@Component
class SurfaceScheduler(
    private val schedules: SurfaceScheduleRepository,
    private val service: MonitoringService,
    private val tenants: TenantContext,
    private val tx: TransactionOperations,
    private val clock: Clock = Clock.systemUTC(),
) {

    @Scheduled(
        initialDelayString = "\${alinha.external-surface.schedule.poll-interval-ms:300000}",
        fixedDelayString = "\${alinha.external-surface.schedule.poll-interval-ms:300000}",
    )
    fun runDue() {
        val now = Instant.now(clock)
        val due = tx.execute { schedules.findTop50ByNextRunAtLessThanEqualOrderByNextRunAtAsc(now).map { it.tenantId } }
        due.orEmpty().filter { claim(it, now) }.forEach { tenantId ->
            // runAs outside any transaction; scheduledCollection opens its own (docs/tenancy.md, rule 4).
            try {
                tenants.runAs(TenantId(tenantId)) { service.scheduledCollection() }
            } catch (e: RuntimeException) {
                // One tenant's failure (e.g. the outbox) must not stop the others. Tried again tomorrow.
                log.error("scheduled external surface check failed for tenant {}", tenantId, e)
            }
        }
    }

    /** Moves the tenant's next run a day ahead. False when another instance claimed it first. */
    private fun claim(tenantId: UUID, now: Instant): Boolean = try {
        tx.execute {
            val schedule = schedules.findById(tenantId).orElse(null)?.takeIf { !it.nextRunAt.isAfter(now) }
            schedule?.postpone(now.plus(MonitoringService.SCHEDULE_INTERVAL))
            schedule != null
        } == true
    } catch (_: OptimisticLockingFailureException) {
        false
    }
}
