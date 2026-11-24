import ssl
from datetime import datetime, timezone

from alinha_job_consumer.collectors import TransientError
from alinha_job_consumer.net import TlsInfo
from alinha_externalsurface import tls_checks
from fakes import FakeHost, FakeProbe, cert_error, ssl_error

NOW = datetime(2026, 12, 15, tzinfo=timezone.utc)
H = "www.acme.pt"


def run(**tls):
    host = FakeHost()
    host.tls = {**host.tls, **tls}
    results = tls_checks.certificate_and_version(FakeProbe({H: host}), H, "8.8.8.8", NOW)
    return [(r.check, r.outcome.value, r.details) for r in results]


def test_valid_certificate_and_modern_version():
    assert run() == [("tls_certificate", "PASS", {"daysLeft": 17, "expiresSoon": True}),
                     ("tls_version", "PASS", {"version": "tls1_3"})]


def test_a_certificate_far_from_expiry_is_not_flagged():
    far = TlsInfo("TLSv1.2", {"notAfter": "Jan  1 00:00:00 2028 GMT"})
    assert run(verify=far)[0] == ("tls_certificate", "PASS", {"daysLeft": 382, "expiresSoon": False})
    assert run(verify=far)[1][2] == {"version": "tls1_2"}


def test_invalid_certificates_say_why_and_still_report_the_version():
    assert run(verify=cert_error(10))[0][1:] == ("FAIL", {"reason": "expired"})
    assert run(verify=cert_error(62))[0][1:] == ("FAIL", {"reason": "hostname_mismatch"})
    assert run(verify=cert_error(19))[0][1:] == ("FAIL", {"reason": "untrusted"})
    assert run(verify=cert_error(7))[0][1:] == ("FAIL", {"reason": "invalid"})
    assert run(verify=cert_error(10))[1] == ("tls_version", "PASS", {"version": "tls1_3"})
    assert run(verify=cert_error(10), inspect=ssl_error("X"))[1][1:] == ("FAIL", {"reason": "no_modern_tls"})
    assert run(verify=cert_error(10), inspect=TransientError("x"))[1][1:] == ("UNKNOWN", {"reason": "unreachable"})


def test_expired_by_date_and_missing_expiry():
    expired = TlsInfo("TLSv1.3", {"notAfter": "Jan  1 00:00:00 2026 GMT"})
    assert run(verify=expired)[0][1:] == ("FAIL", {"reason": "expired"})
    assert run(verify=TlsInfo("TLSv1.3", {}))[0][1:] == ("UNKNOWN", {"reason": "no_expiry"})


def test_no_modern_tls_and_unreachable():
    assert [r[1] for r in run(verify=ssl_error("WRONG_VERSION_NUMBER"))] == ["UNKNOWN", "FAIL"]
    assert [r[1] for r in run(verify=TransientError("x"))] == ["UNKNOWN", "UNKNOWN"]


def legacy(answer, factory=tls_checks.legacy_context):
    host = FakeHost()
    host.tls = {**host.tls, "legacy": answer}
    result = tls_checks.legacy(FakeProbe({H: host}), H, "8.8.8.8", factory)
    return result.outcome.value, result.details


def test_legacy_tls():
    assert legacy(ssl_error("TLSV1_ALERT_PROTOCOL_VERSION")) == ("PASS", {})
    assert legacy(TlsInfo("TLSv1")) == ("FAIL", {"accepted": "tls1"})
    assert legacy(TlsInfo("TLSv1.1")) == ("FAIL", {"accepted": "tls1_1"})
    assert legacy(TransientError("x")) == ("UNKNOWN", {"reason": "unreachable"})


def test_legacy_tls_never_passes_when_this_side_cannot_offer_it():
    assert legacy(ssl_error("NO_PROTOCOLS_AVAILABLE")) == ("UNKNOWN", {"reason": "probe_unavailable"})
    assert legacy(TlsInfo("TLSv1"), factory=lambda: None) == ("UNKNOWN", {"reason": "probe_unavailable"})


def test_contexts():
    assert tls_checks.verifying_context().verify_mode == ssl.CERT_REQUIRED
    assert tls_checks.verifying_context().minimum_version == ssl.TLSVersion.TLSv1_2
    assert tls_checks.inspecting_context().verify_mode == ssl.CERT_NONE
    context = tls_checks.legacy_context()
    assert context is None or context.maximum_version == ssl.TLSVersion.TLSv1_1
