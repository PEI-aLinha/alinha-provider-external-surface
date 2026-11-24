"""The job's scope: the organization's domain and the extra hosts, already validated by the core. The worker checks
again: the scope reaches the network, and a host outside the domain would mean checking someone else's site."""
from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

from alinha_job_consumer.collectors import CollectorError

MAX_EXTRA_HOSTS = 10
_LABEL = r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
_NAME = re.compile(rf"(?=.{{1,253}}$)(?:{_LABEL}\.)+(?:[a-z]{{2,63}}|xn--[a-z0-9-]{{1,59}})")


@dataclass(frozen=True)
class Scope:
    domain: str
    extra_hosts: tuple[str, ...]

    def web_hosts(self) -> list[tuple[str, str]]:
        """(host, role): the domain, www and the extra hosts, without repeats."""
        hosts = [(self.domain, "primary"), (f"www.{self.domain}", "www")]
        hosts += [(h, "extra") for h in self.extra_hosts if h != f"www.{self.domain}"]
        return hosts


def is_host_name(value: Any) -> bool:
    return isinstance(value, str) and _NAME.fullmatch(value) is not None


def parse_scope(job: dict[str, Any]) -> Scope:
    scope = job.get("scope")
    if not isinstance(scope, dict):
        raise CollectorError("invalid_scope", "the job has no scope")
    domain = scope.get("domain")
    hosts = scope.get("hosts", [])
    if not is_host_name(domain):
        raise CollectorError("invalid_scope", "the domain is not a lower-case host name")
    if not isinstance(hosts, list) or len(hosts) > MAX_EXTRA_HOSTS:
        raise CollectorError("invalid_scope", f"hosts must be a list of at most {MAX_EXTRA_HOSTS} names")
    extras: list[str] = []
    for host in hosts:
        if not is_host_name(host) or not host.endswith(f".{domain}"):
            raise CollectorError("invalid_scope", "every host must be a subdomain of the domain")
        if host not in extras:
            extras.append(host)
    return Scope(domain, tuple(extras))
