import type { ReactNode } from 'react'
import { render } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { vi } from 'vitest'
import { PluginHostContext, type PluginHost } from '@alinha/plugin-sdk'

/** Renders `ui` at `url` with a fake shell host (session, `api`, `reauthenticate`), like the shell's onboarding area. */
export function renderWithHost(ui: ReactNode, { url = '/', host = {} }: { url?: string; host?: Partial<PluginHost> } = {}) {
  const fullHost: PluginHost = {
    tenantId: null,
    organization: null,
    user: { id: 'u1', name: 'Rui Santos', roles: ['registrant'] },
    can: () => true,
    api: vi.fn(async () => { throw new Error('unexpected API call') }) as PluginHost['api'],
    reauthenticate: vi.fn(),
    ...host,
  }
  const router = createMemoryRouter([{ path: '*', element: ui }], { initialEntries: [url] })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  const view = render(
    <QueryClientProvider client={queryClient}>
      <PluginHostContext.Provider value={fullHost}>
        <RouterProvider router={router} />
      </PluginHostContext.Provider>
    </QueryClientProvider>,
  )
  return { ...view, router, host: fullHost }
}
