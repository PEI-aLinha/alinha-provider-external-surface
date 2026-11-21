import { definePlugin } from '@alinha/plugin-sdk'
import { Globe } from 'lucide-react'
import { ExternalSurfacePage } from './pages/ExternalSurfacePage'

/**
 * The plugin's frontend entry point. The shell reads this object and mounts
 * the routes and the menu — nothing to register by hand in the shell.
 *   area 'client'     → /org/:org/<path>
 *   area 'consultant' → /portfolio/:org/<path>
 * User-visible text (title, label) is in European Portuguese, plain language
 * (UI requirement in the technical report). Paths are in English.
 */
export default definePlugin({
  id: 'external-surface',
  name: 'External Surface',
  routes: {
    client: [
      { path: 'integrations/external-surface', handle: { title: 'Superfície externa' }, Component: ExternalSurfacePage },
    ],
  },
  nav: [
    { label: 'Superfície externa', path: 'integrations/external-surface', area: 'client', icon: Globe, order: 71 },
  ],
})
