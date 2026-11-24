"""The network side of the web checks, behind one small interface so the checks can be tested with fakes.

Everything goes through `alinha_job_consumer.net`: public addresses only, connections pinned to the checked
address, no redirects followed, capped bodies."""
from __future__ import annotations

import ssl
from typing import Protocol

from alinha_job_consumer import net

from .model import PROVIDER_ID

TIMEOUT_S = 5.0


class Probe(Protocol):
    def address(self, host: str) -> str:
        """The host's public address. Raises TransientError when it does not resolve, CollectorError
        `host_not_allowed` when it is not public."""
        ...

    def handshake(self, host: str, address: str, context: ssl.SSLContext) -> net.TlsInfo: ...

    def get(self, host: str, address: str, path: str, max_bytes: int) -> net.Response: ...

    def location(self, host: str, address: str) -> tuple[int, str | None]: ...


class NetProbe:
    def __init__(self, timeout: float = TIMEOUT_S) -> None:
        self._timeout = timeout
        self._headers = {"User-Agent": net.user_agent(PROVIDER_ID), "Accept": "*/*"}

    def address(self, host: str) -> str:
        return net.public_address(host, 443)

    def handshake(self, host: str, address: str, context: ssl.SSLContext) -> net.TlsInfo:
        return net.tls_handshake(host, address, context, timeout=self._timeout)

    def get(self, host: str, address: str, path: str, max_bytes: int) -> net.Response:
        return net.pinned_https_get(host, address, path, self._headers, timeout=self._timeout, max_bytes=max_bytes)

    def location(self, host: str, address: str) -> tuple[int, str | None]:
        return net.pinned_http_location(host, address, headers=self._headers, timeout=self._timeout)
