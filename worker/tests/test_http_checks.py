import ssl
from datetime import datetime, timezone

from alinha_job_consumer.collectors import TransientError
from alinha_job_consumer.net import Response
from alinha_externalsurface import http_checks
from fakes import SECURE_HEADERS, FakeHost, FakeProbe, secure_host

NOW = datetime(2026, 12, 15, tzinfo=timezone.utc)
H = "acme.pt"
HOSTS = {"acme.pt", "www.acme.pt"}


def redirect(location):
    result = http_checks.https_redirect(FakeProbe({H: FakeHost(location=location)}), H, "8.8.8.8")
    return result.outcome.value, result.details


def test_https_redirect():
    assert redirect((301, "https://www.acme.pt/")) == ("PASS", {"httpOpen": True})
    assert redirect((308, "HTTPS://acme.pt/x")) == ("PASS", {"httpOpen": True})
    assert redirect(ConnectionRefusedError()) == ("PASS", {"httpOpen": False})
    assert redirect((200, None)) == ("FAIL", {"status": 200})
    assert redirect((302, "http://acme.pt/")) == ("FAIL", {"status": 302})
    assert redirect(TransientError("x")) == ("UNKNOWN", {"reason": "unreachable"})


def headers(page_headers):
    return {r.check: (r.outcome.value, r.details) for r in http_checks.evaluate_headers(page_headers)}


def test_secure_headers_pass():
    assert headers(SECURE_HEADERS) == {
        "hsts": ("PASS", {"maxAgeDays": 365, "includeSubdomains": True}),
        "csp": ("PASS", {"frameAncestors": True}),
        "content_type_options": ("PASS", {}),
        "frame_protection": ("PASS", {"source": "csp"}),
        "referrer_policy": ("PASS", {"policy": "strict_origin_when_cross_origin"}),
    }


def test_missing_headers_fail():
    assert {k: v[0] for k, v in headers({}).items()} == dict.fromkeys(http_checks.HEADER_CHECKS, "FAIL")


def test_weak_headers_fail():
    found = headers({"strict-transport-security": "max-age=600", "content-security-policy-report-only": "x",
                     "x-frame-options": "SAMEORIGIN", "referrer-policy": "unsafe-url"})
    assert found["hsts"] == ("FAIL", {"maxAgeDays": 0, "includeSubdomains": False})
    assert found["csp"] == ("FAIL", {"reason": "report_only"})
    assert found["frame_protection"] == ("PASS", {"source": "x_frame_options"})
    assert found["referrer_policy"] == ("FAIL", {"policy": "unsafe_url"})


def test_headers_follow_one_redirect_inside_the_checked_hosts():
    apex = FakeHost(pages={"/": Response(301, {"location": "https://www.acme.pt/"})})
    probe = FakeProbe({H: apex, "www.acme.pt": secure_host()})
    results = http_checks.header_checks(probe, H, "8.8.8.8", HOSTS)
    assert all(r.outcome.value == "PASS" for r in results)
    assert ("get", "www.acme.pt", "/") in probe.calls


def test_headers_do_not_follow_a_redirect_elsewhere():
    apex = FakeHost(pages={"/": Response(302, {"location": "https://evil.example/"})})
    probe = FakeProbe({H: apex})
    results = http_checks.header_checks(probe, H, "8.8.8.8", HOSTS)
    assert all(r.outcome.value == "FAIL" for r in results)
    assert not any(c[1] == "evil.example" for c in probe.calls)


def test_headers_unknown_when_the_page_cannot_be_read():
    tls = http_checks.header_checks(FakeProbe({H: FakeHost(pages={"/": ssl.SSLError("x")})}), H, "1.1.1.1", HOSTS)
    down = http_checks.header_checks(FakeProbe({H: FakeHost(pages={"/": TransientError("x")})}), H, "1.1.1.1", HOSTS)
    assert {r.details["reason"] for r in tls} == {"tls_invalid"}
    assert {r.details["reason"] for r in down} == {"unreachable"}


def sectxt(page):
    result = http_checks.security_txt(FakeProbe({H: FakeHost(pages={"/.well-known/security.txt": page})}), H,
                                      "8.8.8.8", NOW)
    return result.outcome.value, result.details


def test_security_txt():
    def ok(text):
        return Response(200, {}, text.encode())
    assert sectxt(ok("Contact: mailto:s@acme.pt\nExpires: 2027-06-01T00:00:00Z")) == ("PASS", {})
    assert sectxt(ok("Contact: mailto:s@acme.pt\nExpires: 2026-01-01T00:00:00Z")) == ("FAIL", {"reason": "expired"})
    assert sectxt(ok("Contact: mailto:s@acme.pt")) == ("FAIL", {"reason": "no_expiry"})
    assert sectxt(ok("Expires: 2027-06-01T00:00:00Z")) == ("FAIL", {"reason": "no_contact"})
    assert sectxt(Response(404, {})) == ("FAIL", {"reason": "missing"})
    assert sectxt(ssl.SSLError("x")) == ("UNKNOWN", {"reason": "tls_invalid"})
    assert sectxt(TransientError("x")) == ("UNKNOWN", {"reason": "unreachable"})
