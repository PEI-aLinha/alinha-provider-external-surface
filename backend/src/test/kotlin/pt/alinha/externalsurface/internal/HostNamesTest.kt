package pt.alinha.externalsurface.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import pt.alinha.externalsurface.internal.domain.HostNames

class HostNamesTest {

    @ParameterizedTest
    @CsvSource(
        "loja.acme.pt, loja.acme.pt",
        "https://Loja.Acme.pt/catalogo?x=1, loja.acme.pt",
        "loja.acme.pt:8443, loja.acme.pt",
        "loja.acme.pt., loja.acme.pt",
        "açores.acme.pt, xn--aores-yra.acme.pt",
    )
    fun `what is typed becomes a host name`(typed: String, expected: String) {
        assertThat(HostNames.normalize(typed)).isEqualTo(expected)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "10.0.0.1", "acme", "[::1]", "user@acme.pt", "a b.acme.pt", "*.acme.pt", "x_y.acme.pt", "acme.p"])
    fun `anything else is refused`(typed: String) {
        assertThat(HostNames.normalize(typed)).isNull()
    }

    @Test
    fun `subdomains are strict`() {
        assertThat(HostNames.isStrictSubdomain("loja.acme.pt", "acme.pt")).isTrue()
        assertThat(HostNames.isStrictSubdomain("acme.pt", "acme.pt")).isFalse()
        assertThat(HostNames.isStrictSubdomain("notacme.pt", "acme.pt")).isFalse()
    }
}
