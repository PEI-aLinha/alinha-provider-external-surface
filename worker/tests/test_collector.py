import json

import pytest

from alinha_job_consumer.collectors import CollectorError, TransientError
from alinha_externalsurface.collector import collect
from alinha_externalsurface.model import DOMAIN_CHECKS, WEB_CHECKS
from fakes import NX, TIMEOUT, FakeHost, FakeProbe, FakeResolver, secure_host

D = "acme.pt"
APEX = {(D, "SOA"): ("ns1.acme.pt. hostmaster.acme.pt. 1 7200 3600 1209600 300",)}


def job(**scope):
    return {"jobId": "1b7f0c5e-2a64-4d1e-9a77-1f0c2d3b4a5c", "type": "evidence.collect",
            "tenantId": "0d9f3a5e-4b8c-4c2d-8e1f-7a6b5c4d3e2f", "provider": "external-surface",
            "connectionRef": "providers/external-surface", "requestedAt": "2026-12-15T10:00:00Z",
            "scope": {"domain": D, **scope}}


def run(resolver=None, probe=None, **kwargs):
    return collect(job(**kwargs.pop("scope", {})), resolver=resolver or FakeResolver(APEX),
                   probe=probe or FakeProbe({D: secure_host(), f"www.{D}": secure_host()}), **kwargs)


def test_result_shape():
    result = run()
    assert result["schema"] == "external-surface.result.v1"
    assert result["domain"] == D and result["truncated"] is False
    assert [c["check"] for c in result["domainChecks"]] == list(DOMAIN_CHECKS)
    assert [(h["host"], h["role"]) for h in result["hosts"]] == [(D, "primary"), (f"www.{D}", "www")]
    assert [c["check"] for c in result["hosts"][0]["checks"]] == [
        "tls_certificate", "tls_version", "tls_legacy", "https_redirect", "hsts", "csp", "content_type_options",
        "frame_protection", "referrer_policy", "security_txt"]
    assert sorted(c["check"] for c in result["hosts"][0]["checks"]) == sorted(WEB_CHECKS)
    assert len(json.dumps(result)) < 1024 * 1024


def test_a_secure_site_passes_every_web_check():
    outcomes = {c["outcome"] for h in run()["hosts"] for c in h["checks"]}
    assert outcomes == {"PASS"}


def test_extra_hosts_get_web_checks_without_security_txt():
    probe = FakeProbe({D: secure_host(), "loja.acme.pt": secure_host()})
    result = run(probe=probe, scope={"hosts": ["loja.acme.pt"]})
    extra = next(h for h in result["hosts"] if h["role"] == "extra")
    assert "security_txt" not in [c["check"] for c in extra["checks"]]


def test_www_that_does_not_resolve_is_left_out_but_an_extra_host_is_unknown():
    probe = FakeProbe({D: secure_host()})
    result = run(probe=probe, scope={"hosts": ["loja.acme.pt"]})
    assert [h["host"] for h in result["hosts"]] == [D, "loja.acme.pt"]
    assert {c["details"]["reason"] for c in result["hosts"][1]["checks"]} == {"no_address"}


def test_a_name_that_does_not_exist_is_like_one_that_does_not_resolve():
    missing = FakeHost(address=CollectorError("host_not_found", "x"))
    probe = FakeProbe({D: secure_host(), f"www.{D}": missing, "loja.acme.pt": missing})
    result = run(probe=probe, scope={"hosts": ["loja.acme.pt"]})
    assert [h["host"] for h in result["hosts"]] == [D, "loja.acme.pt"]
    assert {c["details"]["reason"] for c in result["hosts"][1]["checks"]} == {"no_address"}


def test_a_host_on_a_private_address_is_not_contacted():
    private = FakeHost(address=CollectorError("host_not_allowed", "x"))
    probe = FakeProbe({D: private})
    result = run(probe=probe)
    assert {c["details"]["reason"] for c in result["hosts"][0]["checks"]} == {"not_public"}
    assert not any(call[0] != "address" for call in probe.calls if call[1] == D)


def test_email_checks_need_the_zone_apex():
    result = run(resolver=FakeResolver({(D, "SOA"): ()}))
    assert {c["details"]["reason"] for c in result["domainChecks"]} == {"not_zone_apex"}


def test_a_domain_that_does_not_exist_is_a_final_error():
    with pytest.raises(CollectorError) as error:
        run(resolver=FakeResolver({(D, "SOA"): NX}))
    assert error.value.code == "domain_not_found"


def test_dns_that_does_not_answer_is_retried():
    with pytest.raises(TransientError):
        run(resolver=FakeResolver({(D, "SOA"): TIMEOUT}))


def test_checks_past_the_budget_are_unknown():
    result = run(probe=FakeProbe({D: secure_host()}, delay=0.5), budget_s=0.05)
    assert {c["details"]["reason"] for c in result["hosts"][0]["checks"]} == {"timeout"}


def test_a_broken_check_does_not_cost_the_others_their_results():
    class Broken(FakeProbe):
        def location(self, host, address):
            if host == D:
                raise RuntimeError("bug")
            return super().location(host, address)

    result = run(probe=Broken({D: secure_host(), f"www.{D}": secure_host()}))
    assert {c["details"].get("reason") for c in result["hosts"][0]["checks"]} == {"check_error"}
    assert {c["outcome"] for c in result["hosts"][1]["checks"]} == {"PASS"}


def test_only_its_own_provider():
    with pytest.raises(CollectorError) as error:
        collect({**job(), "provider": "code-repository"})
    assert error.value.code == "unknown_provider"


def test_invalid_scope_never_reaches_the_network():
    probe = FakeProbe()
    with pytest.raises(CollectorError):
        collect({**job(), "scope": {"domain": "10.0.0.1"}}, resolver=FakeResolver(APEX), probe=probe)
    assert probe.calls == []
