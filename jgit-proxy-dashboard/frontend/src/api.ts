/** Fetch wrapper that redirects to /login.html on 401. */
async function apiFetch(url: string, options?: RequestInit): Promise<Response> {
  const res = await fetch(url, options);
  if (res.status === 401) {
    window.location.href = '/login.html';
    return new Promise(() => {}); // suspend; page is navigating away
  }
  return res;
}

export async function fetchMe() {
  const res = await apiFetch('/api/me');
  return res.json();
}

export async function fetchPushes(params: URLSearchParams) {
  const res = await apiFetch('/api/push?' + params);
  if (!res.ok) throw new Error('Failed to fetch pushes');
  return res.json();
}

export async function fetchPush(id: string) {
  const url = id.includes('_') ? `/api/push/by-ref/${id}` : `/api/push/${id}`;
  const res = await apiFetch(url);
  if (!res.ok) throw new Error('Push not found');
  return res.json();
}

export async function fetchProviders() {
  const res = await apiFetch('/api/providers');
  if (!res.ok) throw new Error('Failed to fetch providers');
  return res.json();
}

export async function approvePush(id: string, body: Record<string, string>) {
  const res = await apiFetch(`/api/push/${id}/authorise`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const err = await res.json();
    throw new Error(err.error || 'Failed to approve');
  }
  return res.json();
}

export async function rejectPush(id: string, body: Record<string, string>) {
  const res = await apiFetch(`/api/push/${id}/reject`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const err = await res.json();
    throw new Error(err.error || 'Failed to reject');
  }
  return res.json();
}
