import { describe, expect, it } from 'vitest'
import { isSubdomainOf, normalizeHost } from './hostnames'

describe('normalizeHost', () => {
  it.each([
    ['https://Loja.Empresa.pt/', 'loja.empresa.pt'],
    ['  loja.empresa.pt.  ', 'loja.empresa.pt'],
    ['loja.empresa.pt:8443/x?y', 'loja.empresa.pt'],
    ['açores.empresa.pt', 'xn--aores-yra.empresa.pt'],
  ])('reads %s as %s', (raw, expected) => {
    expect(normalizeHost(raw)).toBe(expected)
  })

  it.each(['', 'localhost', 'a b.pt', 'user@empresa.pt', '10.0.0.1', '[::1]', 'empresa.p', '-x.empresa.pt', 'http://'])(
    'refuses %s', (raw) => {
      expect(normalizeHost(raw)).toBeNull()
    })
})

describe('isSubdomainOf', () => {
  it('is true only below the domain', () => {
    expect(isSubdomainOf('loja.empresa.pt', 'empresa.pt')).toBe(true)
    expect(isSubdomainOf('empresa.pt', 'empresa.pt')).toBe(false)
    expect(isSubdomainOf('outraempresa.pt', 'empresa.pt')).toBe(false)
  })
})
