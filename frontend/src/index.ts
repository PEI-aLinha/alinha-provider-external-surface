import { definePlugin } from '@alinha/plugin-sdk'
import { Globe } from 'lucide-react'
import { ExternalSurfacePage } from './pages/ExternalSurfacePage'

const PATH = 'integrations/external-surface'
const TITLE = 'Superfície externa'

/**
 * The plugin's frontend entry point. The shell reads this object and mounts the routes and the menu.
 *   area 'client'     → /org/:org/integrations/external-surface (the organization's responsible person activates here)
 *   area 'consultant' → /portfolio/:org/integrations/external-surface (the consultant follows the results)
 * Like the other providers, no `resource` on the entries: every role of the organization may read, and the
 * backend checks `external-surface:read` on each call.
 * An `integrations/<id>` path is listed on the shell's «Integrações» page, not in the side menu.
 */
export default definePlugin({
  id: 'external-surface',
  name: 'External Surface',
  routes: {
    client: [{ path: PATH, handle: { title: TITLE }, Component: ExternalSurfacePage }],
    consultant: [{ path: PATH, handle: { title: TITLE }, Component: ExternalSurfacePage }],
  },
  nav: [
    { label: TITLE, path: PATH, area: 'client', icon: Globe, order: 71 },
    { label: TITLE, path: PATH, area: 'consultant', icon: Globe, order: 71 },
  ],
})
