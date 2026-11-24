"""Checks of the domain's public DNS: email authentication (SPF, DKIM, DMARC, MTA-STS), CAA and DNSSEC."""
from __future__ import annotations

from typing import Callable

from alinha_job_consumer.collectors import CollectorError, TransientError

from . import parsers
from .dnsclient import DnsUnavailable, Resolver
from .model import CheckResult, failed, passed, unknown
from .probe import Probe

# Selectors used by the common mail services and servers: DKIM keys cannot be listed, only guessed.
DKIM_SELECTORS = ("default", "selector1", "selector2", "google", "k1", "k2", "s1", "s2", "dkim", "mail")
MTA_STS_MAX_BYTES = 64 * 1024


def _guarded(check: str, run: Callable[[], CheckResult]) -> CheckResult:
    try:
        return run()
    except DnsUnavailable:
        return unknown(check, "dns_unavailable")


def spf(resolver: Resolver, domain: str) -> CheckResult:
    def run() -> CheckResult:
        records = parsers.spf_records(resolver.lookup(domain, "TXT").values)
        if not records:
            return failed("spf", reason="missing")
        if len(records) > 1:
            return failed("spf", reason="multiple")  # receivers treat two records as an error
        policy = parsers.parse_spf(records[0]).policy
        if policy in ("fail", "softfail", "redirect"):
            return passed("spf", policy=policy)
        return failed("spf", policy=policy)
    return _guarded("spf", run)


def dmarc(resolver: Resolver, domain: str) -> CheckResult:
    def run() -> CheckResult:
        records = parsers.dmarc_records(resolver.lookup(f"_dmarc.{domain}", "TXT").values)
        if not records:
            return failed("dmarc", reason="missing")
        if len(records) > 1:
            return failed("dmarc", reason="multiple")
        record = parsers.parse_dmarc(records[0])
        details = {"policy": record.policy, "pct": record.pct, "reports": record.reports}
        if record.policy in ("quarantine", "reject") and record.pct == 100:
            return passed("dmarc", **details)
        return failed("dmarc", **details)
    return _guarded("dmarc", run)


def dkim(resolver: Resolver, domain: str) -> CheckResult:
    """PASS when a common selector has a key. Not finding one proves nothing (the selector may be another), so
    it is UNKNOWN, never FAIL."""
    unanswered = 0
    for selector in DKIM_SELECTORS:
        try:
            if parsers.has_dkim_key(resolver.lookup(f"{selector}._domainkey.{domain}", "TXT").values):
                return passed("dkim", selector=selector)
        except DnsUnavailable:
            unanswered += 1
    return unknown("dkim", "dns_unavailable" if unanswered == len(DKIM_SELECTORS) else "no_known_selector")


def mta_sts(resolver: Resolver, probe: Probe, domain: str) -> CheckResult:
    def run() -> CheckResult:
        if not resolver.lookup(domain, "MX").values:
            return unknown("mta_sts", "no_mail")
        if not parsers.has_mta_sts_record(resolver.lookup(f"_mta-sts.{domain}", "TXT").values):
            return failed("mta_sts", reason="missing")
        host = f"mta-sts.{domain}"
        try:
            address = probe.address(host)
            answer = probe.get(host, address, "/.well-known/mta-sts.txt", MTA_STS_MAX_BYTES)
        except TransientError:
            return unknown("mta_sts", "unreachable")
        except (CollectorError, OSError):  # not public, invalid certificate (ssl.SSLError), refused
            return failed("mta_sts", reason="policy_unavailable")
        mode = parsers.mta_sts_mode(answer.body.decode("utf-8", "replace")) if answer.status == 200 else None
        if mode is None:
            return failed("mta_sts", reason="policy_unavailable")
        return passed("mta_sts", mode=mode) if mode == "enforce" else failed("mta_sts", mode=mode)
    return _guarded("mta_sts", run)


def caa(resolver: Resolver, domain: str) -> CheckResult:
    """Informational: which certificate authorities may issue for the domain."""
    return _guarded("caa", lambda: passed("caa") if resolver.lookup(domain, "CAA").values
                    else failed("caa", reason="missing"))


def dnssec(resolver: Resolver, domain: str) -> CheckResult:
    """Informational: a DS record in the parent zone means the domain is signed."""
    return _guarded("dnssec", lambda: passed("dnssec") if resolver.lookup(domain, "DS").values
                    else failed("dnssec", reason="unsigned"))


def domain_checks(resolver: Resolver, probe: Probe, domain: str) -> list[CheckResult]:
    return [spf(resolver, domain), dkim(resolver, domain), dmarc(resolver, domain),
            mta_sts(resolver, probe, domain), caa(resolver, domain), dnssec(resolver, domain)]
