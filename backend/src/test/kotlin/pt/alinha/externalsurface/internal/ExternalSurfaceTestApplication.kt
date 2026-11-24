package pt.alinha.externalsurface.internal

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.testcontainers.postgresql.PostgreSQLContainer
import pt.alinha.core.access.internal.AccessWebConfig
import pt.alinha.core.access.internal.RequiresPermissionInterceptor
import pt.alinha.core.access.internal.RolePermissionRegistry
import pt.alinha.core.access.internal.SecurityConfig
import pt.alinha.core.audit.internal.AuditActor
import pt.alinha.core.audit.internal.JpaAuditTrail
import pt.alinha.core.jobs.JobDispatcher
import pt.alinha.core.jobs.JobRequest
import pt.alinha.core.jobs.JobResult
import pt.alinha.core.jobs.JobResults
import pt.alinha.core.registry.PluginRegistry
import pt.alinha.core.tenant.TenantContext
import pt.alinha.core.tenant.internal.CorrelationIdFilter
import pt.alinha.core.tenant.internal.HibernateTenantResolver
import pt.alinha.core.tenant.internal.JwtTenantContext
import pt.alinha.core.tenant.internal.ScopedCorrelationContext
import pt.alinha.core.tenant.internal.TenantResolutionInterceptor
import pt.alinha.core.tenant.internal.TenantWebConfig
import pt.alinha.externalsurface.ExternalSurfacePlugin
import pt.alinha.externalsurface.internal.persistence.SurfaceRepository
import pt.alinha.externalsurface.internal.web.ExternalSurfaceController
import pt.alinha.externalsurface.internal.web.ExternalSurfaceProblems
import pt.alinha.organization.api.AddressView
import pt.alinha.organization.api.ContactsView
import pt.alinha.organization.api.OrganizationApi
import pt.alinha.organization.api.OrganizationProfileView
import pt.alinha.organization.api.PermanentContactView
import pt.alinha.pluginapi.TenantId
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Records jobs instead of writing the outbox; like the real one, refuses to run outside a transaction. */
class FakeJobDispatcher(private val tenants: TenantContext) : JobDispatcher {
    data class Dispatched(val id: UUID, val tenant: TenantId, val request: JobRequest)

    val dispatched: MutableList<Dispatched> = java.util.Collections.synchronizedList(mutableListOf())

    override fun dispatch(request: JobRequest): UUID {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "dispatch needs a transaction" }
        return UUID.randomUUID().also { dispatched += Dispatched(it, tenants.current(), request) }
    }
}

/** Results the test puts in, per (tenant, job), like the real tenant-filtered store. */
class FakeJobResults(private val tenants: TenantContext) : JobResults {
    val results = ConcurrentHashMap<Pair<TenantId, UUID>, JobResult>()

    override fun find(jobId: UUID): JobResult? = results[tenants.current() to jobId]
}

/** The organization plugin's api, per tenant like the real one: [domains] holds each tenant's domain. */
class FakeOrganizationApi(private val tenants: TenantContext) : OrganizationApi {
    val domains = ConcurrentHashMap<TenantId, String>()
    val unregistered: MutableSet<TenantId> = ConcurrentHashMap.newKeySet()

    override fun currentProfile(): OrganizationProfileView? {
        val tenant = tenants.current()
        if (tenant in unregistered) return null
        return OrganizationProfileView(
            id = tenant.value, version = 0, name = "Componentes Águeda, Lda.", nif = "509180027", cae = "29320",
            sector = "industria", subsector = "automoveis", sectorAnnex = "II", services = "Componentes.",
            employees = 120, turnover = 18_500_000, address = AddressView("Rua", "3750-750", "Águeda"),
            contacts = ContactsView("234600120", "geral@acme.pt"), permanentContact = PermanentContactView("Rui", "912345678"),
            domain = domains[tenant], registeredAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
        )
    }
}

/**
 * The plugin with the core's real RBAC, tenant resolution and audit trail on PostgreSQL. The outbox, the job results
 * and the organization plugin are fakes. Token value: `<role>.<sub>.<tenant>`; a consultant's tenant goes in `tenants`.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackages = ["pt.alinha.externalsurface", "pt.alinha.core.audit"])
@EnableJpaRepositories(basePackageClasses = [SurfaceRepository::class])
@Import(
    SecurityConfig::class, AccessWebConfig::class, RequiresPermissionInterceptor::class,
    RolePermissionRegistry::class, PluginRegistry::class,
    TenantWebConfig::class, TenantResolutionInterceptor::class, JwtTenantContext::class,
    HibernateTenantResolver::class, CorrelationIdFilter::class, ScopedCorrelationContext::class,
    AuditActor::class, JpaAuditTrail::class,
    ExternalSurfacePlugin::class, MonitoringService::class, SurfaceResultListener::class, SurfaceScheduler::class,
    ExternalSurfaceApiImpl::class, ExternalSurfaceController::class, ExternalSurfaceProblems::class,
)
class ExternalSurfaceTestApplication {
    /** @Async listeners run on the caller's thread, so a test sees their effect as soon as the call returns. */
    @Bean(name = ["taskExecutor", "applicationTaskExecutor"])
    fun taskExecutor(): TaskExecutor = SyncTaskExecutor()

    @Bean fun jobDispatcher(tenants: TenantContext) = FakeJobDispatcher(tenants)

    @Bean fun jobResults(tenants: TenantContext) = FakeJobResults(tenants)

    @Bean fun organizationApi(tenants: TenantContext) = FakeOrganizationApi(tenants)

    @Bean
    @Primary
    fun testJwtDecoder(): JwtDecoder = JwtDecoder { token ->
        val (role, sub, tenant) = (token.split(".") + listOf("", "")).take(3)
        val builder = Jwt.withTokenValue(token).header("alg", "none").subject(sub).claim("email_verified", true)
            .claim("realm_access", mapOf("roles" to listOf(role)))
        when {
            tenant.isEmpty() -> Unit
            role == "consultant" -> builder.claim("tenants", listOf(tenant))
            else -> builder.claim("tenant_id", tenant)
        }
        builder.build()
    }
}

object ExternalSurfaceDatabase {
    val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17").apply { start() }

    fun register(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl)
        registry.add("spring.datasource.username", postgres::getUsername)
        registry.add("spring.datasource.password", postgres::getPassword)
        registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        registry.add("spring.jpa.open-in-view") { "false" }
        registry.add("spring.flyway.locations") { "classpath:db/migration" }
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { "https://keycloak.test/realms/alinha" }
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences") { "alinha-api" }
        // The tests call SurfaceScheduler.runDue() themselves.
        registry.add("alinha.external-surface.schedule.poll-interval-ms") { "86400000" }
    }
}
