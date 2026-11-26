// Subproject ":external-surface" of the main repo's build (included automatically by
// its settings.gradle.kts). Does not build outside that checkout.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.modulith.bom))

    implementation(project(":plugin-api"))
    implementation(project(":core"))
    // The domain to check comes from the organization profile: only pt.alinha.organization.api.OrganizationApi
    implementation(project(":organization"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.modulith.starter.core)

    testImplementation(libs.spring.boot.starter.test)
    // Integration test: real core RBAC, tenant and audit beans on PostgreSQL (ExternalSurfaceIntegrationTest)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.oauth2.resource.server)
    testImplementation(libs.spring.boot.starter.flyway)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.flyway.postgresql)
    testRuntimeOnly(libs.postgresql)
}
