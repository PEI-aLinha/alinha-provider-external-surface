package pt.alinha.externalsurface.internal

import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.transaction.support.TransactionOperations
import pt.alinha.core.jobs.JobCompleted
import pt.alinha.core.jobs.JobResult
import pt.alinha.core.jobs.JobResults
import pt.alinha.core.jobs.JobStatus
import pt.alinha.core.tenant.TenantContext
import pt.alinha.pluginapi.DomainEvent
import pt.alinha.pluginapi.TenantId
import java.time.Instant
import java.util.UUID

/** The fallback of [SurfaceResultListener]: what happens when even marking the check failed does not work. */
class SurfaceResultListenerTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val jobId = UUID.randomUUID()
    private val service = mock(MonitoringService::class.java)
    private val results = mock(JobResults::class.java)

    /** Runs the block as the event's tenant, like the real one, without a request or a database. */
    private val tenants = object : TenantContext {
        override fun current(): TenantId = tenant
        override fun <T> runAs(tenantId: TenantId, block: () -> T): T = block()
        override fun <T> runAs(event: DomainEvent, block: () -> T): T = block()
    }

    private val listener = SurfaceResultListener(service, results, tenants, TransactionOperations.withoutTransaction())

    private val event = JobCompleted(tenant, UUID.randomUUID(), Instant.now(), jobId, MonitoringService.JOB_TYPE, "external-surface", 1)

    @Test
    fun `when the result cannot be applied the check is marked failed`() {
        `when`(results.find(jobId)).thenReturn(
            JobResult(jobId, MonitoringService.JOB_TYPE, JobStatus.COMPLETED, 1, emptyMap(), null, Instant.now()),
        )
        doThrow(IllegalStateException("database unavailable")).`when`(service).applyResult(jobId, emptyMap())

        listener.on(event)

        verify(service).applyFailure(jobId, MonitoringService.GENERIC_ERROR)
    }

    @Test
    fun `when marking it failed throws too, the error is logged and does not escape the listener`() {
        `when`(results.find(jobId)).thenThrow(IllegalStateException("database unavailable"))
        doThrow(IllegalStateException("still unavailable")).`when`(service).applyFailure(jobId, MonitoringService.GENERIC_ERROR)

        assertThatCode { listener.on(event) }.doesNotThrowAnyException()
        verify(service).applyFailure(jobId, MonitoringService.GENERIC_ERROR)
    }
}
