/**
 * Provider IDs are internally `{type}/{host}` (e.g. `github/github.com`). The `/` breaks URL path
 * routing — Spring Security's StrictHttpFirewall rejects `%2F` with HTTP 400. Swap `/` for `@` on
 * the wire; the controller swaps it back before touching the store.
 */
function providerToPathKey(provider: string): string {
  return provider.replace(/\//g, '@')
}

/** Reads the XSRF-TOKEN cookie set by Spring Security's CookieCsrfTokenRepository. */
function getCsrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match ? decodeURIComponent(match[1]) : null
}

/** Fetch wrapper that redirects to /login.html on 401 and attaches the CSRF token on mutating requests. */
async function apiFetch(url: string, options?: RequestInit): Promise<Response> {
  const method = (options?.method ?? 'GET').toUpperCase()
  const mutating = ['POST', 'PUT', 'DELETE', 'PATCH'].includes(method)

  const headers: Record<string, string> = { ...(options?.headers as Record<string, string>) }
  if (mutating) {
    const token = getCsrfToken()
    if (token) headers['X-XSRF-TOKEN'] = token
  }

  const res = await fetch(url, { ...options, headers })
  if (res.status === 401) {
    window.location.href = '/login.html'
    return new Promise(() => {}) // suspend; page is navigating away
  }
  return res
}

export async function fetchMe() {
  const res = await apiFetch('/api/me')
  return res.json()
}

export async function fetchPushes(params: URLSearchParams) {
  const res = await apiFetch('/api/push?' + params)
  if (!res.ok) throw new Error('Failed to fetch pushes')
  return res.json()
}

export async function fetchPush(id: string) {
  const url = id.includes('_') ? `/api/push/by-ref/${id}` : `/api/push/${id}`
  const res = await apiFetch(url)
  if (!res.ok) throw new Error('Push not found')
  return res.json()
}

export async function fetchDiff(id: string): Promise<{ content: string | null }> {
  const res = await apiFetch(`/api/push/${encodeURIComponent(id)}/diff`)
  if (!res.ok) throw new Error('Diff not found')
  return res.json()
}

export async function fetchProviders() {
  const res = await apiFetch('/api/providers')
  if (!res.ok) throw new Error('Failed to fetch providers')
  return res.json()
}

export async function triggerConfigReload(section: string = 'all'): Promise<{ message: string }> {
  const res = await apiFetch(`/api/config/reload?section=${encodeURIComponent(section)}`, {
    method: 'POST',
  })
  if (!res.ok) await parseErrorResponse(res, 'Config reload failed')
  return res.json()
}

export async function fetchConfig(): Promise<{ authProvider: string; allowedOrigins: string[] }> {
  const res = await fetch('/api/runtime-config')
  if (!res.ok) throw new Error('Failed to fetch config')
  return res.json()
}

export async function fetchUsers() {
  const res = await apiFetch('/api/users')
  if (!res.ok) throw new Error('Failed to fetch users')
  return res.json()
}

export async function fetchUser(username: string) {
  const res = await apiFetch(`/api/users/${encodeURIComponent(username)}`)
  if (!res.ok) throw new Error('User not found')
  return res.json()
}

async function parseErrorResponse(res: Response, fallback: string): Promise<never> {
  const text = await res.text()
  let message = `${fallback} (HTTP ${res.status})`
  try {
    const err = JSON.parse(text)
    if (err.error) message = err.error
  } catch {
    // not JSON — keep the fallback
  }
  throw new Error(message)
}

export async function approvePush(
  id: string,
  body: {
    reviewerUsername: string
    reviewerEmail: string
    reason: string
    attestations?: Record<string, string>
  },
) {
  const res = await apiFetch(`/api/push/${id}/authorise`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) await parseErrorResponse(res, 'Failed to approve')
  return res.json()
}

export async function rejectPush(id: string, body: Record<string, string>) {
  const res = await apiFetch(`/api/push/${id}/reject`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!res.ok) await parseErrorResponse(res, 'Failed to reject')
  return res.json()
}

export async function cancelPush(id: string) {
  const res = await apiFetch(`/api/push/${id}/cancel`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({}),
  })
  if (!res.ok) await parseErrorResponse(res, 'Failed to cancel')
  return res.json()
}

export async function addEmail(email: string) {
  const res = await apiFetch('/api/me/emails', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
  })
  if (!res.ok) await parseErrorResponse(res, 'Failed to add email')
  return res.json()
}

export async function removeEmail(email: string) {
  const res = await apiFetch(`/api/me/emails/${encodeURIComponent(email)}`, { method: 'DELETE' })
  if (!res.ok) await parseErrorResponse(res, 'Failed to remove email')
}

export async function addScmIdentity(provider: string, username: string) {
  const res = await apiFetch('/api/me/identities', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider, username }),
  })
  if (!res.ok) await parseErrorResponse(res, 'Failed to add SCM identity')
  return res.json()
}

export async function removeScmIdentity(provider: string, scmUsername: string) {
  const res = await apiFetch(
    `/api/me/identities/${encodeURIComponent(providerToPathKey(provider))}/${encodeURIComponent(scmUsername)}`,
    { method: 'DELETE' },
  )
  if (!res.ok) await parseErrorResponse(res, 'Failed to remove SCM identity')
}

export async function createUser(
  username: string,
  password: string,
  email?: string,
  roles?: string[],
) {
  const res = await apiFetch('/api/users', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, email, roles }),
  })
  if (!res.ok) await parseErrorResponse(res, 'Failed to create user')
  return res.json()
}

