import { useState } from 'react'
import { ShieldCheck } from 'lucide-react'
import { errorMessage, useApiMutation, useHost } from '@alinha/plugin-sdk'
import { Button, Card, Checkbox, EmptyState, Eyebrow } from '@alinha/ui'
import { QUERY_KEY, RESOURCE, type ActivationInput, type Monitoring } from './types'

interface ActivationCardProps {
  domain: string
}

/**
 * The checks only start after the responsible person says the organization owns the domain and authorizes them.
 * The backend refuses an activation without `authorized: true` and records who gave it in the audit trail.
 */
export function ActivationCard({ domain }: ActivationCardProps) {
  const { can } = useHost()
  const [authorized, setAuthorized] = useState(false)
  const activate = useApiMutation<ActivationInput, Monitoring>({
    method: 'PUT', path: '/external-surface/monitoring', invalidates: [[...QUERY_KEY]],
  })

  if (!can(RESOURCE, 'activate')) {
    return (
      <EmptyState icon={<ShieldCheck className="size-5" aria-hidden />} title="A verificação está desligada">
        Peça ao responsável da organização para ativar a verificação do domínio {domain}.
      </EmptyState>
    )
  }
  return (
    <Card className="flex max-w-[680px] flex-col gap-4 p-6">
      <Eyebrow as="h2">Ativar a verificação</Eyebrow>
      <div className="flex flex-col gap-2 text-[0.875rem] text-ink-soft">
        <p>
          Todos os dias verificamos, a partir da internet e sem nenhuma palavra-passe, o que qualquer pessoa consegue ver
          de <strong className="text-ink">{domain}</strong>: a ligação cifrada do site, o certificado, as proteções do
          navegador e as proteções do email contra falsificação.
        </p>
        <p>
          Não instalamos nada nos vossos servidores nem tentamos entrar neles: fazemos só os pedidos que um navegador ou
          um servidor de email faria.
        </p>
      </div>
      <form className="flex flex-col gap-4" aria-label="Ativar a verificação"
        onSubmit={(event) => { event.preventDefault(); if (authorized) activate.mutate({ authorized: true }) }}>
        <Checkbox label="Confirmo que a organização é titular deste domínio e autoriza a verificação." checked={authorized}
          onChange={(event) => setAuthorized(event.target.checked)} />
        {activate.error && <p role="alert" className="text-[0.875rem] font-semibold text-danger">{errorMessage(activate.error)}</p>}
        <div>
          <Button type="submit" disabled={!authorized || activate.isPending}>
            {activate.isPending ? 'A ativar…' : 'Ativar verificação'}
          </Button>
        </div>
      </form>
    </Card>
  )
}
