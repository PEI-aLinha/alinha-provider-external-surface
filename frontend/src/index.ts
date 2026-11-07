import { definePlugin } from '@alinha/plugin-sdk'
import { ExternalSurfacePage } from './pages/ExternalSurfacePage'

/**
 * The plugin's frontend entry point. The shell reads this object and mounts
 * the routes and the menu — nothing to register by hand in the shell.
 *   area 'client'     → /org/:org/<path>
 *   area 'consultant' → /carteira/<path>
 * User-visible text (title, label) and paths are in Portuguese, plain language
 * (UI requirement in the technical report).
 */
export default definePlugin({
  id: 'external-surface',
  name: 'External Surface',
  routes: {
    client: [
      { path: 'integracoes/superficie-externa', handle: { title: 'Superfície externa' }, Component: ExternalSurfacePage },
    ],
  },
  nav: [
    { label: 'Superfície externa', path: 'integracoes/superficie-externa', area: 'client' },
  ],
})
