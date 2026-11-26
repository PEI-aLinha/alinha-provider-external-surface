import type { components } from '@alinha/api-client'

export type Monitoring = components['schemas']['ExternalSurfaceMonitoring']
export type Host = components['schemas']['ExternalSurfaceHost']
export type HostsInput = components['schemas']['ExternalSurfaceHostsInput']
export type ActivationInput = components['schemas']['ExternalSurfaceActivationInput']
export type Evidence = components['schemas']['ExternalSurfaceEvidence']
export type Check = Evidence['check']

export const QUERY_KEY = ['external-surface'] as const
export const RESOURCE = 'external-surface'
/** Same limit as the backend (`HostNames.MAX_EXTRA`). */
export const MAX_HOSTS = 10
