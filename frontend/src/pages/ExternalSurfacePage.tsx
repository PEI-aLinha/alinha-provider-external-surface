import { Globe } from 'lucide-react'
import { Link, useLocation } from 'react-router'
import { useApiQuery, useHost } from '@alinha/plugin-sdk'
import { buttonClass, EmptyState, ErrorState, PageHeader, SkeletonRows } from '@alinha/ui'
import { ActivationCard } from '../surface/ActivationCard'
import { HostsEditor } from '../surface/HostsEditor'
import { ResultsList } from '../surface/ResultsList'
import { StatusCard } from '../surface/StatusCard'
import { QUERY_KEY, RESOURCE, type Evidence, type Monitoring } from '../surface/types'

/** While a check runs, ask again every few seconds. */
const POLL_MS = 4000

export function ExternalSurfacePage() {
  const monitoring = useApiQuery<Monitoring>([...QUERY_KEY, 'monitoring'], '/external-surface/monitoring', {
    refetchInterval: (query) => (query.state.data?.status === 'PENDING' ? POLL_MS : false),
  })

  return (
    <>
      <PageHeader title="Superfície externa"
        subtitle="O que qualquer pessoa consegue ver, a partir da internet, do site e do email da organização." />
      {monitoring.isPending ? (
        <SkeletonRows label="A carregar a verificação…" />
      ) : monitoring.isError ? (
        <ErrorState error={monitoring.error} onRetry={() => void monitoring.refetch()} />
      ) : (
        <Monitored monitoring={monitoring.data} />
      )}
    </>
  )
}

function Monitored({ monitoring }: { monitoring: Monitoring }) {
  const { can } = useHost()
  const domain = monitoring.domain
  if (!domain) return <NoDomain />
  if (!monitoring.active) {
    return (
      <div className="flex flex-col gap-6">
        <ActivationCard domain={domain} />
        <Results status={monitoring.status ?? null} />
      </div>
    )
  }
  const canManage = can(RESOURCE, 'manage')
  return (
    <div className="flex flex-col gap-6">
      <StatusCard monitoring={{ ...monitoring, domain }} canManage={canManage} />
      <Results status={monitoring.status ?? null} />
      <HostsEditor domain={domain} hosts={monitoring.hosts} canManage={canManage} />
    </div>
  )
}

/** The domain lives in the organization profile, which only the client area edits. */
function NoDomain() {
  const inClientArea = useLocation().pathname.startsWith('/org/')
  return (
    <EmptyState icon={<Globe className="size-5" aria-hidden />} title="Falta o domínio da organização">
      <span className="flex flex-col items-center gap-3">
        <span>
          Para verificarmos o site e o email, indique primeiro o domínio da organização (por exemplo, empresa.pt)
          {inClientArea ? ' na página Organização.' : '. Peça à organização para o indicar na página Organização.'}
        </span>
        {inClientArea && (
          <Link to="../../organization" relative="path" className={buttonClass({ variant: 'secondary' })}>
            Ir para a página Organização
          </Link>
        )}
      </span>
    </EmptyState>
  )
}

/** The last complete check. Shown even after the checks are turned off: what was found stays in the history. */
function Results({ status }: { status: Monitoring['status'] }) {
  const evidence = useApiQuery<Evidence[]>([...QUERY_KEY, 'evidence', status ?? 'none'], '/external-surface/evidence')
  if (!evidence.isPending && !evidence.isError && evidence.data.length === 0 && status !== 'PENDING' && status !== 'ACTIVE') {
    return null
  }
  return (
    <section aria-labelledby="results-heading" className="flex flex-col gap-3">
      <h2 id="results-heading" className="text-[1.125rem] font-bold text-ink">O que encontrámos</h2>
      {evidence.isPending ? (
        <SkeletonRows label="A carregar os resultados…" />
      ) : evidence.isError ? (
        <ErrorState error={evidence.error} onRetry={() => void evidence.refetch()} />
      ) : evidence.data.length === 0 ? (
        <p className="text-ink-soft">Os resultados aparecem aqui quando a primeira verificação terminar.</p>
      ) : (
        <ResultsList evidence={evidence.data} />
      )}
    </section>
  )
}
