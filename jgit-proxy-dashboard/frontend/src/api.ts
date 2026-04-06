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

export async function fetchProviders() {
  const res = await apiFetch('/api/providers')
  if (!res.ok) throw new Error('Failed to fetch providers')
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
  try {
    const err = JSON.parse(text)
    throw new Error(err.error || fallback)
  } catch {
    throw new Error(`${fallback} (HTTP ${res.status})`)
  }
}

export async function approvePush(id: string, body: Record<string, string>) {
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
    `/api/me/identities/${encodeURIComponent(provider)}/${encodeURIComponent(scmUsername)}`,
    { method: 'DELETE' },
  )
  if (!res.ok) await parseErrorResponse(res, 'Failed to remove SCM identity')
}
