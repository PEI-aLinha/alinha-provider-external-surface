const LABEL = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/
const TOP_LEVEL = /^(?:[a-z]{2,63}|xn--[a-z0-9-]{1,59})$/
const MAX_LENGTH = 253

/**
 * A host name from what was typed (`https://Loja.Empresa.pt/` → `loja.empresa.pt`), internationalized names in
 * ASCII form, or null when it is not one. Same rules as the backend (`HostNames.normalize`).
 */
export function normalizeHost(raw: string): string | null {
  const text = raw.trim().toLowerCase()
  if (!text || /[\s@[\]]/.test(text)) return null
  let host: string
  try {
    host = new URL(/^[a-z][a-z0-9+.-]*:\/\//.test(text) ? text : `http://${text}`).hostname
  } catch {
    return null
  }
  host = host.replace(/\.$/, '')
  const labels = host.split('.')
  const valid = host.length <= MAX_LENGTH && labels.length >= 2 && labels.every((l) => LABEL.test(l)) &&
    TOP_LEVEL.test(labels[labels.length - 1])
  return valid ? host : null
}

/** A subdomain of [domain], not the domain itself. */
export const isSubdomainOf = (host: string, domain: string): boolean => host.endsWith(`.${domain}`)
