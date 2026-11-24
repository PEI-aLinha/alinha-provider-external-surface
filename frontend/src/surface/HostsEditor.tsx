import { useState, type FormEvent } from 'react'
import { X } from 'lucide-react'
import { errorMessage, useApiMutation } from '@alinha/plugin-sdk'
import { Badge, Button, Card, Eyebrow, Field, TextInput } from '@alinha/ui'
import { isSubdomainOf, normalizeHost } from './hostnames'
import { MAX_HOSTS, QUERY_KEY, type Host, type HostsInput, type Monitoring } from './types'

interface HostsEditorProps {
  domain: string
  hosts: Host[]
  canManage: boolean
}

/** Why [raw] cannot be added, or null. The backend checks the same again. */
export function hostProblem(raw: string, domain: string, hosts: Host[]): string | null {
  const host = normalizeHost(raw)
  if (!host) return `Indique só o endereço, por exemplo loja.${domain}.`
  if (!isSubdomainOf(host, domain)) return `Só pode juntar endereços de ${domain}, por exemplo loja.${domain}.`
  if (host === `www.${domain}`) return `O endereço www.${domain} já é verificado.`
  if (hosts.some((h) => h.hostname === host)) return 'Este endereço já está na lista.'
  if (hosts.length >= MAX_HOSTS) return `Pode indicar até ${MAX_HOSTS} endereços.`
  return null
}

/**
 * Other sites of the organization under its domain (a shop, a client area). The domain itself and www are always
 * checked. A host that is no longer under the profile's domain is kept but not checked, and says so.
 */
export function HostsEditor({ domain, hosts, canManage }: HostsEditorProps) {
  const [draft, setDraft] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const save = useApiMutation<HostsInput, Monitoring>({
    method: 'PUT', path: '/external-surface/hosts', invalidates: [[...QUERY_KEY]],
  })
  const names = hosts.map((h) => h.hostname)

  const add = (event: FormEvent) => {
    event.preventDefault()
    const found = hostProblem(draft, domain, hosts)
    setProblem(found)
    if (found) return
    save.mutate({ hosts: [...names, normalizeHost(draft) as string] }, { onSuccess: () => setDraft('') })
  }
  const remove = (hostname: string) => save.mutate({ hosts: names.filter((name) => name !== hostname) })

  return (
    <Card className="flex flex-col gap-4 p-6">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <Eyebrow as="h2">Outros endereços a verificar</Eyebrow>
        <span className="text-[0.8125rem] text-muted">{hosts.length}/{MAX_HOSTS}</span>
      </div>
      <p className="text-[0.875rem] text-ink-soft">
        Verificamos sempre {domain} e www.{domain}. Se a organização tem outros sites no mesmo domínio, como uma loja ou
        uma área de clientes, junte-os aqui.
      </p>
      {hosts.length > 0 && (
        <ul className="divide-y divide-line-soft" aria-label="Endereços a verificar">
          {hosts.map((host) => (
            <li key={host.hostname} className="flex items-center justify-between gap-3 py-2">
              <span className="flex flex-wrap items-center gap-2 text-ink">
                {host.hostname}
                {!host.inScope && <Badge tone="outline">Fora do domínio: não é verificado</Badge>}
              </span>
              {canManage && (
                <Button variant="ghost" size="sm" aria-label={`Remover ${host.hostname}`} onClick={() => remove(host.hostname)}
                  disabled={save.isPending}>
                  <X className="size-4" aria-hidden />
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {canManage && hosts.length < MAX_HOSTS && (
        <form className="flex flex-wrap items-end gap-3" aria-label="Juntar endereço" onSubmit={add} noValidate>
          <Field label="Endereço" hint={`Exemplo: loja.${domain}`} error={problem ?? undefined} className="min-w-[240px] flex-1">
            {({ id, describedBy, invalid }) => (
              <TextInput id={id} aria-describedby={describedBy} invalid={invalid} value={draft} autoComplete="off"
                onChange={(event) => { setDraft(event.target.value); setProblem(null) }} />
            )}
          </Field>
          <Button type="submit" variant="secondary" disabled={!draft.trim() || save.isPending}>Juntar</Button>
        </form>
      )}
      {save.error && <p role="alert" className="text-[0.875rem] font-semibold text-danger">{errorMessage(save.error)}</p>}
    </Card>
  )
}
