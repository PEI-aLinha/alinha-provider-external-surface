import { Globe } from 'lucide-react'
import { EmptyState, PageHeader } from '@alinha/ui'

export function ExternalSurfacePage() {
  return (
    <>
      <PageHeader title="Superfície externa" subtitle="O que está visível da sua organização na Internet." />
      <EmptyState icon={<Globe className="size-5" aria-hidden />} title="Em construção">
        Aqui vai poder indicar os sites e domínios da sua organização para verificarmos, de fora, se há falhas de segurança visíveis na Internet.
      </EmptyState>
    </>
  )
}
