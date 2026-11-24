import pytest

from alinha_job_consumer.collectors import CollectorError
from alinha_externalsurface.model import CheckResult, Outcome, clean_details
from alinha_externalsurface.scope import parse_scope


def test_scope_lists_the_domain_www_and_the_extra_hosts_once():
    scope = parse_scope({"scope": {"domain": "acme.pt", "hosts": ["loja.acme.pt", "www.acme.pt", "loja.acme.pt"]}})
    assert scope.web_hosts() == [("acme.pt", "primary"), ("www.acme.pt", "www"), ("loja.acme.pt", "extra")]


def test_hosts_are_optional():
    assert parse_scope({"scope": {"domain": "acme.pt"}}).extra_hosts == ()


@pytest.mark.parametrize("scope", [
    None, {}, {"domain": "ACME.pt"}, {"domain": "10.0.0.1"}, {"domain": "acme"}, {"domain": 3},
    {"domain": "acme.pt", "hosts": "loja.acme.pt"}, {"domain": "acme.pt", "hosts": ["evil.example"]},
    {"domain": "acme.pt", "hosts": ["notacme.pt"]}, {"domain": "acme.pt", "hosts": ["loja.acme.pt:8443"]},
    {"domain": "acme.pt", "hosts": [f"h{i}.acme.pt" for i in range(11)]},
])
def test_refuses_anything_else(scope):
    with pytest.raises(CollectorError) as error:
        parse_scope({"scope": scope})
    assert error.value.code == "invalid_scope"


def test_details_keep_only_short_codes_numbers_and_flags():
    raw = {"policy": "reject", "maxAgeDays": 10**9, "negative": -5, "ok": True, "raw": "v=spf1 -all",
           "bad key": "x", "pct": 100, "a": 1, "b": 2, "c": 3, "d": 4}
    assert clean_details(raw) == {"policy": "reject", "maxAgeDays": 100_000, "negative": 0, "ok": True,
                                  "pct": 100, "a": 1, "b": 2, "c": 3}


def test_check_result_json():
    assert CheckResult("hsts", Outcome.FAIL, {"reason": "missing"}).to_json() == {
        "check": "hsts", "outcome": "FAIL", "details": {"reason": "missing"}}
