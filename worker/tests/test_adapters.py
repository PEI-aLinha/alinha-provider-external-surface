import dns.exception
import dns.message
import dns.name
import dns.rdataclass
import dns.rdatatype
import dns.resolver
import dns.rrset
import pytest

from alinha_job_consumer import net
from alinha_externalsurface.dnsclient import DnsPythonResolver, DnsUnavailable, Lookup
from alinha_externalsurface.probe import NetProbe


class Answer:
    def __init__(self, rrset):
        self.rrset = rrset


def resolver_answering(monkeypatch, outcome):
    resolver = DnsPythonResolver(lifetime=1)

    def resolve(name, rtype, raise_on_no_answer=True):
        if isinstance(outcome, Exception):
            raise outcome
        return outcome

    monkeypatch.setattr(resolver._resolver, "resolve", resolve)
    return resolver


def test_txt_strings_are_joined(monkeypatch):
    rrset = dns.rrset.from_text("acme.pt.", 300, "IN", "TXT", '"v=spf1 " "-all"', '"other"')
    assert resolver_answering(monkeypatch, Answer(rrset)).lookup("acme.pt", "TXT") == Lookup(("v=spf1 -all", "other"))


def test_other_records_are_text(monkeypatch):
    rrset = dns.rrset.from_text("acme.pt.", 300, "IN", "MX", "10 mail.acme.pt.")
    assert resolver_answering(monkeypatch, Answer(rrset)).lookup("acme.pt", "MX") == Lookup(("10 mail.acme.pt.",))


def test_no_answer_and_no_name(monkeypatch):
    assert resolver_answering(monkeypatch, Answer(None)).lookup("acme.pt", "CAA") == Lookup(())
    assert resolver_answering(monkeypatch, dns.resolver.NXDOMAIN()).lookup("x.pt", "TXT") == Lookup((), False)


@pytest.mark.parametrize("error", [dns.resolver.NoNameservers(), dns.resolver.LifetimeTimeout(timeout=1, errors={}),
                                   dns.exception.Timeout()])
def test_no_answer_at_all_is_unavailable(monkeypatch, error):
    with pytest.raises(DnsUnavailable):
        resolver_answering(monkeypatch, error).lookup("acme.pt", "TXT")


def test_net_probe_goes_through_the_shared_network_module(monkeypatch):
    calls = []
    monkeypatch.setattr(net, "public_addresses",
                        lambda host, port: calls.append(("address", host, port)) or ("8.8.8.8", "2606:4700::1"))
    monkeypatch.setattr(net, "tls_handshake", lambda *a, **k: calls.append(("tls", a[0], k["timeout"])) or "tls")
    monkeypatch.setattr(net, "pinned_https_get",
                        lambda host, address, path, headers, **k: calls.append(("get", path, headers["User-Agent"],
                                                                                k["max_bytes"])) or "page")
    monkeypatch.setattr(net, "pinned_http_location", lambda host, address, **k: calls.append(("http", host)) or (301, None))
    probe = NetProbe(timeout=3)

    assert probe.address("acme.pt") == ("8.8.8.8", "2606:4700::1")  # every address, so a connection can fall back
    assert probe.handshake("acme.pt", "8.8.8.8", None) == "tls"
    assert probe.get("acme.pt", "8.8.8.8", "/", 0) == "page"
    assert probe.location("acme.pt", "8.8.8.8") == (301, None)
    assert calls == [("address", "acme.pt", 443), ("tls", "acme.pt", 3),
                     ("get", "/", "aLinha-external-surface/1.0", 0), ("http", "acme.pt")]
