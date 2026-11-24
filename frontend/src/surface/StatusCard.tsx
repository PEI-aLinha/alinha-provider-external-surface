import { useState } from 'react'
import { errorMessage, useApiMutation } from '@alinha/plugin-sdk'
import { Badge, Button, Card, Eyebrow, Modal } from '@alinha/ui'
import { errorText, formatDate, STATUS_LABEL } from './labels'
import { QUERY_KEY, type Monitoring } from './types'

/** Same deadline as the backend: a check PENDING for longer is treated as lost and may be started again. */
const STALE_MS = 15 * 60 * 1000

export function isRunning(monitoring: Monitoring, now = Date.now()): boolean {
  if (monitoring.status !== 'PENDING') return false
  return !monitoring.collectingSince || now - new Date(monitoring.collectingSince).getTime() < STALE_MS
}

interface StatusCardProps {
  monitoring: Monitoring & { domain: string }
  canManage: boolean
}

export function StatusCard({ monitoring, canManage }: StatusCardProps) {
  const [confirming, setConfirming] = useState(false)
  const invalidates = [[...QUERY_KEY]]
  const collect = useApiMutation<void, Monitoring>({ path: '/external-surface/collections', invalidates })
  const deactivate = useApiMutation<void, void>({
    method: 'DELETE', path: '/external-surface/monitoring', invalidates, onSuccess: () => setConfirming(false),
  })
  const running = isRunning(monitoring)
  const actionError = collect.error ?? deactivate.error
  const status = monitoring.status

  return (
    <Card className="flex flex-col gap-4 p-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <Eyebrow as="h2">Domínio verificado</Eyebrow>
          <p className="mt-1 text-[1.125rem] font-bold text-ink">{monitoring.domain}</p>
        </div>
        {status && (
          <Badge tone={status === 'FAILED' ? 'dark' : status === 'ACTIVE' ? 'signal' : 'neutral'}>{STATUS_LABEL[status]}</Badge>
        )}
      </div>

      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-[0.875rem]">
        <dt className="text-muted">Última verificação</dt>
        <dd className="text-ink">{formatDate(monitoring.lastCollectedAt)}</dd>
        <dt className="text-muted">Verificação automática</dt>
        <dd className="text-ink">Todos os dias</dd>
        <dt className="text-muted">Ativada em</dt>
        <dd className="text-ink">{formatDate(monitoring.activatedAt)}</dd>
      </dl>

      {status === 'FAILED' && <p role="alert" className="text-[0.875rem] font-semibold text-danger">{errorText(monitoring.lastError)}</p>}
      {actionError && <p role="alert" className="text-[0.875rem] font-semibold text-danger">{errorMessage(actionError)}</p>}

      {canManage && (
        <div className="flex flex-wrap gap-3">
          <Button onClick={() => collect.mutate()} disabled={running || collect.isPending}>
            {running ? 'A verificar…' : 'Verificar agora'}
          </Button>
          <Button variant="ghost" onClick={() => setConfirming(true)}>Desativar</Button>
        </div>
      )}

      <Modal open={confirming} onOpenChange={setConfirming} title="Desativar a verificação?"
        description="Deixamos de verificar o domínio, incluindo a verificação diária. Os resultados já recolhidos ficam no histórico. Para voltar a ativar é preciso a autorização do responsável."
        footer={(
          <>
            <Button variant="ghost" onClick={() => setConfirming(false)}>Cancelar</Button>
            <Button onClick={() => deactivate.mutate()} disabled={deactivate.isPending}>Desativar</Button>
          </>
        )} />
    </Card>
  )
}
