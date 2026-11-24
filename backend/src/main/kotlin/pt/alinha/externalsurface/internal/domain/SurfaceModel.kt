package pt.alinha.externalsurface.internal.domain

import java.net.IDN

/** Where a check runs: once for the domain (email and DNS), or on every web host. */
enum class CheckKind { DOMAIN, WEB }

/**
 * What the worker checks (worker/src/alinha_externalsurface/model.py). [informational] checks are recommendations:
 * stored and shown, never evidence for a control. [mainSiteOnly] checks run on the domain and www, not the extras.
 */
enum class SurfaceCheck(val id: String, val kind: CheckKind, val informational: Boolean = false, val mainSiteOnly: Boolean = false) {
    TLS_VERSION("tls_version", CheckKind.WEB),
    TLS_LEGACY("tls_legacy", CheckKind.WEB),
    TLS_CERTIFICATE("tls_certificate", CheckKind.WEB),
    HTTPS_REDIRECT("https_redirect", CheckKind.WEB),
    HSTS("hsts", CheckKind.WEB),
    CSP("csp", CheckKind.WEB),
    CONTENT_TYPE_OPTIONS("content_type_options", CheckKind.WEB),
    FRAME_PROTECTION("frame_protection", CheckKind.WEB),
    REFERRER_POLICY("referrer_policy", CheckKind.WEB),
    SECURITY_TXT("security_txt", CheckKind.WEB, mainSiteOnly = true),
    SPF("spf", CheckKind.DOMAIN),
    DKIM("dkim", CheckKind.DOMAIN),
    DMARC("dmarc", CheckKind.DOMAIN),
    MTA_STS("mta_sts", CheckKind.DOMAIN),
    CAA("caa", CheckKind.DOMAIN, informational = true),
    DNSSEC("dnssec", CheckKind.DOMAIN, informational = true),
    ;

    companion object {
        fun of(id: String): SurfaceCheck? = entries.firstOrNull { it.id == id }
    }
}

enum class Outcome { PASS, FAIL, UNKNOWN }

enum class MonitoringStatus { PENDING, ACTIVE, FAILED }

/** The role of a web host in a check: the domain itself, its www, or an extra host the client added. */
enum class HostRole(val id: String) {
    PRIMARY("primary"), WWW("www"), EXTRA("extra");

    companion object {
        fun of(id: String): HostRole? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Host names as the client types them and as the worker gets them: lower case ASCII (internationalized names in their
 * `xn--` form), letters, digits and hyphens, at least two labels and a real top-level domain. Same rules as the
 * organization's domain (organization plugin, `DomainName`).
 */
object HostNames {
    const val MAX_EXTRA = 10
    /** The domain, www and the extra hosts. */
    const val MAX_HOSTS = MAX_EXTRA + 2
    const val MAX_LENGTH = 253
    val ERROR_CODE = Regex("^[a-z][a-z0-9_]{0,99}$")

    private val SCHEME = Regex("""^[a-z][a-z0-9+.\-]*://""")
    private val PORT = Regex(""":\d{1,5}$""")
    private val LABEL = Regex("""^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$""")
    private val TOP_LEVEL = Regex("""^(?:[a-z]{2,63}|xn--[a-z0-9-]{1,59})$""")

    /** A host name already in its stored form. */
    fun isHost(value: String): Boolean {
        val labels = value.split('.')
        return value.length <= MAX_LENGTH && labels.size >= 2 && labels.all(LABEL::matches) && TOP_LEVEL.matches(labels.last())
    }

    /** What was typed (`https://Loja.Empresa.pt/`) as a host name (`loja.empresa.pt`), or null. */
    fun normalize(raw: String): String? {
        var text = raw.trim().lowercase()
        if (text.isEmpty() || text.any { it.isWhitespace() || it.isISOControl() }) return null
        text = text.replace(SCHEME, "")
        val end = text.indexOfAny(charArrayOf('/', '?', '#'))
        if (end >= 0) text = text.substring(0, end)
        if ('@' in text || '[' in text) return null
        text = text.replace(PORT, "").removeSuffix(".")
        val ascii = try {
            IDN.toASCII(text, IDN.USE_STD3_ASCII_RULES).lowercase()
        } catch (_: IllegalArgumentException) {
            return null
        }
        return ascii.takeIf(::isHost)
    }

    fun isStrictSubdomain(host: String, domain: String): Boolean = host.endsWith(".$domain")

    fun www(domain: String) = "www.$domain"
}
