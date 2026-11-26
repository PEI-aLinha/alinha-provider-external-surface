-- The checks of the organization's public domain, one row per tenant. The domain itself is read from the organization
-- profile when each check starts; here are only the extra hosts, whether the checks are on, and the last check.
create table externalsurface.surface (
    id                   uuid primary key,
    tenant_id            uuid not null,
    -- Subdomains of the profile's domain, checked besides the domain and www.
    extra_hosts          text[] not null default '{}' check (cardinality(extra_hosts) <= 10),
    active               boolean not null default false,
    -- When the Designated Officer authorized the checks (who did it is in the audit trail).
    activated_at         timestamptz,
    status               text check (status in ('PENDING', 'ACTIVE', 'FAILED')),
    last_job_id          uuid,
    collecting_since     timestamptz,
    -- The current check was started by the daily schedule, not by a person.
    collecting_scheduled boolean not null default false,
    -- What the current check was sent: its result must be about exactly this.
    collecting_domain    text,
    collecting_hosts     text[] not null default '{}',
    collected_job_id     uuid,
    last_error           text check (last_error ~ '^[a-z][a-z0-9_]{0,99}$'),
    last_collected_at    timestamptz,
    version              bigint not null default 0,
    constraint surface_one_per_tenant unique (tenant_id),
    constraint surface_active_has_date check (not active or activated_at is not null)
);

-- What one check found: one row per (host, check); the email and DNS checks carry the domain as host.
-- Insert-only: a new check adds new rows.
create table externalsurface.evidence (
    id              uuid primary key,
    tenant_id       uuid not null,
    surface_id      uuid references externalsurface.surface (id) on delete set null,
    job_id          uuid not null,
    domain          text not null,
    host            text not null,
    check_name      text not null check (check_name in
                        ('tls_version', 'tls_legacy', 'tls_certificate', 'https_redirect', 'hsts', 'csp',
                         'content_type_options', 'frame_protection', 'referrer_policy', 'security_txt',
                         'spf', 'dkim', 'dmarc', 'mta_sts', 'caa', 'dnssec')),
    outcome         text not null check (outcome in ('PASS', 'FAIL', 'UNKNOWN')),
    details         jsonb not null default '{}',
    -- Empty only for the informational checks (caa, dnssec): they are not evidence for any control.
    control_ids     text[] not null,
    strength        text not null,
    mapping_version integer not null,
    collected_at    timestamptz not null,
    constraint evidence_once_per_job unique (job_id, host, check_name)
);

create index evidence_tenant_collected on externalsurface.evidence (tenant_id, collected_at desc);
create index evidence_job on externalsurface.evidence (job_id);

-- When each active tenant's next daily check is due. Read by the scheduler across tenants, so it holds only the
-- tenant id and a time: no tenant data. Not tenant-filtered on purpose (SurfaceScheduleEntity).
create table externalsurface.collection_schedule (
    tenant_id   uuid primary key,
    next_run_at timestamptz not null,
    version     bigint not null default 0
);

create index collection_schedule_due on externalsurface.collection_schedule (next_run_at);
