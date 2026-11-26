import type { Check, Evidence, Monitoring } from './types'

/** Plain-language names for each check (UI text in European Portuguese, ADR 0005). */
export const CHECK_LABEL: Record<Check, string> = {
  tls_version: 'Ligação cifrada atual',
  tls_legacy: 'Versões antigas de cifra desligadas',
  tls_certificate: 'Certificado do site válido',
  https_redirect: 'Visitantes enviados para a ligação segura',
  hsts: 'Navegador obrigado a usar a ligação segura',
  csp: 'Regras sobre o conteúdo que o site carrega',
  content_type_options: 'Ficheiros abertos só pelo tipo indicado',
  frame_protection: 'Site protegido contra ser embutido noutros',
  referrer_policy: 'Endereços visitados não são partilhados',
  security_txt: 'Contacto para comunicar falhas de segurança',
  spf: 'Servidores autorizados a enviar email (SPF)',
  dkim: 'Emails assinados digitalmente (DKIM)',
  dmarc: 'Política contra emails falsificados (DMARC)',
  mta_sts: 'Email recebido só por ligação cifrada (MTA-STS)',
  caa: 'Entidades autorizadas a emitir certificados',
  dnssec: 'Domínio assinado (DNSSEC)',
}

export const CHECK_WHY: Record<Check, string> = {
  tls_version: 'Quem visita o site comunica de forma cifrada, com versões que ainda são seguras.',
  tls_legacy: 'Versões antigas (TLS 1.0 e 1.1) têm falhas conhecidas e já não devem ser aceites.',
  tls_certificate: 'O certificado prova que o site é mesmo da organização. Fora de prazo, os navegadores avisam.',
  https_redirect: 'Quem escreve o endereço sem “https” chega à versão segura do site.',
  hsts: 'Depois da primeira visita, o navegador nunca volta a usar a ligação sem cifra.',
  csp: 'Limita o que o site pode carregar, o que trava muitos ataques ao navegador.',
  content_type_options: 'Impede que o navegador execute ficheiros como se fossem outra coisa.',
  frame_protection: 'Impede que outro site mostre este por baixo de botões falsos.',
  referrer_policy: 'Os endereços das páginas não vão parar a outros sites.',
  security_txt: 'Quem encontrar uma falha sabe a quem a comunicar (ficheiro security.txt).',
  spf: 'Diz que servidores podem enviar email em nome do domínio.',
  dkim: 'Os emails enviados levam uma assinatura que prova a sua origem.',
  dmarc: 'Diz aos outros servidores o que fazer com emails falsificados em nome do domínio.',
  mta_sts: 'Os emails enviados para a organização só viajam cifrados.',
  caa: 'Recomendação: limita quem pode emitir certificados para o domínio.',
  dnssec: 'Recomendação: protege as respostas do domínio contra falsificação.',
}

export const OUTCOME_LABEL: Record<Evidence['outcome'], string> = {
  PASS: 'Cumpre',
  FAIL: 'Não cumpre',
  UNKNOWN: 'Não foi possível verificar',
}

/** For the recommendations (CAA, DNSSEC), which are not evidence for any control. */
export const RECOMMENDATION_LABEL: Record<Evidence['outcome'], string> = {
  PASS: 'Em vigor',
  FAIL: 'Por fazer',
  UNKNOWN: 'Não foi possível verificar',
}

export const STRENGTH_LABEL: Partial<Record<Evidence['strength'], string>> = {
  CONTINUOUSLY_VERIFIED: 'Verificado todos os dias',
  TECHNICALLY_VERIFIED: 'Verificado a partir da internet',
}

export const STATUS_LABEL: Record<NonNullable<Monitoring['status']>, string> = {
  PENDING: 'A verificar…',
  ACTIVE: 'Ativa',
  FAILED: 'A última verificação falhou',
}

const ERROR_TEXT: Record<string, string> = {
  domain_not_found: 'O domínio não existe na internet. Confirme o domínio na página Organização.',
  domain_missing: 'A organização ainda não tem domínio. Indique-o na página Organização.',
  invalid_scope: 'O domínio ou os endereços indicados não são válidos. Reveja-os.',
  invalid_result: 'Recebemos uma resposta inesperada. Tente verificar de novo.',
  transient_error: 'O domínio não respondeu a tempo. Tente de novo dentro de alguns minutos.',
  publish_failed: 'Não foi possível iniciar a verificação. Tente de novo dentro de alguns minutos.',
}

export function errorText(code: string | null | undefined): string {
  return (code && ERROR_TEXT[code]) || 'Algo correu mal na verificação. Tente de novo; se continuar, contacte o suporte.'
}