export async function deleteUser(username: string) {
  const res = await apiFetch(`/api/users/${encodeURIComponent(username)}`, { method: 'DELETE' })
  if (!res.ok) await parseErrorResponse(res, 'Failed to delete user')
}

export async function resetUserPassword(username: string, password: string) {
  const res = await apiFetch(`/api/users/${encodeURIComponent(username)}/reset-password`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  })
  if (!res.ok) await parseErrorResponse(res, 'Failed to reset password')
  return res.json()
}

export async function addUserIdentity(username: string, provider: string, scmUsername: string) {
  const res = await apiFetch(`/api/users/${encodeURIComponent(username)}/identities`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ provider, scmUsername }),
  })
  if (!res.ok) await parseErrorResponse(res, 'Failed to add SCM identity')
  return res.json()
}

export async function addUserEmail(username: string, email: string) {
  const res = await apiFetch(`/api/users/${encodeURIComponent(username)}/emails`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
  })
  if (!res.ok) await parseErrorResponse(res, 'Failed to add email')
  return res.json()
}

export async function removeUserEmail(username: string, email: string) {
  const res = await apiFetch(
    `/api/users/${encodeURIComponent(username)}/emails/${encodeURIComponent(email)}`,
    { method: 'DELETE' },
  )
  if (!res.ok) await parseErrorResponse(res, 'Failed to remove email')
}

export async function removeUserIdentity(username: string, provider: string, scmUsername: string) {
  const res = await apiFetch(
    `/api/users/${encodeURIComponent(username)}/identities/${encodeURIComponent(providerToPathKey(provider))}/${encodeURIComponent(scmUsername)}`,
    { method: 'DELETE' },
  )
  if (!res.ok) await parseErrorResponse(res, 'Failed to remove SCM identity')
}

export async function fetchUserPermissions(username: string) {
  const res = await apiFetch(`/api/users/${encodeURIComponent(username)}/permissions`)
  if (!res.ok) throw new Error('Failed to fetch permissions')
  return res.json()
}

export async function addUserPermission(
  username: string,
  data: { provider: string; path: string; pathType: string; operations: string },
) {
  const res = await apiFetch(`/api/users/${encodeURIComponent(username)}/permissions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
  })
  if (!res.ok) await parseErrorResponse(res, 'Failed to add permission')
  return res.json()
}

export async function createAccessRule(rule: {
  access: 'ALLOW' | 'DENY'
  operations: 'BOTH' | 'PUSH' | 'FETCH'
  slug?: string
  owner?: string
  name?: string
  provider?: string
  description?: string
  ruleOrder?: number
}) {
  const res = await apiFetch('/api/repos/rules', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...rule, enabled: true, source: 'DB' }),
  })
  if (!res.ok) await parseErrorResponse(res, 'Failed to create access rule')
  return res.json()
}

export async function deleteAccessRule(id: string) {
  const res = await apiFetch(`/api/repos/rules/${encodeURIComponent(id)}`, { method: 'DELETE' })
  if (!res.ok) await parseErrorResponse(res, 'Failed to delete access rule')
}

export interface TcpResult {
  status: 'ok' | 'error'
  host: string
  port: number
  durationMs: number
  error?: string
  detail?: string
}

export interface TlsResult {
  status: 'ok' | 'error'
  durationMs: number
  // ok fields
  protocol?: string
  cipher?: string
  peerCn?: string
  // error fields
  error?: string
  detail?: string
}

export interface HttpResult {
  status: number | 'error'
  durationMs: number
  location?: string
  detail?: string
}

export interface GitProbeResult {
  status: 'ok' | 'error'
  /** Full URL that was probed, e.g. https://github.com/owner/repo/info/refs?service=git-upload-pack */
  probeUrl?: string
  /** HTTP status code returned by the upstream — present when status is 'ok' */
  httpStatus?: number
  /** Content-Type header from the upstream response */
  contentType?: string
  /** TIMEOUT | RESET | ERROR — present when status is 'error' */
  error?: string
  detail?: string
  durationMs: number
}

export interface GitProbe {
  uploadPack: GitProbeResult
  receivePack: GitProbeResult
}

export interface LogStep {
  timestamp: string
  step: string
  status: 'ok' | 'error' | 'skipped'
  durationMs?: number
  detail: string
}

export interface ProviderConnectivity {
  uri: string
  tcp: TcpResult
  tls: TlsResult | null
  http: HttpResult | null
  /** Only present on targeted checks (provider + repoPath supplied) */
  gitProbe?: GitProbe | null
  /** Structured step log — only present on targeted checks */
  steps?: LogStep[]
}

export async function checkConnectivity(): Promise<{
  checkedAt: string
  providers: Record<string, ProviderConnectivity>
}> {
  const res = await apiFetch('/api/admin/connectivity')
  if (!res.ok) await parseErrorResponse(res, 'Connectivity check failed')
  return res.json()
}

export async function checkTargetedConnectivity(
  provider: string,
  repoPath: string,
): Promise<{ checkedAt: string; providers: Record<string, ProviderConnectivity> }> {
  const params = new URLSearchParams({ provider, repoPath })
  const res = await apiFetch('/api/admin/connectivity?' + params)
  if (!res.ok) await parseErrorResponse(res, 'Connectivity check failed')
  return res.json()
}

export async function deleteUserPermission(username: string, id: string) {
  const res = await apiFetch(
    `/api/users/${encodeURIComponent(username)}/permissions/${encodeURIComponent(id)}`,
    { method: 'DELETE' },
  )
  if (!res.ok) await parseErrorResponse(res, 'Failed to remove permission')
}
