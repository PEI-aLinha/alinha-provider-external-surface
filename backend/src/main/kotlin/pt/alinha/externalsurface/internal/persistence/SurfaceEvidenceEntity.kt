package pt.alinha.externalsurface.internal.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import pt.alinha.core.tenant.TenantOwnedEntity
import pt.alinha.externalsurface.internal.domain.Outcome
import pt.alinha.externalsurface.internal.domain.SurfaceCheck
import pt.alinha.pluginapi.EvidenceStrength
import java.time.Instant
import java.util.UUID

/** One check on one host (or on the domain), from one check run. Insert-only. */
@Entity
@Table(schema = "externalsurface", name = "evidence")
class SurfaceEvidenceEntity(
    @Id val id: UUID,
    @Column(name = "surface_id", updatable = false) val surfaceId: UUID?,
    @Column(name = "job_id", nullable = false, updatable = false) val jobId: UUID,
    @Column(nullable = false, updatable = false) val domain: String,
    @Column(nullable = false, updatable = false) val host: String,
    check: SurfaceCheck,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) val outcome: Outcome,
    /** Short codes, numbers and flags from the worker, already validated (SurfaceResultParser). JSON object. */
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, updatable = false) val details: String,
    controlIds: Set<String>,
    @Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false) val strength: EvidenceStrength,
    @Column(name = "mapping_version", nullable = false, updatable = false) val mappingVersion: Int,
    @Column(name = "collected_at", nullable = false, updatable = false) val collectedAt: Instant,
) : TenantOwnedEntity() {

    @Column(name = "check_name", nullable = false, updatable = false)
    private val checkName: String = check.id

    val check: SurfaceCheck get() = requireNotNull(SurfaceCheck.of(checkName)) { "unknown check $checkName" }

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "control_ids", columnDefinition = "text[]", nullable = false, updatable = false)
    private val controlIdArray: Array<String> = controlIds.sorted().toTypedArray()

    val controlIds: Set<String> get() = controlIdArray.toSet()
}
