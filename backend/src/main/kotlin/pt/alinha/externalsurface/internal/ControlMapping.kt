package pt.alinha.externalsurface.internal

import pt.alinha.externalsurface.internal.domain.SurfaceCheck

/**
 * Which NIS2/QNRCS v1 controls (frameworks/nis2-qnrcs/v1/catalogue.yaml) each check is technical evidence for.
 * Deterministic and versioned (RNF05): every stored evidence row keeps the [VERSION] it was mapped with. Change the
 * mapping by bumping [VERSION], never by editing it in place. Informational checks map to nothing.
 */
object ControlMapping {
    const val VERSION = 1

    private const val ENCRYPTED_ACCESS = "PR.SD-2-B"     // Encriptar o acesso a informação sensível
    private const val OUTBOUND_FLOWS = "PR.SD-2-E"       // Proteger toda a informação que sai da empresa
    private const val SECURE_BASELINE = "PR.SP-1-B"      // Configuração base segura
    private const val VULNERABILITY_REPORTS = "ID.AR-7-S" // Processo de gestão de vulnerabilidades (fontes externas)

    val CONTROLS: Map<SurfaceCheck, Set<String>> = mapOf(
        // Visitors reach the site over current, verified encryption.
        SurfaceCheck.TLS_VERSION to setOf(ENCRYPTED_ACCESS),
        SurfaceCheck.TLS_CERTIFICATE to setOf(ENCRYPTED_ACCESS),
        SurfaceCheck.HTTPS_REDIRECT to setOf(ENCRYPTED_ACCESS),
        // Old protocols switched off, and browsers told to always use https: part of a secure configuration.
        SurfaceCheck.TLS_LEGACY to setOf(ENCRYPTED_ACCESS, SECURE_BASELINE),
        SurfaceCheck.HSTS to setOf(ENCRYPTED_ACCESS, SECURE_BASELINE),
        // The site's browser protections.
        SurfaceCheck.CSP to setOf(SECURE_BASELINE),
        SurfaceCheck.CONTENT_TYPE_OPTIONS to setOf(SECURE_BASELINE),
        SurfaceCheck.FRAME_PROTECTION to setOf(SECURE_BASELINE),
        SurfaceCheck.REFERRER_POLICY to setOf(SECURE_BASELINE),
        // Email that pretends to come from the organization is refused.
        SurfaceCheck.SPF to setOf(SECURE_BASELINE),
        SurfaceCheck.DKIM to setOf(SECURE_BASELINE),
        SurfaceCheck.DMARC to setOf(SECURE_BASELINE),
        // Email to the organization only travels encrypted.
        SurfaceCheck.MTA_STS to setOf(OUTBOUND_FLOWS),
        // Outsiders know where to report a vulnerability.
        SurfaceCheck.SECURITY_TXT to setOf(VULNERABILITY_REPORTS),
        SurfaceCheck.CAA to emptySet(),
        SurfaceCheck.DNSSEC to emptySet(),
    )

    fun controlsFor(check: SurfaceCheck): Set<String> = CONTROLS.getValue(check)
}
