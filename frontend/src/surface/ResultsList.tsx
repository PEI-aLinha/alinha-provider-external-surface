import { CircleCheck, CircleHelp, CircleX, TriangleAlert } from 'lucide-react'
import { Badge, Card, Eyebrow } from '@alinha/ui'
import { CHECK_LABEL, CHECK_WHY, detailText, OUTCOME_LABEL, reasonText, RECOMMENDATION_LABEL, STRENGTH_LABEL } from './labels'
import type { Check, Evidence } from './types'

const ICON = {
  PASS: <CircleCheck className="size-3.5" aria-hidden />,
  FAIL: <CircleX className="size-3.5" aria-hidden />,
  UNKNOWN: <CircleHelp className="size-3.5" aria-hidden />,
} as const

const TONE = { PASS: 'signal', FAIL: 'dark', UNKNOWN: 'outline' } as const

/** Checked once for the whole domain (email and DNS); the rest is checked for each site. */
const DOMAIN_CHECKS: readonly Check[] = ['spf', 'dkim', 'dmarc', 'mta_sts', 'caa', 'dnssec']

interface Group {
  key: string
  title: string
  items: Evidence[]
}

/** One group for the email and the domain, then one per site in the order they came (domain, www, extras). */
export function groupResults(evidence: Evidence[]): Group[] {
  const domainItems = evidence.filter((item) => DOMAIN_CHECKS.includes(item.check))
  const sites = new Map<string, Evidence[]>()
  for (const item of evidence) {
    if (!DOMAIN_CHECKS.includes(item.check)) sites.set(item.host, [...(sites.get(item.host) ?? []), item])
  }
  const groups: Group[] = domainItems.length > 0 ? [{ key: 'domain', title: 'Email e domínio', items: domainItems }] : []
  return [...groups, ...[...sites].map(([host, items]) => ({ key: host, title: `Site ${host}`, items }))]
}

/** The badge text carries the meaning, not its colour. Recommendations never count as a gap. */
export function ResultsList({ evidence }: { evidence: Evidence[] }) {
  return (
    <div className="flex flex-col gap-4">
      {groupResults(evidence).map((group) => (
        <Card key={group.key} className="p-5">
          <Eyebrow as="h3">{group.title}</Eyebrow>
          <ul className="mt-3 divide-y divide-line-soft">
            {group.items.map((item) => <ResultRow key={item.id} item={item} />)}
          </ul>
        </Card>
      ))}
    </div>
  )
}

function ResultRow({ item }: { item: Evidence }) {
  const detail = detailText(item)
  const reason = reasonText(item)
  const expiresSoon = item.check === 'tls_certificate' && item.details.expiresSoon === true
  const labels = item.informational ? RECOMMENDATION_LABEL : OUTCOME_LABEL
  return (
    <li className="flex flex-wrap items-start justify-between gap-3 py-3">
      <div className="min-w-0 flex-1">
        <p className="flex flex-wrap items-center gap-2 font-semibold text-ink">
          {CHECK_LABEL[item.check]}
          {item.informational && <Badge tone="outline">Recomendação</Badge>}
        </p>
        <p className="text-[0.8125rem] text-muted">{CHECK_WHY[item.check]}</p>
        {reason && <p className="mt-1 text-[0.8125rem] text-ink-soft">{reason}</p>}
        {detail && (
          <p className={expiresSoon ? 'mt-1 flex items-center gap-1 text-[0.8125rem] font-semibold text-danger' : 'mt-1 text-[0.8125rem] text-ink-soft'}>
            {expiresSoon && <TriangleAlert className="size-3.5" aria-hidden />}
            {detail}
          </p>
        )}
        {!item.informational && item.outcome !== 'UNKNOWN' && STRENGTH_LABEL[item.strength] && (
          <p className="mt-1 text-[0.75rem] text-muted">{STRENGTH_LABEL[item.strength]}</p>
        )}
      </div>
      <Badge tone={TONE[item.outcome]} icon={ICON[item.outcome]}>{labels[item.outcome]}</Badge>
    </li>
  )
}
