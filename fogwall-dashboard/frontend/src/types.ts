export type PushStatus =
  'PENDING' | 'APPROVED' | 'FORWARDED' | 'REJECTED' | 'CANCELED' | 'RECEIVED' | 'ERROR'

export type ScmApiActionStatus = 'FORWARDED' | 'DENIED' | 'REJECTED' | 'ERROR'

/** SCM API proxy (#264) audit record — one per proxied mutation, never per read. */
export interface ScmApiActionRecord {
  id: string
  timestamp?: string | number
  provider?: string
  /** SCM login the caller's token resolved to, before proxy-user resolution. */
  scmUsername?: string
  /** Fogwall proxy username the caller resolved to. */
  resolvedUser?: string
  repoOwner?: string
  repoName?: string
  mutationField?: string
  nodeId?: string
  nodeType?: string
  status: ScmApiActionStatus
  reason?: string
  variablesJson?: string
}

export interface Step {
  id: string
  stepName: string
  stepOrder: number
  status: 'PASS' | 'WARN' | 'FAIL' | 'BLOCKED' | 'SKIPPED' | string
  errorMessage?: string
  blockedMessage?: string
  content?: string
  timestamp?: string
}

export interface Commit {
  sha: string
  message: string
  authorName: string
  authorEmail: string
  committerName?: string
  committerEmail?: string
  signedOffBy?: string[]
  coAuthoredBy?: string[]
}

export interface AttestationLink {
  text: string
  url: string
}

export interface AttestationQuestion {
  id: string
  type: 'checkbox' | 'text' | 'dropdown'
  label: string
  required: boolean
  options?: string[]
  links?: AttestationLink[]
}

export interface Attestation {
  type?: 'APPROVAL' | 'REJECTION' | 'CANCELLATION'
  reviewerUsername: string
  reviewerEmail?: string
  reason?: string
  timestamp?: string
  selfApproval?: boolean
  answers?: Record<string, string>
}

export interface PushRecord {
  id: string
  status: PushStatus
  project?: string
  repoName?: string
  url?: string
  upstreamUrl?: string
  /** Browsable web URL for the repository, computed server-side from the provider. Absent for generic providers. */
  repoUrl?: string
  branch?: string
  commitTo?: string
  /** Browsable web URL for {@link commitTo}, computed server-side from the provider. Absent for generic providers. */
  commitUrl?: string
  commitFrom?: string
  message?: string
  author?: string
  user?: string
  resolvedUser?: string
  scmUsername?: string
  committer?: string
  timestamp?: string | number
  blockedMessage?: string
  errorMessage?: string
  autoApproved?: boolean
  autoRejected?: boolean
  attestation?: Attestation
  commits?: Commit[]
  steps?: Step[]
  /**
   * Server-computed flag (only set on GET /api/push/{id}): the current authenticated user is the resolved pusher,
   * holds ROLE_SELF_CERTIFY, AND has a SELF_CERTIFY repo permission for this push's path. Gates the self-certify
   * banner and approve button in the UI.
   */
  canCurrentUserSelfCertify?: boolean
}

/** One cached local mirror, from GET /api/admin/cache (#340). */
export interface CacheEntry {
  cacheKey: string
  remoteUrl: string
  cachedAtMillis: number
  lastFetchedAtMillis: number
  sizeBytes: number
  /** Ref count, or -1 when the mirror's refs could not be read. */
  refCount: number
  shallow: boolean
  unshallowed: boolean
}

/** One ref in a cached mirror, from GET /api/admin/cache/refs (#340). */
export interface CacheRef {
  name: string
  objectId: string
  type: 'branch' | 'tag' | 'other' | string
}

/** GET /api/admin/cache response: mirrors grouped by proxy mode. */
export interface CacheListResponse {
  server: CacheEntry[]
  proxy: CacheEntry[]
}

export interface Provider {
  name: string
  id: string
  uri: string
  host: string
  serverPath: string
  proxyPath: string
  /** True when the SSH listener is enabled and this provider serves SSH (#442). */
  sshEnabled: boolean
  /** TCP port the fogwall SSH listener binds. */
  sshPort: number
  /** SSH route path (leading slash), keyed on the provider's host, e.g. `/github.com`. */
  sshPath: string
  attestationQuestions: AttestationQuestion[]
  requireReviewPermission: boolean
}

