from datetime import datetime, timezone

import pytest

from alinha_externalsurface import parsers


@pytest.mark.parametrize("record,policy", [
    ("v=spf1 include:_spf.google.com -all", "fail"),
    ("v=spf1 mx ~all", "softfail"),
    ("v=spf1 ?all", "neutral"),
    ("v=spf1 +all", "pass"),
    ("v=spf1 all", "pass"),
    ("v=spf1 redirect=_spf.acme.pt", "redirect"),
    ("v=spf1 mx", "none"),
    ("v=spf1 -all include:x.pt", "fail"),
    ("V=SPF1 MX -ALL", "fail"),
])
def test_spf_policy_is_the_all_mechanism(record, policy):
    assert parsers.parse_spf(record).policy == policy


def test_spf_records_ignore_other_txt():
    assert parsers.spf_records(("google-site-verification=x", "v=spf1 -all", "v=spf10")) == ["v=spf1 -all"]


@pytest.mark.parametrize("record,expected", [
    ("v=DMARC1; p=reject; rua=mailto:d@acme.pt", ("reject", 100, True)),
    ("v=DMARC1;p=quarantine;pct=50", ("quarantine", 50, False)),
    ("v=DMARC1; p=none", ("none", 100, False)),
    ("v=DMARC1; p=maybe", ("invalid", 100, False)),
    ("v=DMARC1; p=reject; pct=abc", ("invalid", 0, False)),
    ("v=DMARC1", ("invalid", 100, False)),
])
def test_dmarc(record, expected):
    found = parsers.parse_dmarc(record)
    assert (found.policy, found.pct, found.reports) == expected


def test_dmarc_records_need_the_version_first():
    assert parsers.dmarc_records(("v=DMARC1; p=none", "p=reject; v=DMARC1", "x")) == ["v=DMARC1; p=none"]


def test_dkim_key_needs_a_public_key():
    assert parsers.has_dkim_key(("v=DKIM1; k=rsa; p=MIGfMA0",))
    assert not parsers.has_dkim_key(("v=DKIM1; p=",))
    assert not parsers.has_dkim_key(("something else",))


def test_mta_sts():
    assert parsers.has_mta_sts_record(("v=STSv1; id=2026",))
    assert not parsers.has_mta_sts_record(("v=spf1 -all",))
    assert parsers.mta_sts_mode("version: STSv1\nmode: enforce\nmx: *.acme.pt\nmax_age: 86400") == "enforce"
    assert parsers.mta_sts_mode("version: STSv1\nmode: testing") == "testing"
    assert parsers.mta_sts_mode("version: STSv1\nmode: loud") is None
    assert parsers.mta_sts_mode("<html>not found</html>") is None


@pytest.mark.parametrize("header,expected", [
    ("max-age=31536000; includeSubDomains; preload", (31536000, True, True)),
    ('max-age="600"', (600, False, False)),
    ("includeSubDomains", (-1, True, False)),
    ("max-age=abc", (-1, False, False)),
])
def test_hsts(header, expected):
    found = parsers.parse_hsts(header)
    assert (found.max_age, found.include_subdomains, found.preload) == expected


def test_csp_directives():
    assert parsers.csp_directives("default-src 'self'; Frame-Ancestors 'none';") == {
        "default-src": "'self'", "frame-ancestors": "'none'"}


@pytest.mark.parametrize("header,policy", [
    ("no-referrer", "no-referrer"),
    ("unsafe-url, strict-origin-when-cross-origin", "strict-origin-when-cross-origin"),
    ("strict-origin, made-up", "strict-origin"),
    ("made-up", ""),
])
def test_referrer_policy_is_the_last_known_one(header, policy):
    assert parsers.referrer_policy(header) == policy


def test_security_txt():
    found = parsers.parse_security_txt("# hi\nContact: mailto:s@acme.pt\nExpires: 2027-01-01T00:00:00Z\n")
    assert found.has_contact
    assert found.expires == datetime(2027, 1, 1, tzinfo=timezone.utc)
    assert parsers.parse_security_txt("Expires: someday").expires is None
    assert parsers.parse_security_txt("Expires: 2027-01-01T00:00:00").expires.tzinfo == timezone.utc
    assert not parsers.parse_security_txt("").has_contact
