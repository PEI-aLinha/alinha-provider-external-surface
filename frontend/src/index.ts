import { definePlugin } from '@alinha/plugin-sdk'
import { Globe } from 'lucide-react'
import { ExternalSurfacePage } from './pages/ExternalSurfacePage'

const PATH = 'integrations/external-surface'
const TITLE = 'Superfície externa'

/**
 * The plugin's frontend entry point. The shell reads this object and mounts the routes and the menu.
 *   area 'client'     → /org/:org/integrations/external-surface (the organization's responsible person activates here)
 *   area 'consultant' → /portfolio/:org/integrations/external-surface (the consultant follows the results)
 * An `integrations/<id>` path is listed on the shell's «Integrações» page, not in the side menu.
 */
export default definePlugin({
  id: 'external-surface',
  name: 'External Surface',
  routes: {
    client: [{ path: PATH, handle: { title: TITLE, resource: 'external-surface' }, Component: ExternalSurfacePage }],
    consultant: [{ path: PATH, handle: { title: TITLE, resource: 'external-surface' }, Component: ExternalSurfacePage }],
  },
  nav: [
    { label: TITLE, path: PATH, area: 'client', icon: Globe, resource: 'external-surface', order: 71 },
    { label: TITLE, path: PATH, area: 'consultant', icon: Globe, resource: 'external-surface', order: 71 },
  ],
})
