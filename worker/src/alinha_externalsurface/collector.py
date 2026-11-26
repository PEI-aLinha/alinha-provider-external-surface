"""external-surface collector: checks the organization's public domain from the internet, without credentials.

Registered in the `alinha.collectors` entry-point group. The job's scope is `{domain, hosts}`; the result follows
`external-surface.result.v1`:

    {"schema", "domain", "truncated": false,
     "domainChecks": [{"check", "outcome", "details"}],
     "hosts": [{"host", "role": "primary" | "www" | "extra", "checks": [...]}]}

Every check runs in one bounded time budget. A check that cannot finish is UNKNOWN, never FAIL: an UNKNOWN is not
evidence for any control, so a slow host never turns into a gap.
"""
from __future__ import annotations

import concurrent.futures
import logging
import time
from datetime import datetime, timezone
from typing import Any, Callable

from alinha_job_consumer.collectors import CollectorError, TransientError

from . import dns_checks, http_checks, tls_checks
from .dnsclient import DnsPythonResolver, DnsUnavailable, Resolver
from .model import (DOMAIN_CHECKS, MAIN_SITE_ONLY, PROVIDER_ID, RESULT_SCHEMA, WEB_CHECKS, CheckResult,
                    unknown_for)
from .probe import NetProbe, Probe
from .scope import Scope, parse_scope

BUDGET_S = 40.0  # below the worker's 50 s collector deadline
WORKERS = 6

Clock = Callable[[], float]

log = logging.getLogger(__name__)


def collect(job: dict[str, Any], *, resolver: Resolver | None = None, probe: Probe | None = None,
            now: datetime | None = None, budget_s: float = BUDGET_S, clock: Clock = time.monotonic
            ) -> dict[str, Any]:
    if job.get("provider") != PROVIDER_ID:
        raise CollectorError("unknown_provider", f"this collector only handles {PROVIDER_ID}")
    scope = parse_scope(job)
    resolver = resolver or DnsPythonResolver()
    probe = probe or NetProbe()
    now = now or datetime.now(timezone.utc)
    deadline = clock() + budget_s

    is_zone_apex = _zone_apex(resolver, scope.domain)
    hosts = scope.web_hosts()
    executor = concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS, thread_name_prefix="external-surface")
    try:
        domain_task = executor.submit(_domain, resolver, probe, scope.domain, is_zone_apex)
        host_tasks = [executor.submit(_host, probe, host, role, {h for h, _ in hosts}, now) for host, role in hosts]
        concurrent.futures.wait([domain_task, *host_tasks], timeout=max(deadline - clock(), 0))
    finally:
        # Threads still running end at their own socket and DNS timeouts; their results are not waited for.
        executor.shutdown(wait=False, cancel_futures=True)

    domain_results = _finished(domain_task, list(DOMAIN_CHECKS))
    host_results = []
    for (host, role), task in zip(hosts, host_tasks):
        checks = _finished(task, _web_checks(role))
        if checks is not None:  # None: www does not exist, nothing to report
            host_results.append({"host": host, "role": role, "checks": [c.to_json() for c in checks]})
    return {
        "schema": RESULT_SCHEMA,
        "domain": scope.domain,
        "truncated": False,
        "domainChecks": [c.to_json() for c in domain_results or []],
        "hosts": host_results,
    }


def _zone_apex(resolver: Resolver, domain: str) -> bool:
    """Whether [domain] is a DNS zone (has its own SOA). Email policies live at the zone, not at a host inside it."""
    try:
        lookup = resolver.lookup(domain, "SOA")
    except DnsUnavailable as error:
        raise TransientError("the domain's DNS does not answer") from error
    if not lookup.name_exists:
        raise CollectorError("domain_not_found", "the domain does not exist in DNS")
    return bool(lookup.values)


def _domain(resolver: Resolver, probe: Probe, domain: str, is_zone_apex: bool) -> list[CheckResult]:
    if not is_zone_apex:
        return unknown_for(DOMAIN_CHECKS, "not_zone_apex")
    return dns_checks.domain_checks(resolver, probe, domain)


def _web_checks(role: str) -> list[str]:
    return [c for c in WEB_CHECKS if role != "extra" or c not in MAIN_SITE_ONLY]


def _host(probe: Probe, host: str, role: str, hosts: set[str], now: datetime) -> list[CheckResult] | None:
    checks = _web_checks(role)
    try:
        address = probe.address(host)
    except TransientError:
        return None if role == "www" else unknown_for(checks, "no_address")
    except CollectorError as error:
        if error.code == "host_not_found":  # the name does not exist
            return None if role == "www" else unknown_for(checks, "no_address")
        return unknown_for(checks, "not_public")
    results = tls_checks.certificate_and_version(probe, host, address, now)
    results.append(tls_checks.legacy(probe, host, address))
    results.append(http_checks.https_redirect(probe, host, address))
    results += http_checks.header_checks(probe, host, address, hosts)
    if "security_txt" in checks:
        results.append(http_checks.security_txt(probe, host, address, now))
    return results


def _finished(task: concurrent.futures.Future, checks: list[str]) -> list[CheckResult] | None:
    if not task.done() or task.cancelled():
        return unknown_for(checks, "timeout")
    error = task.exception()
    if error is not None:
        # One host's failure must not cost the others their results. The message may name the host: log only.
        log.warning("an external-surface check failed: %s", type(error).__name__)
        return unknown_for(checks, "check_error")
    return task.result()
