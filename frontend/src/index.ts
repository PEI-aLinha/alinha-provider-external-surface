import { definePlugin } from '@alinha/plugin-sdk'
import { ExternalSurfacePage } from './pages/ExternalSurfacePage'

/**
 * Ponto de entrada do plugin no frontend. O shell lê este objeto e monta
 * as rotas e o menu — não há nada a registar à mão no shell.
 *   area 'client'     → /org/:org/<path>
 *   area 'consultant' → /carteira/<path>
 */
export default definePlugin({
  id: 'external-surface',
  name: 'External Surface',
  routes: {
    client: [
      { path: 'external-surface', handle: { title: 'External Surface' }, Component: ExternalSurfacePage },
    ],
  },
  nav: [
    { label: 'External Surface', path: 'external-surface', area: 'client' },
  ],
})
