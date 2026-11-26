"""Fake DNS and network for the checks: the tests never touch the real internet."""
from __future__ import annotations

import ssl
import time
from dataclasses import dataclass, field
from typing import Any

from alinha_job_consumer.net import Response, TlsInfo
from alinha_externalsurface.dnsclient import DnsUnavailable, Lookup

NX = "nxdomain"
TIMEOUT = "timeout"


class FakeResolver:
    """`records[(name, rtype)]`: a tuple of values, NX, or TIMEOUT. Anything else exists with no records."""

    def __init__(self, records: dict[tuple[str, str], Any] | None = None, delay: float = 0.0) -> None:
        self.records = records or {}
        self.delay = delay
        self.asked: list[tuple[str, str]] = []

    def lookup(self, name: str, rtype: str) -> Lookup:
        self.asked.append((name, rtype))
        if self.delay:
            time.sleep(self.delay)
        value = self.records.get((name, rtype), ())
        if value == TIMEOUT:
            raise DnsUnavailable(rtype)
        if value == NX:
            return Lookup((), name_exists=False)
        return Lookup(tuple(value))


def context_kind(context: ssl.SSLContext) -> str:
    if context.maximum_version == ssl.TLSVersion.TLSv1_1:
        return "legacy"
    return "verify" if context.verify_mode == ssl.CERT_REQUIRED else "inspect"


def cert_error(code: int) -> ssl.SSLCertVerificationError:
    error = ssl.SSLCertVerificationError(1, "certificate verify failed")
    error.verify_code = code
    return error


def ssl_error(reason: str) -> ssl.SSLError:
    error = ssl.SSLError(1, reason)
    error.reason = reason
    return error


GOOD_CERT = {"notAfter": "Jan  1 00:00:00 2027 GMT", "subject": ((("commonName", "x"),),)}


@dataclass
class FakeHost:
    address: Any = "8.8.8.8"  # or an exception to raise
    tls: dict[str, Any] = field(default_factory=lambda: {
        "verify": TlsInfo("TLSv1.3", GOOD_CERT),
        "inspect": TlsInfo("TLSv1.3"),
        "legacy": ssl_error("TLSV1_ALERT_PROTOCOL_VERSION"),
    })
    location: Any = (301, "https://www.acme.pt/")
    pages: dict[str, Any] = field(default_factory=dict)  # path -> Response or exception


SECURE_HEADERS = {
    "strict-transport-security": "max-age=31536000; includeSubDomains",
    "content-security-policy": "default-src 'self'; frame-ancestors 'none'",
    "x-content-type-options": "nosniff",
    "referrer-policy": "strict-origin-when-cross-origin",
}

SECURITY_TXT = b"Contact: mailto:security@acme.pt\nExpires: 2027-06-01T00:00:00Z\n"


def secure_host(**overrides: Any) -> FakeHost:
    host = FakeHost(pages={
        "/": Response(200, dict(SECURE_HEADERS)),
        "/.well-known/security.txt": Response(200, {}, SECURITY_TXT),
    })
    for key, value in overrides.items():
        setattr(host, key, value)
    return host


class FakeProbe:
    def __init__(self, hosts: dict[str, FakeHost] | None = None, delay: float = 0.0) -> None:
        self.hosts = hosts or {}
        self.delay = delay
        self.calls: list[tuple[str, ...]] = []

    def _host(self, host: str) -> FakeHost:
        return self.hosts.get(host) or FakeHost(address=_unresolvable())

    @staticmethod
    def _answer(value: Any) -> Any:
        if isinstance(value, BaseException):
            raise value
        return value

    def address(self, host: str) -> str:
        self.calls.append(("address", host))
        if self.delay:
            time.sleep(self.delay)
        return self._answer(self._host(host).address)

    def handshake(self, host: str, address: str, context: ssl.SSLContext) -> TlsInfo:
        kind = context_kind(context)
        self.calls.append(("handshake", host, kind))
        return self._answer(self._host(host).tls[kind])

    def get(self, host: str, address: str, path: str, max_bytes: int) -> Response:
        self.calls.append(("get", host, path))
        return self._answer(self._host(host).pages.get(path, Response(404, {})))

    def location(self, host: str, address: str) -> tuple[int, str | None]:
        self.calls.append(("location", host))
        return self._answer(self._host(host).location)


def _unresolvable() -> Exception:
    from alinha_job_consumer.collectors import TransientError
    return TransientError("host does not resolve")
