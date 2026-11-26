"""TLS checks of one web host: the version it negotiates, whether it still accepts TLS 1.0/1.1, and its
certificate. Each probe opens a TLS session, sends nothing and closes it."""
from __future__ import annotations

import ssl
import warnings
from datetime import datetime, timezone
from typing import Callable

from alinha_job_consumer.collectors import TransientError

from .model import CheckResult, failed, passed, unknown
from .probe import Probe

EXPIRES_SOON_DAYS = 30
MODERN = ("TLSv1.2", "TLSv1.3")
# OpenSSL verify codes (X509_V_ERR_*).
_EXPIRED = 10
_HOSTNAME_MISMATCH = 62
_UNTRUSTED = (18, 19, 20, 21)


def verifying_context() -> ssl.SSLContext:
    """What a browser does: trusted chain, matching name, TLS 1.2 or later."""
    context = ssl.create_default_context()
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    return context


def inspecting_context() -> ssl.SSLContext:
    """Reads the negotiated version of a host whose certificate is invalid. Used only to look: no data is sent.

    Verification is off on purpose: the verifying handshake already failed and reported the certificate; this one
    only reads which TLS version the host negotiates. The session is closed without sending anything."""
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    return context


def legacy_context() -> ssl.SSLContext | None:
    """Offers only TLS 1.0 and 1.1, to see whether the host still accepts them. None when this OpenSSL cannot.

    The old protocols, weak ciphers and missing verification are the point of this probe: it reports a host that
    still accepts them. The session is closed right after the handshake, without sending anything."""
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", DeprecationWarning)
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
            context.minimum_version = ssl.TLSVersion.TLSv1
            context.maximum_version = ssl.TLSVersion.TLSv1_1
            context.set_ciphers("DEFAULT:@SECLEVEL=0")
        return context
    except (ValueError, ssl.SSLError):
        return None


def _certificate_failure(error: ssl.SSLCertVerificationError) -> CheckResult:
    code = error.verify_code
    if code == _EXPIRED:
        return failed("tls_certificate", reason="expired")
    if code == _HOSTNAME_MISMATCH:
        return failed("tls_certificate", reason="hostname_mismatch")
    if code in _UNTRUSTED:
        return failed("tls_certificate", reason="untrusted")
    return failed("tls_certificate", reason="invalid")


def _days_left(peercert: dict, now: datetime) -> int | None:
    not_after = peercert.get("notAfter")
    if not not_after:
        return None
    expiry = datetime.fromtimestamp(ssl.cert_time_to_seconds(not_after), timezone.utc)
    return (expiry - now).days


def _version_result(version: str) -> CheckResult:
    code = version.lower().replace("v", "").replace(".", "_")  # "TLSv1.3" -> "tls1_3"
    return passed("tls_version", version=code) if version in MODERN else failed("tls_version", version=code)


def certificate_and_version(probe: Probe, host: str, address: str, now: datetime) -> list[CheckResult]:
    try:
        info = probe.handshake(host, address, verifying_context())
    except ssl.SSLCertVerificationError as error:
        return [_certificate_failure(error), _version_only(probe, host, address)]
    except ssl.SSLError:
        # No TLS 1.2 or later in common with a current browser (or no TLS at all on 443).
        return [unknown("tls_certificate", "no_modern_tls"), failed("tls_version", reason="no_modern_tls")]
    except TransientError:
        return [unknown("tls_certificate", "unreachable"), unknown("tls_version", "unreachable")]
    days = _days_left(info.peercert, now)
    if days is None:
        certificate = unknown("tls_certificate", "no_expiry")
    elif days < 0:
        certificate = failed("tls_certificate", reason="expired")
    else:
        certificate = passed("tls_certificate", daysLeft=days, expiresSoon=days < EXPIRES_SOON_DAYS)
    return [certificate, _version_result(info.version)]


def _version_only(probe: Probe, host: str, address: str) -> CheckResult:
    try:
        return _version_result(probe.handshake(host, address, inspecting_context()).version)
    except ssl.SSLError:
        return failed("tls_version", reason="no_modern_tls")
    except TransientError:
        return unknown("tls_version", "unreachable")


def legacy(probe: Probe, host: str, address: str,
           context_factory: Callable[[], ssl.SSLContext | None] = legacy_context) -> CheckResult:
    context = context_factory()
    if context is None:
        return unknown("tls_legacy", "probe_unavailable")
    try:
        info = probe.handshake(host, address, context)
    except ssl.SSLError as error:
        # These two come from our side, before the host answered: they prove nothing about the host.
        if error.reason in ("NO_PROTOCOLS_AVAILABLE", "NO_CIPHERS_AVAILABLE"):
            return unknown("tls_legacy", "probe_unavailable")
        return passed("tls_legacy")
    except TransientError:
        return unknown("tls_legacy", "unreachable")
    return failed("tls_legacy", accepted=info.version.lower().replace("v", "").replace(".", "_"))
