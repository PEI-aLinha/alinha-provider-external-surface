"""What a check produces, and the limits the core enforces on it (CollectionResultParser.kt)."""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import Enum
from typing import Any

PROVIDER_ID = "external-surface"
RESULT_SCHEMA = "external-surface.result.v1"


class Outcome(str, Enum):
    PASS = "PASS"
    FAIL = "FAIL"
    # Could not be checked (timeout, no answer). Never evidence for a control, so never reported as FAIL.
    UNKNOWN = "UNKNOWN"


# Checked once for the domain (email and DNS).
DOMAIN_CHECKS = ("spf", "dkim", "dmarc", "mta_sts", "caa", "dnssec")
# Checked for every web host (the domain, www and the extra hosts).
WEB_CHECKS = ("tls_version", "tls_legacy", "tls_certificate", "https_redirect", "hsts", "csp",
              "content_type_options", "frame_protection", "referrer_policy", "security_txt")
# security.txt only belongs on the organization's main site.
MAIN_SITE_ONLY = ("security_txt",)

_KEY = re.compile(r"[a-zA-Z]{1,40}")
_TEXT = re.compile(r"[a-z0-9_]{1,40}")
MAX_DETAILS = 8
MAX_NUMBER = 100_000

Scalar = bool | int | str


def clean_details(details: dict[str, Any]) -> dict[str, Scalar]:
    """Keeps only what the core accepts: at most 8 short keys, booleans, small whole numbers and short codes.
    Raw values from the host (headers, DNS records) never get through."""
    kept: dict[str, Scalar] = {}
    for key, value in details.items():
        if len(kept) == MAX_DETAILS or not _KEY.fullmatch(key):
            continue
        if isinstance(value, bool):
            kept[key] = value
        elif isinstance(value, int):
            kept[key] = max(0, min(value, MAX_NUMBER))
        elif isinstance(value, str) and _TEXT.fullmatch(value):
            kept[key] = value
    return kept


@dataclass(frozen=True)
class CheckResult:
    check: str
    outcome: Outcome
    details: dict[str, Scalar] = field(default_factory=dict)

    def to_json(self) -> dict[str, Any]:
        return {"check": self.check, "outcome": self.outcome.value, "details": clean_details(self.details)}


def passed(check: str, **details: Any) -> CheckResult:
    return CheckResult(check, Outcome.PASS, dict(details))


def failed(check: str, **details: Any) -> CheckResult:
    return CheckResult(check, Outcome.FAIL, dict(details))


def unknown(check: str, reason: str) -> CheckResult:
    return CheckResult(check, Outcome.UNKNOWN, {"reason": reason})


def unknown_for(checks: tuple[str, ...] | list[str], reason: str) -> list[CheckResult]:
    return [unknown(check, reason) for check in checks]
