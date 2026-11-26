import { describe, expect, it } from 'vitest'
import { detailText, errorText, formatDate, reasonText, tlsVersion } from './labels'
import type { Evidence } from './types'

const item = (check: Evidence['check'], outcome: Evidence['outcome'], details: Evidence['details'] = {}): Evidence => ({
  id: 'x', host: 'acme.pt', check, outcome, details, controlIds: [], informational: false,
  strength: 'TECHNICALLY_VERIFIED', collectedAt: '2026-11-24T10:00:00Z',
})

describe('labels', () => {
  it.each([
    ['tls1_3', 'TLS 1.3'], ['tls1_2', 'TLS 1.2'], ['tlsv1.1', 'TLS 1.1'], ['tlsv1', 'TLS 1.0'],
  ])('names TLS version %s as %s', (code, expected) => {
    expect(tlsVersion(code)).toBe(expected)
  })

  it.each([
    [item('tls_certificate', 'PASS', { daysLeft: 12, expiresSoon: true }), 'Expira dentro de 12 dias: renove-o já.'],
    [item('tls_certificate', 'PASS', { daysLeft: 80, expiresSoon: false }), 'Válido durante mais 80 dias.'],
    [item('tls_certificate', 'FAIL', { reason: 'expired' }), null],
    [item('tls_version', 'PASS', { version: 'tls1_3' }), 'Versão usada: TLS 1.3.'],
    [item('tls_legacy', 'FAIL', { accepted: 'tlsv1' }), 'Ainda aceita TLS 1.0.'],
    [item('https_redirect', 'PASS', { httpOpen: false }), 'O site não responde sem cifra.'],
    [item('https_redirect', 'FAIL', { status: 200 }), 'O site responde sem cifra e não reencaminha.'],
    [item('https_redirect', 'PASS', { httpOpen: true }), null],
    [item('hsts', 'FAIL', { maxAgeDays: 30 }), 'Dura só 30 dias; o mínimo são 180.'],
    [item('hsts', 'PASS', { maxAgeDays: 365 }), null],
    [item('spf', 'FAIL', { policy: 'neutral' }), 'Não diz aos outros servidores que recusem emails de servidores não autorizados.'],
    [item('spf', 'PASS', { policy: 'fail' }), null],
    [item('dmarc', 'FAIL', { policy: 'none', pct: 100 }), 'Só observa: não pede que os emails falsificados sejam recusados.'],
    [item('dmarc', 'FAIL', { policy: 'invalid', pct: 100 }), 'O registo tem uma política que os servidores de email não reconhecem.'],
    [item('dmarc', 'FAIL', { policy: 'reject', pct: 50 }), 'Aplica-se só a 50% dos emails.'],
    [item('dmarc', 'PASS', { policy: 'reject', pct: 100 }), null],
    [item('mta_sts', 'FAIL', { mode: 'testing' }), 'Está em modo de teste.'],
    [item('mta_sts', 'FAIL', { mode: 'none' }), 'Está desligado.'],
    [item('mta_sts', 'PASS', { mode: 'enforce' }), null],
    [item('referrer_policy', 'FAIL', { policy: 'unsafe_url' }), 'A política escolhida partilha os endereços.'],
    [item('csp', 'PASS'), null],
  ])('describes the details of %#', (evidence, expected) => {
    expect(detailText(evidence)).toBe(expected)
  })

  it('explains an UNKNOWN by its reason, and an unknown reason generically', () => {
    expect(reasonText(item('dkim', 'UNKNOWN', { reason: 'dns_unavailable' }))).toBe('Os servidores de nomes do domínio não responderam.')
    expect(reasonText(item('dkim', 'UNKNOWN', { reason: 'something_new' }))).toBe('Algo correu mal nesta verificação.')
  })

  it('explains a FAIL by its reason when known', () => {
    expect(reasonText(item('spf', 'FAIL', { reason: 'missing' }))).toBe('Não está configurado.')
    expect(reasonText(item('spf', 'FAIL', { reason: 'other' }))).toBeNull()
    expect(reasonText(item('spf', 'PASS'))).toBeNull()
  })

  it('turns error codes into sentences', () => {
    expect(errorText('domain_not_found')).toMatch(/não existe na internet/)
    expect(errorText('collector_error')).toMatch(/Algo correu mal/)
    expect(errorText(null)).toMatch(/Algo correu mal/)
  })

  it('formats dates, with a dash for none', () => {
    expect(formatDate(null)).toBe('—')
    expect(formatDate('2026-11-24T10:00:00Z')).toMatch(/2026/)
  })
})
