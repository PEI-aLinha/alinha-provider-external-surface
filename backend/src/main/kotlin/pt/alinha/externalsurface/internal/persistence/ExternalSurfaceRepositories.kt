package pt.alinha.externalsurface.internal.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/** Tenant-filtered by Hibernate (TenantOwnedEntity): at most one row per tenant. */
interface SurfaceRepository : JpaRepository<SurfaceEntity, UUID> {
    fun findFirstBy(): SurfaceEntity?
}

interface SurfaceEvidenceRepository : JpaRepository<SurfaceEvidenceEntity, UUID> {
    fun existsByJobId(jobId: UUID): Boolean

    fun findByJobIdOrderByHostAscCheckNameAsc(jobId: UUID): List<SurfaceEvidenceEntity>
}

/** Not tenant-filtered (SurfaceScheduleEntity): only the scheduler lists it. */
interface SurfaceScheduleRepository : JpaRepository<SurfaceScheduleEntity, UUID> {
    fun findTop50ByNextRunAtLessThanEqualOrderByNextRunAtAsc(now: Instant): List<SurfaceScheduleEntity>
}
