"""DNS lookups. Only public DNS questions are asked: nothing here connects to the organization's servers."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

LIFETIME_S = 4.0


class DnsUnavailable(Exception):
    """No answer (timeout, SERVFAIL): the record may or may not exist."""


@dataclass(frozen=True)
class Lookup:
    values: tuple[str, ...] = ()
    # False when the name itself does not exist (NXDOMAIN).
    name_exists: bool = True


class Resolver(Protocol):
    def lookup(self, name: str, rtype: str) -> Lookup:
        """Records of [rtype] at [name] as text (TXT strings joined). Raises DnsUnavailable without an answer."""
        ...


class DnsPythonResolver:
    def __init__(self, lifetime: float = LIFETIME_S) -> None:
        import dns.resolver  # imported here so the tests run with fakes only

        self._dns = dns
        self._resolver = dns.resolver.Resolver()
        self._resolver.lifetime = lifetime

    def lookup(self, name: str, rtype: str) -> Lookup:
        resolver = self._dns.resolver
        try:
            answer = self._resolver.resolve(name, rtype, raise_on_no_answer=False)
        except resolver.NXDOMAIN:
            return Lookup((), name_exists=False)
        except (resolver.NoNameservers, resolver.LifetimeTimeout, self._dns.exception.Timeout) as error:
            raise DnsUnavailable(rtype) from error
        if answer.rrset is None:
            return Lookup(())
        return Lookup(tuple(_text(record) for record in answer.rrset))


def _text(record: object) -> str:
    strings = getattr(record, "strings", None)
    if strings is not None:  # TXT: one record may be split into several strings
        return b"".join(strings).decode("utf-8", "replace")
    return record.to_text()  # type: ignore[attr-defined]
