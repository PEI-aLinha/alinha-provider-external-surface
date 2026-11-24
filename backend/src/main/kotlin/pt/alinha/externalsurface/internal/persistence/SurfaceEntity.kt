package pt.alinha.externalsurface.internal.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pt.alinha.core.tenant.TenantOwnedEntity
import pt.alinha.externalsurface.internal.domain.MonitoringStatus
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The tenant's single set of external checks: extra hosts, whether they run, and the state of the last one. */
@Entity
@Table(schema = "externalsurface", name = "surface")
class SurfaceEntity(@Id val id: UUID) : TenantOwnedEntity() {

    @Version
    var version: Long = 0
        protected set

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "extra_hosts", columnDefinition = "text[]", nullable = false)
    private var extraHostArray: Array<String> = emptyArray()

    val extraHosts: List<String> get() = extraHostArray.toList()

    @Column(nullable = false)
    var active: Boolean = false
        protected set

    @Column(name = "activated_at")
    var activatedAt: Instant? = null
        protected set

    @Enumerated(EnumType.STRING)
    var status: MonitoringStatus? = null
        protected set

    @Column(name = "last_job_id")
    var lastJobId: UUID? = null
        protected set

    /** When the current (or last) check was queued; a PENDING older than the deadline may be replaced. */
    @Column(name = "collecting_since")
    var collectingSince: Instant? = null
        protected set

    @Column(name = "collecting_scheduled", nullable = false)
    var collectingScheduled: Boolean = false
        protected set

    @Column(name = "collecting_domain")
    var collectingDomain: String? = null
        protected set

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "collecting_hosts", columnDefinition = "text[]", nullable = false)
    private var collectingHostArray: Array<String> = emptyArray()

    val collectingHosts: List<String> get() = collectingHostArray.toList()

    /** The job whose evidence is the current picture (the last one that completed). */
    @Column(name = "collected_job_id")
    var collectedJobId: UUID? = null
        protected set

    @Column(name = "last_error")
    var lastError: String? = null
        protected set

    @Column(name = "last_collected_at")
    var lastCollectedAt: Instant? = null
        protected set

    fun replaceHosts(hosts: List<String>) {
        extraHostArray = hosts.toTypedArray()
    }

    fun activate(at: Instant) {
        active = true
        activatedAt = at
    }

    fun deactivate() {
        active = false
    }

    fun collecting(jobId: UUID, domain: String, hosts: List<String>, at: Instant, scheduled: Boolean) {
        status = MonitoringStatus.PENDING
        collectingSince = at
        collectingScheduled = scheduled
        collectingDomain = domain
        collectingHostArray = hosts.toTypedArray()
        lastJobId = jobId
        lastError = null
    }

    fun collected(jobId: UUID, at: Instant) {
        status = MonitoringStatus.ACTIVE
        collectedJobId = jobId
        lastCollectedAt = at
        lastError = null
    }

    fun failed(errorCode: String) {
        status = MonitoringStatus.FAILED
        lastError = errorCode
    }

    /** A check is running and is not yet past [deadline]: a new one would race it. */
    fun isCollecting(now: Instant, deadline: Duration): Boolean =
        status == MonitoringStatus.PENDING && collectingSince?.plus(deadline)?.isAfter(now) == true
}
