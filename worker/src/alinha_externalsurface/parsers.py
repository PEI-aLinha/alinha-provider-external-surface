"""Pure parsers for what hosts publish (DNS records, headers, policy files). Their input is untrusted: they only
ever return short codes, numbers and flags, never the text they read."""
from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timezone


# -- SPF (RFC 7208) -----------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Spf:
    # "fail" (-all), "softfail" (~all), "neutral" (?all), "pass" (+all), "redirect", or "none" (no all, no redirect)
    policy: str


def spf_records(txt: tuple[str, ...]) -> list[str]:
    return [t for t in txt if re.fullmatch(r"v=spf1(\s.*)?", t.strip(), re.IGNORECASE)]


def parse_spf(record: str) -> Spf:
    qualifiers = {"-": "fail", "~": "softfail", "?": "neutral", "+": "pass"}
    redirect = False
    for term in record.split()[1:]:
        term = term.lower()
        if term.startswith("redirect="):
            redirect = True
            continue
        qualifier = term[0] if term[0] in qualifiers else "+"
        if term.lstrip("-~?+") == "all":
            return Spf(qualifiers[qualifier])  # nothing after "all" is ever evaluated
    return Spf("redirect" if redirect else "none")


# -- DMARC (RFC 7489) ---------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Dmarc:
    policy: str  # "none", "quarantine", "reject" or "invalid"
    pct: int
    reports: bool


def tags(record: str) -> dict[str, str]:
    found: dict[str, str] = {}
    for part in record.split(";"):
        name, sep, value = part.partition("=")
        if sep and name.strip():
            found.setdefault(name.strip().lower(), value.strip())
    return found


def dmarc_records(txt: tuple[str, ...]) -> list[str]:
    return [t for t in txt if re.match(r"\s*v\s*=\s*DMARC1\s*(;|$)", t, re.IGNORECASE)]


def parse_dmarc(record: str) -> Dmarc:
    found = tags(record)
    policy = found.get("p", "").lower()
    pct_text = found.get("pct", "100")
    pct = int(pct_text) if pct_text.isdigit() and int(pct_text) <= 100 else -1
    if policy not in ("none", "quarantine", "reject") or pct < 0:
        policy = "invalid"
    return Dmarc(policy, max(pct, 0), "rua" in found)


# -- DKIM (RFC 6376) ----------------------------------------------------------------------------------------------

def has_dkim_key(txt: tuple[str, ...]) -> bool:
    """A key record with a public key (`p=` empty means the key was revoked)."""
    return any(tags(t).get("p", "") for t in txt if "p=" in t.replace(" ", ""))


# -- MTA-STS (RFC 8461) -------------------------------------------------------------------------------------------

def has_mta_sts_record(txt: tuple[str, ...]) -> bool:
    return any(re.match(r"\s*v\s*=\s*STSv1\s*(;|$)", t, re.IGNORECASE) for t in txt)


def mta_sts_mode(policy: str) -> str | None:
    """`enforce`, `testing` or `none`; None when the file is not a valid policy."""
    fields = _key_values(policy, ":")
    if fields.get("version", [""])[0] != "STSv1":
        return None
    mode = fields.get("mode", [""])[0]
    return mode if mode in ("enforce", "testing", "none") else None


# -- HTTP headers -------------------------------------------------------------------------------------------------

HSTS_MIN_SECONDS = 15_552_000  # 180 days
SAFE_REFERRER_POLICIES = ("no-referrer", "same-origin", "strict-origin", "strict-origin-when-cross-origin")


@dataclass(frozen=True)
class Hsts:
    max_age: int  # seconds; -1 when invalid
    include_subdomains: bool
    preload: bool


def parse_hsts(header: str) -> Hsts:
    found: dict[str, str] = {}
    for part in header.split(";"):
        name, _, value = part.partition("=")
        found.setdefault(name.strip().lower(), value.strip().strip('"'))
    max_age = found.get("max-age", "")
    return Hsts(int(max_age) if max_age.isdigit() else -1, "includesubdomains" in found, "preload" in found)


def csp_directives(header: str) -> dict[str, str]:
    directives: dict[str, str] = {}
    for part in header.split(";"):
        name, _, value = part.strip().partition(" ")
        if name:
            directives.setdefault(name.lower(), value.strip())
    return directives


def referrer_policy(header: str) -> str:
    """The policy browsers apply: the last one they understand in a comma-separated list."""
    known = ("no-referrer", "no-referrer-when-downgrade", "origin", "origin-when-cross-origin", "same-origin",
             "strict-origin", "strict-origin-when-cross-origin", "unsafe-url")
    applied = ""
    for token in header.split(","):
        token = token.strip().lower()
        if token in known:
            applied = token
    return applied


# -- security.txt (RFC 9116) --------------------------------------------------------------------------------------

@dataclass(frozen=True)
class SecurityTxt:
    has_contact: bool
    expires: datetime | None  # None when missing or not a valid date


def parse_security_txt(text: str) -> SecurityTxt:
    fields = _key_values(text, ":")
    expires = None
    for value in fields.get("expires", [])[:1]:
        try:
            expires = datetime.fromisoformat(value.replace("Z", "+00:00"))
            if expires.tzinfo is None:
                expires = expires.replace(tzinfo=timezone.utc)
        except ValueError:
            expires = None
    return SecurityTxt(bool(fields.get("contact")), expires)


def _key_values(text: str, separator: str) -> dict[str, list[str]]:
    fields: dict[str, list[str]] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        name, sep, value = line.partition(separator)
        if sep:
            fields.setdefault(name.strip().lower(), []).append(value.strip())
    return fields
