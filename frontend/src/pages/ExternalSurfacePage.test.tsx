import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ApiError, type PluginHost } from '@alinha/plugin-sdk'
import { renderWithHost } from '../test/renderWithHost'
import type { Evidence, Monitoring } from '../surface/types'
import { isRunning } from '../surface/StatusCard'
import { hostProblem } from '../surface/HostsEditor'
import { groupResults } from '../surface/ResultsList'
import { ExternalSurfacePage } from './ExternalSurfacePage'

const ACTIVE: Monitoring = {
  domain: 'acme.pt', active: true, activatedAt: '2026-11-20T09:00:00Z', status: 'ACTIVE', lastError: null,
  lastCollectedAt: '2026-11-24T10:00:00Z', collectingSince: null,
  hosts: [{ hostname: 'loja.acme.pt', inScope: true }, { hostname: 'loja.antiga.pt', inScope: false }],
}
const INACTIVE: Monitoring = { domain: 'acme.pt', active: false, hosts: [] }

const item = (host: string, check: Evidence['check'], outcome: Evidence['outcome'], details: Evidence['details'] = {},
  informational = false): Evidence => ({
  id: `${host}-${check}`, host, check, outcome, details, controlIds: informational ? [] : ['PR.SD-2-B'], informational,
  strength: 'CONTINUOUSLY_VERIFIED', collectedAt: '2026-11-24T10:00:00Z',
})

const EVIDENCE: Evidence[] = [
  item('acme.pt', 'spf', 'PASS', { policy: 'fail' }),
  item('acme.pt', 'dmarc', 'FAIL', { policy: 'none', pct: 100, reports: true }),
  item('acme.pt', 'dnssec', 'FAIL', { reason: 'unsigned' }, true),
  item('acme.pt', 'tls_certificate', 'PASS', { daysLeft: 9, expiresSoon: true }),
  item('www.acme.pt', 'tls_version', 'PASS', { version: 'tls1_3' }),
  item('loja.acme.pt', 'hsts', 'UNKNOWN', { reason: 'timeout' }),
]

/** A fake /api: answers by method and path, and records what was sent. */
function apiWith(routes: Record<string, unknown>) {
  return vi.fn(async (path: string, init?: RequestInit) => {
    const key = `${init?.method ?? 'GET'} ${path}`
    if (!(key in routes)) throw new Error(`unexpected ${key}`)
    const answer = routes[key]
    if (answer instanceof Error) throw answer
    return answer
  }) as unknown as PluginHost['api'] & ReturnType<typeof vi.fn>
}

const render = (api: PluginHost['api'], can: PluginHost['can'] = () => true, url = '/org/acme/integrations/external-surface') =>
  renderWithHost(<ExternalSurfacePage />, { host: { api, can }, url })

const sent = (api: ReturnType<typeof vi.fn>, key: string) =>
  api.mock.calls.find(([path, init]) => `${(init as RequestInit | undefined)?.method ?? 'GET'} ${path}` === key)

