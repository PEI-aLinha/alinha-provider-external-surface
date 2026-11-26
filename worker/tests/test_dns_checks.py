import ssl

from alinha_job_consumer.collectors import CollectorError, TransientError
from alinha_job_consumer.net import Response
from alinha_externalsurface import dns_checks
from fakes import NX, TIMEOUT, FakeHost, FakeProbe, FakeResolver

D = "acme.pt"


def outcome(result):
    return result.outcome.value, result.details


def test_spf():
    assert outcome(dns_checks.spf(FakeResolver({(D, "TXT"): ("v=spf1 mx -all",)}), D)) == ("PASS", {"policy": "fail"})
    assert outcome(dns_checks.spf(FakeResolver({(D, "TXT"): ("v=spf1 ?all",)}), D)) == ("FAIL", {"policy": "neutral"})
    assert outcome(dns_checks.spf(FakeResolver(), D)) == ("FAIL", {"reason": "missing"})
    assert outcome(dns_checks.spf(FakeResolver({(D, "TXT"): ("v=spf1 -all", "v=spf1 ~all")}), D)) == (
        "FAIL", {"reason": "multiple"})
    assert outcome(dns_checks.spf(FakeResolver({(D, "TXT"): TIMEOUT}), D)) == ("UNKNOWN", {"reason": "dns_unavailable"})


def test_dmarc():
    name = f"_dmarc.{D}"
    assert dns_checks.dmarc(FakeResolver({(name, "TXT"): ("v=DMARC1; p=reject",)}), D).outcome.value == "PASS"
    assert outcome(dns_checks.dmarc(FakeResolver({(name, "TXT"): ("v=DMARC1; p=none",)}), D))[0] == "FAIL"
    assert outcome(dns_checks.dmarc(FakeResolver({(name, "TXT"): ("v=DMARC1; p=reject; pct=20",)}), D))[0] == "FAIL"
    assert outcome(dns_checks.dmarc(FakeResolver({(name, "TXT"): NX}), D)) == ("FAIL", {"reason": "missing"})
    assert outcome(dns_checks.dmarc(FakeResolver({(name, "TXT"): ("v=DMARC1; p=none", "v=DMARC1; p=reject")}), D)) == (
        "FAIL", {"reason": "multiple"})


def test_dkim_finds_a_common_selector_and_never_fails():
    found = FakeResolver({(f"selector2._domainkey.{D}", "TXT"): ("v=DKIM1; p=MIIB",)})
    assert outcome(dns_checks.dkim(found, D)) == ("PASS", {"selector": "selector2"})
    assert outcome(dns_checks.dkim(FakeResolver(), D)) == ("UNKNOWN", {"reason": "no_known_selector"})
    everything_times_out = FakeResolver({(f"{s}._domainkey.{D}", "TXT"): TIMEOUT for s in dns_checks.DKIM_SELECTORS})
    assert outcome(dns_checks.dkim(everything_times_out, D)) == ("UNKNOWN", {"reason": "dns_unavailable"})


def mta(records, host=None):
    resolver = FakeResolver({(D, "MX"): ("10 mail.acme.pt.",), **records})
    return outcome(dns_checks.mta_sts(resolver, FakeProbe({f"mta-sts.{D}": host or FakeHost()}), D))


def policy(text):
    return FakeHost(pages={"/.well-known/mta-sts.txt": Response(200, {}, text.encode())})


def test_mta_sts():
    record = {(f"_mta-sts.{D}", "TXT"): ("v=STSv1; id=1",)}
    assert mta(record, policy("version: STSv1\nmode: enforce")) == ("PASS", {"mode": "enforce"})
    assert mta(record, policy("version: STSv1\nmode: testing")) == ("FAIL", {"mode": "testing"})
    assert mta(record, policy("nonsense")) == ("FAIL", {"reason": "policy_unavailable"})
    assert mta(record, FakeHost()) == ("FAIL", {"reason": "policy_unavailable"})  # 404
    assert mta(record, FakeHost(address=TransientError("x"))) == ("UNKNOWN", {"reason": "unreachable"})
    assert mta(record, FakeHost(address=CollectorError("host_not_allowed", "x"))) == (
        "FAIL", {"reason": "policy_unavailable"})
    bad_cert = FakeHost(pages={"/.well-known/mta-sts.txt": ssl.SSLCertVerificationError("bad")})
    assert mta(record, bad_cert) == ("FAIL", {"reason": "policy_unavailable"})
    assert mta({}) == ("FAIL", {"reason": "missing"})


def test_mta_sts_does_not_apply_without_mail():
    assert outcome(dns_checks.mta_sts(FakeResolver(), FakeProbe(), D)) == ("UNKNOWN", {"reason": "no_mail"})


def test_caa_and_dnssec_are_read_from_dns():
    signed = FakeResolver({(D, "CAA"): ('0 issue "letsencrypt.org"',), (D, "DS"): ("2371 13 2 ABCD",)})
    assert [r.outcome.value for r in (dns_checks.caa(signed, D), dns_checks.dnssec(signed, D))] == ["PASS", "PASS"]
    bare = FakeResolver()
    assert outcome(dns_checks.caa(bare, D)) == ("FAIL", {"reason": "missing"})
    assert outcome(dns_checks.dnssec(bare, D)) == ("FAIL", {"reason": "unsigned"})


def test_domain_checks_returns_one_result_per_check():
    checks = [r.check for r in dns_checks.domain_checks(FakeResolver(), FakeProbe(), D)]
    assert checks == ["spf", "dkim", "dmarc", "mta_sts", "caa", "dnssec"]
