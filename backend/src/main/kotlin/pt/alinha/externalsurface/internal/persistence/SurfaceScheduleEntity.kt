package pt.alinha.externalsurface.internal.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * When a tenant's next daily check is due. Deliberately NOT a TenantOwnedEntity: the scheduler runs outside any
 * tenant and must find every due tenant (docs/tenancy.md has no other way for a plugin). It holds no tenant data, only
 * the id and a time, and the scheduler does nothing with a row but `TenantContext.runAs(tenantId)`, where the
 * surface is read tenant-filtered again. Inside a request, only ever look a row up by `TenantContext.current()`.
 */
@Entity
@Table(schema = "externalsurface", name = "collection_schedule")
class SurfaceScheduleEntity(
    @Id @Column(name = "tenant_id") val tenantId: UUID,
    nextRunAt: Instant,
) {
    @Version
    var version: Long = 0
        protected set

    @Column(name = "next_run_at", nullable = false)
    var nextRunAt: Instant = nextRunAt
        protected set

    fun postpone(until: Instant) {
        nextRunAt = until
    }
}