const REASON_TEXT: Record<string, string> = {
  timeout: 'Não respondeu a tempo.',
  unreachable: 'Não conseguimos ligar a este endereço.',
  no_address: 'Este endereço não existe na internet.',
  not_public: 'Este endereço não é público, por isso não o verificamos.',
  tls_invalid: 'O certificado não é válido, por isso não lemos a página.',
  dns_unavailable: 'Os servidores de nomes do domínio não responderam.',
  not_zone_apex: 'O domínio indicado não é o domínio principal (por exemplo, empresa.pt).',
  no_mail: 'O domínio não recebe email, por isso não se aplica.',
  no_known_selector: 'Não encontrámos a assinatura nos nomes habituais. Se usa outro, pode estar em vigor na mesma.',
  probe_unavailable: 'Não foi possível testar as versões antigas a partir dos nossos servidores.',
  no_modern_tls: 'O site não aceita uma ligação cifrada atual.',
  no_expiry: 'O certificado não indica a data de validade.',
  check_error: 'Algo correu mal nesta verificação.',
}

/** Why something could not be checked, or why it failed, in a short sentence. Null when there is nothing to add. */
export function reasonText(evidence: Evidence): string | null {
  const reason = evidence.details.reason
  if (typeof reason !== 'string') return null
  if (evidence.outcome === 'UNKNOWN') return REASON_TEXT[reason] ?? REASON_TEXT.check_error
  return FAIL_REASON[reason] ?? null
}

const FAIL_REASON: Record<string, string> = {
  missing: 'Não está configurado.',
  multiple: 'Há mais do que um registo, o que os servidores de email tratam como erro.',
  expired: 'Está fora de prazo.',
  hostname_mismatch: 'O certificado é de outro endereço.',
  untrusted: 'O certificado não foi emitido por uma entidade reconhecida.',
  invalid: 'O certificado não é válido.',
  no_modern_tls: 'O site não aceita uma ligação cifrada atual.',
  report_only: 'As regras só registam problemas, não os bloqueiam.',
  no_contact: 'O ficheiro não indica um contacto.',
  no_expiry: 'O ficheiro não indica até quando é válido.',
  policy_unavailable: 'A política anunciada não está disponível.',
  unsigned: 'O domínio não está assinado.',
}

/** Details from the worker, as short sentences. Unknown keys are ignored. */
export function detailText(evidence: Evidence): string | null {
  const d = evidence.details as Record<string, boolean | number | string | undefined>
  switch (evidence.check) {
    case 'tls_certificate':
      if (typeof d.daysLeft !== 'number') return null
      return d.expiresSoon ? `Expira dentro de ${d.daysLeft} dias: renove-o já.` : `Válido durante mais ${d.daysLeft} dias.`
    case 'tls_version':
      return typeof d.version === 'string' ? `Versão usada: ${tlsVersion(d.version)}.` : null
    case 'tls_legacy':
      return typeof d.accepted === 'string' ? `Ainda aceita ${tlsVersion(d.accepted)}.` : null
    case 'https_redirect':
      return d.httpOpen === false ? 'O site não responde sem cifra.' : typeof d.status === 'number' ? 'O site responde sem cifra e não reencaminha.' : null
    case 'hsts':
      return typeof d.maxAgeDays === 'number' && evidence.outcome === 'FAIL' ? `Dura só ${d.maxAgeDays} dias; o mínimo são 180.` : null
    case 'spf':
      return d.policy === 'pass' || d.policy === 'neutral' || d.policy === 'none'
        ? 'Não diz aos outros servidores que recusem emails de servidores não autorizados.'
        : null
    case 'dmarc':
      if (d.policy === 'invalid') return 'O registo tem uma política que os servidores de email não reconhecem.'
      if (d.policy === 'none') return 'Só observa: não pede que os emails falsificados sejam recusados.'
      if (typeof d.pct === 'number' && d.pct < 100) return `Aplica-se só a ${d.pct}% dos emails.`
      return null
    case 'mta_sts':
      return d.mode === 'testing' ? 'Está em modo de teste.' : d.mode === 'none' ? 'Está desligado.' : null
    case 'referrer_policy':
      return evidence.outcome === 'FAIL' && typeof d.policy === 'string' ? 'A política escolhida partilha os endereços.' : null
    default:
      return null
  }
}

/** "tls1_3" (tls_version) or "tlsv1.1" (tls_legacy) -> "TLS 1.3" / "TLS 1.1"; "tlsv1" -> "TLS 1.0". */
export function tlsVersion(code: string): string {
  const number = code.toLowerCase().replace(/^tlsv?/, '').replace('_', '.')
  return `TLS ${number.includes('.') ? number : `${number}.0`}`
}

const DATE = new Intl.DateTimeFormat('pt-PT', { dateStyle: 'medium', timeStyle: 'short' })

export function formatDate(value: string | null | undefined): string {
  return value ? DATE.format(new Date(value)) : '—'
}