/** One provider's generated git setup config, from the public `/api/setup` endpoint (#475). Push-only by default. */
export interface SetupProvider {
  name: string
  id: string
  type: string
  /** Upstream host this config routes through fogwall, e.g. `github.com`. */
  host: string
  /** Upstream base URL, trailing slash stripped, e.g. `https://github.com`. */
  upstreamUrl: string
  /** The `/server` clone/push URL prefix through fogwall, e.g. `https://fogwall/server/github.com/`. */
  serverUrl: string
  /** Global push-only `~/.gitconfig` block (HTTPS): reroutes pushes to /server; clones/fetches stay direct. */
  httpPush: string
  /** Opt-in global `~/.gitconfig` block (HTTPS) to also route fetches through fogwall's /proxy. */
  httpRead: string
  /** Per-repository HTTPS commands: clone from upstream, then `git remote set-url --push` to fogwall. */
  httpPerRepo: string
  /** True when this provider serves SSH and SSH config blocks are available. */
  sshEnabled: boolean
  /** Global push-only SSH `~/.gitconfig` block, or null when the provider does not serve SSH. */
  sshPush: string | null
  /** Per-repository SSH commands, or null when the provider does not serve SSH. */
  sshPerRepo: string | null
}

/** Response of the public `/api/setup` endpoint (#475) — the in-app developer setup guide. */
export interface SetupInfo {
  /** Base URL the generated config routes to (service-url if set, else derived from the request). */
  serviceUrl: string
  /** False when `server.service-url` is unset and the base URL was derived from the request (may be wrong behind a proxy). */
  serviceUrlConfigured: boolean
  providers: SetupProvider[]
}

export interface EmailEntry {
  email: string
  verified: boolean
  locked: boolean
  source: string
}

export interface ScmIdentity {
  provider: string
  username: string
  verified: boolean
  source?: string
}

/**
 * A provider configured for SCM OAuth account linking, as served by /api/runtime-config. `type` picks the
 * logo/brand to render (github, gitlab, forgejo); `hostname` is the actual host this instance talks to, since a
 * github/gitlab-type provider isn't always github.com/gitlab.com (GHE data residency, self-managed GHES, self-hosted
 * GitLab), and a forgejo-type provider covers everything from a generic self-hosted instance to codeberg.org.
 */
export interface ScmOAuthProviderInfo {
  id: string
  type: string
  hostname: string
}

export interface CurrentUser {
  username: string
  emails: EmailEntry[]
  scmIdentities: ScmIdentity[]
  authorities: string[]
}

export interface UserSummary {
  username: string
  primaryEmail: string | null
  scmProviders: string[]
  pushCounts: Partial<Record<PushStatus, number>>
}

export interface UserDetail {
  username: string
  emails: EmailEntry[]
  scmIdentities: ScmIdentity[]
  pushCounts: Partial<Record<PushStatus, number>>
}

export interface SshKeyEntry {
  id: string
  fingerprint: string
  publicKey: string
  label: string
  createdAt: string
  locked: boolean
  source: string
}

export interface RepoPermission {
  id: string
  username: string
  provider: string
  value: string
  matchType: 'LITERAL' | 'GLOB' | 'REGEX'
  grant: 'PUSH' | 'REVIEW' | 'PUSH_AND_REVIEW' | 'SELF_CERTIFY'
  source: 'CONFIG' | 'DB'
}

export interface GroupPermissionRule {
  id: string
  groupId: string
  provider: string
  target: string
  value: string
  matchType: 'LITERAL' | 'GLOB' | 'REGEX'
  grant: 'PUSH' | 'REVIEW' | 'PUSH_AND_REVIEW' | 'SELF_CERTIFY'
}

export interface GroupSummary {
  id: string
  name: string
  description: string | null
  source: 'CONFIG' | 'DB'
  memberCount: number
  ruleCount: number
}

export interface GroupDetail {
  id: string
  name: string
  description: string | null
  source: 'CONFIG' | 'DB'
  members: string[]
  rules: GroupPermissionRule[]
}

export interface ThirdPartyNoticeModule {
  ecosystem: 'maven' | 'npm'
  name: string
  version: string
  url?: string
  declaredLicense?: string
  declaredLicenseUrl?: string
  licenseText?: string
  noticeText?: string
  licenseTextSource: 'embedded' | 'declared-only'
}

export interface ThirdPartyNotices {
  generatedAt: string | null
  variant: string | null
  modules: ThirdPartyNoticeModule[]
}