describe('ExternalSurfacePage', () => {
  it('asks for the domain first, with a link to the organization page in the client area', async () => {
    render(apiWith({ 'GET /external-surface/monitoring': { domain: null, active: false, hosts: [] } }))

    expect(await screen.findByText('Falta o domínio da organização')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Ir para a página Organização' })).toHaveAttribute('href', '/org/acme/organization')
  })

  it('tells the consultant to ask the organization for the domain', async () => {
    render(apiWith({ 'GET /external-surface/monitoring': { domain: null, active: false, hosts: [] } }), () => true,
      '/portfolio/acme/integrations/external-surface')

    expect(await screen.findByText(/Peça à organização para o indicar/)).toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('activates only after the authorization is confirmed', async () => {
    const api = apiWith({
      'GET /external-surface/monitoring': INACTIVE,
      'GET /external-surface/evidence': [],
      'PUT /external-surface/monitoring': { ...ACTIVE, status: 'PENDING' },
    })
    render(api)

    const button = await screen.findByRole('button', { name: 'Ativar verificação' })
    expect(button).toBeDisabled()
    fireEvent.click(screen.getByLabelText('Confirmo que a organização é titular deste domínio e autoriza a verificação.'))
    fireEvent.click(button)

    await waitFor(() => expect(sent(api, 'PUT /external-surface/monitoring')).toBeDefined())
    expect(JSON.parse(sent(api, 'PUT /external-surface/monitoring')?.[1].body as string)).toEqual({ authorized: true })
  })

  it('shows why an activation was refused', async () => {
    const refused = new ApiError(422, '/external-surface/monitoring', {
      type: 'about:blank', title: 'x', status: 422, code: 'DOMAIN_MISSING', detail: 'Indique primeiro o domínio da organização.',
    } as never)
    render(apiWith({
      'GET /external-surface/monitoring': INACTIVE, 'GET /external-surface/evidence': [], 'PUT /external-surface/monitoring': refused,
    }))

    fireEvent.click(await screen.findByLabelText(/Confirmo que a organização/))
    fireEvent.click(screen.getByRole('button', { name: 'Ativar verificação' }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('tells people who cannot activate whom to ask', async () => {
    render(apiWith({ 'GET /external-surface/monitoring': INACTIVE, 'GET /external-surface/evidence': [] }),
      (_resource, action) => action !== 'activate')

    expect(await screen.findByText(/Peça ao responsável da organização para ativar/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ativar verificação' })).not.toBeInTheDocument()
  })

  it('shows the status, the extra hosts and what each check found', async () => {
    render(apiWith({ 'GET /external-surface/monitoring': ACTIVE, 'GET /external-surface/evidence': EVIDENCE }))

    expect(await screen.findByText('Email e domínio')).toBeInTheDocument()
    expect(screen.getByText('acme.pt')).toBeInTheDocument()
    expect(screen.getByText('Ativa')).toBeInTheDocument()
    expect(screen.getByText('Site acme.pt')).toBeInTheDocument()
    expect(screen.getByText('Site www.acme.pt')).toBeInTheDocument()
    expect(screen.getByText('Expira dentro de 9 dias: renove-o já.')).toBeInTheDocument()
    expect(screen.getByText('Só observa: não pede que os emails falsificados sejam recusados.')).toBeInTheDocument()
    expect(screen.getByText('Recomendação')).toBeInTheDocument()
    expect(screen.getByText('Por fazer')).toBeInTheDocument()
    expect(screen.getByText('Não respondeu a tempo.')).toBeInTheDocument()
    expect(screen.getByText('Fora do domínio: não é verificado')).toBeInTheDocument()
    expect(screen.getByText('2/10')).toBeInTheDocument()
  })

  it('starts a check on request', async () => {
    const api = apiWith({
      'GET /external-surface/monitoring': ACTIVE, 'GET /external-surface/evidence': [],
      'POST /external-surface/collections': { ...ACTIVE, status: 'PENDING' },
    })
    render(api)

    fireEvent.click(await screen.findByRole('button', { name: 'Verificar agora' }))

    await waitFor(() => expect(sent(api, 'POST /external-surface/collections')).toBeDefined())
  })

  it('deactivates after confirming', async () => {
    const api = apiWith({
      'GET /external-surface/monitoring': ACTIVE, 'GET /external-surface/evidence': [],
      'DELETE /external-surface/monitoring': undefined,
    })
    render(api)

    fireEvent.click(await screen.findByRole('button', { name: 'Desativar' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Desativar' }))

    await waitFor(() => expect(sent(api, 'DELETE /external-surface/monitoring')).toBeDefined())
  })

  it('adds and removes extra hosts', async () => {
    const api = apiWith({
      'GET /external-surface/monitoring': ACTIVE, 'GET /external-surface/evidence': [],
      'PUT /external-surface/hosts': ACTIVE,
    })
    render(api)

    const input = await screen.findByRole('textbox', { name: 'Endereço' })
    fireEvent.change(input, { target: { value: 'outra.pt' } })
    fireEvent.click(screen.getByRole('button', { name: 'Juntar' }))
    expect(await screen.findByText(/Só pode juntar endereços de acme.pt/)).toBeInTheDocument()

    fireEvent.change(input, { target: { value: 'https://Clientes.acme.pt/' } })
    fireEvent.click(screen.getByRole('button', { name: 'Juntar' }))
    await waitFor(() => expect(sent(api, 'PUT /external-surface/hosts')).toBeDefined())
    expect(JSON.parse(sent(api, 'PUT /external-surface/hosts')?.[1].body as string))
      .toEqual({ hosts: ['loja.acme.pt', 'loja.antiga.pt', 'clientes.acme.pt'] })

    api.mockClear()
    fireEvent.click(screen.getByRole('button', { name: 'Remover loja.antiga.pt' }))
    await waitFor(() => expect(sent(api, 'PUT /external-surface/hosts')).toBeDefined())
    expect(JSON.parse(sent(api, 'PUT /external-surface/hosts')?.[1].body as string)).toEqual({ hosts: ['loja.acme.pt'] })
  })

  it('hides the actions from people who can only read', async () => {
    render(apiWith({ 'GET /external-surface/monitoring': ACTIVE, 'GET /external-surface/evidence': [] }),
      (_resource, action) => (action ?? 'read') === 'read')

    expect(await screen.findByText('Domínio verificado')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Verificar agora' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Remover/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('form', { name: 'Juntar endereço' })).not.toBeInTheDocument()
  })

  it('explains a failed check', async () => {
    render(apiWith({
      'GET /external-surface/monitoring': { ...ACTIVE, status: 'FAILED', lastError: 'domain_not_found' },
      'GET /external-surface/evidence': [],
    }))

    expect(await screen.findByText(/O domínio não existe na internet/)).toBeInTheDocument()
  })

  it('shows an error state when the monitoring cannot be read', async () => {
    render(apiWith({ 'GET /external-surface/monitoring': new ApiError(500, '/external-surface/monitoring') }))

    expect(await screen.findByRole('button', { name: /Tentar/ })).toBeInTheDocument()
  })
})

describe('isRunning', () => {
  const now = Date.parse('2026-11-24T10:00:00Z')
  it('is true only for a recent PENDING check', () => {
    expect(isRunning({ ...ACTIVE, status: 'PENDING', collectingSince: '2026-11-24T09:55:00Z' }, now)).toBe(true)
    expect(isRunning({ ...ACTIVE, status: 'PENDING', collectingSince: '2026-11-24T09:00:00Z' }, now)).toBe(false)
    expect(isRunning({ ...ACTIVE, status: 'PENDING', collectingSince: null }, now)).toBe(true)
    expect(isRunning(ACTIVE, now)).toBe(false)
  })
})

describe('hostProblem', () => {
  const hosts = ACTIVE.hosts
  it.each([
    ['nada', /Indique só o endereço/],
    ['www.acme.pt', /já é verificado/],
    ['loja.acme.pt', /já está na lista/],
  ])('refuses %s', (raw, message) => {
    expect(hostProblem(raw, 'acme.pt', hosts)).toMatch(message)
  })

  it('stops at the limit', () => {
    const full = Array.from({ length: 10 }, (_, i) => ({ hostname: `s${i}.acme.pt`, inScope: true }))
    expect(hostProblem('novo.acme.pt', 'acme.pt', full)).toMatch(/até 10/)
    expect(hostProblem('novo.acme.pt', 'acme.pt', hosts)).toBeNull()
  })
})

describe('groupResults', () => {
  it('puts the domain checks first, then one group per site', () => {
    expect(groupResults(EVIDENCE).map((g) => g.title))
      .toEqual(['Email e domínio', 'Site acme.pt', 'Site www.acme.pt', 'Site loja.acme.pt'])
    expect(groupResults([])).toEqual([])
  })
})
