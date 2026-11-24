"""HTTP checks of one web host: plain http sends visitors to https, the security headers of the home page, and
security.txt. Only the home page and two small well-known files are fetched: nothing is crawled."""
from __future__ import annotations

import ssl
from datetime import datetime
from urllib.parse import urlsplit

from alinha_job_consumer.collectors import CollectorError, TransientError
from alinha_job_consumer.net import Response

from . import parsers
from .model import CheckResult, failed, passed, unknown
from .probe import Probe

HEADER_CHECKS = ("hsts", "csp", "content_type_options", "frame_protection", "referrer_policy")
REDIRECTS = (301, 302, 303, 307, 308)
HOME_MAX_BYTES = 0  # only the status and headers matter
SECURITY_TXT_MAX_BYTES = 32 * 1024


def https_redirect(probe: Probe, host: str, address: str) -> CheckResult:
    try:
        status, location = probe.location(host, address)
    except ConnectionRefusedError:
        return passed("https_redirect", httpOpen=False)  # nothing served over plain http
    except TransientError:
        return unknown("https_redirect", "unreachable")
    if status in REDIRECTS and location and urlsplit(location).scheme.lower() == "https":
        return passed("https_redirect", httpOpen=True)
    return failed("https_redirect", status=status)


def _home(probe: Probe, host: str, address: str, hosts: set[str]) -> Response:
    """The home page's answer, after at most one redirect to https on a host of this check."""
    answer = probe.get(host, address, "/", HOME_MAX_BYTES)
    target = urlsplit(answer.headers.get("location", "")) if answer.status in REDIRECTS else None
    if target and target.scheme == "https" and (target.hostname or "") in hosts and not target.port:
        next_host = target.hostname or host
        next_address = address if next_host == host else probe.address(next_host)
        path = target.path or "/"
        return probe.get(next_host, next_address, path + (f"?{target.query}" if target.query else ""), HOME_MAX_BYTES)
    return answer


def header_checks(probe: Probe, host: str, address: str, hosts: set[str]) -> list[CheckResult]:
    try:
        answer = _home(probe, host, address, hosts)
    except ssl.SSLError:
        return [unknown(check, "tls_invalid") for check in HEADER_CHECKS]
    except (TransientError, CollectorError, OSError):
        return [unknown(check, "unreachable") for check in HEADER_CHECKS]
    return evaluate_headers(answer.headers)


def evaluate_headers(headers: dict[str, str] | object) -> list[CheckResult]:
    get = headers.get  # type: ignore[attr-defined]
    results: list[CheckResult] = []

    hsts_header = get("strict-transport-security")
    if not hsts_header:
        results.append(failed("hsts", reason="missing"))
    else:
        hsts = parsers.parse_hsts(hsts_header)
        details = {"maxAgeDays": max(hsts.max_age, 0) // 86400, "includeSubdomains": hsts.include_subdomains}
        ok = hsts.max_age >= parsers.HSTS_MIN_SECONDS
        results.append(passed("hsts", **details) if ok else failed("hsts", **details))

    csp_header = get("content-security-policy")
    directives = parsers.csp_directives(csp_header or "")
    if csp_header:
        results.append(passed("csp", frameAncestors="frame-ancestors" in directives))
    else:
        reason = "report_only" if get("content-security-policy-report-only") else "missing"
        results.append(failed("csp", reason=reason))

    nosniff = (get("x-content-type-options") or "").strip().lower() == "nosniff"
    results.append(passed("content_type_options") if nosniff else failed("content_type_options", reason="missing"))

    frame_options = (get("x-frame-options") or "").strip().lower()
    if "frame-ancestors" in directives:
        results.append(passed("frame_protection", source="csp"))
    elif frame_options in ("deny", "sameorigin"):
        results.append(passed("frame_protection", source="x_frame_options"))
    else:
        results.append(failed("frame_protection", reason="missing"))

    policy = parsers.referrer_policy(get("referrer-policy") or "")
    if not policy:
        results.append(failed("referrer_policy", reason="missing"))
    elif policy in parsers.SAFE_REFERRER_POLICIES:
        results.append(passed("referrer_policy", policy=policy.replace("-", "_")))
    else:
        results.append(failed("referrer_policy", policy=policy.replace("-", "_")))
    return results


def security_txt(probe: Probe, host: str, address: str, now: datetime) -> CheckResult:
    try:
        answer = probe.get(host, address, "/.well-known/security.txt", SECURITY_TXT_MAX_BYTES)
    except ssl.SSLError:
        return unknown("security_txt", "tls_invalid")
    except (TransientError, CollectorError, OSError):
        return unknown("security_txt", "unreachable")
    if answer.status != 200:
        return failed("security_txt", reason="missing")
    found = parsers.parse_security_txt(answer.body.decode("utf-8", "replace"))
    if not found.has_contact:
        return failed("security_txt", reason="no_contact")
    if found.expires is None:
        return failed("security_txt", reason="no_expiry")
    if found.expires < now:
        return failed("security_txt", reason="expired")
    return passed("security_txt")
