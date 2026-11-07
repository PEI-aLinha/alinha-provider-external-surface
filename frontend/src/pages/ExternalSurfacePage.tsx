import { useQuery } from '@tanstack/react-query'
import { useHost } from '@alinha/plugin-sdk'
import { PageHeader } from '@alinha/ui'

export function ExternalSurfacePage() {
  const { api } = useHost()
  const ping = useQuery({
    queryKey: ['external-surface', 'ping'],
    queryFn: () => api<{ plugin: string; status: string }>('/external-surface/ping'),
  })

  return (
    <>
      <PageHeader title="External Surface" />
      <p>Backend: {ping.isPending ? 'a verificar…' : ping.isError ? 'indisponível' : ping.data.status}</p>
    </>
  )
}
