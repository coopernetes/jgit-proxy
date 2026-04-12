import { useEffect, useState } from 'react'
import {
  addEmail,
  addScmIdentity,
  fetchMe,
  fetchProviders,
  removeEmail,
  removeScmIdentity,
} from '../api'
import { OperationsBadge, PathTypeBadge } from '../components/PermissionBadges'
import type { CurrentUser, EmailEntry, RepoPermission, ScmIdentity } from '../types'

function LockedBadge({ source }: { source: string }) {
  const title =
    source === 'config'
      ? 'Defined in server configuration and cannot be removed'
      : `Managed by your ${source.toUpperCase()} identity provider and cannot be removed`
  return (
    <span
      className="inline-flex items-center gap-1 rounded-full bg-blue-50 px-2 py-0.5 text-xs font-medium text-blue-600"
      title={title}
    >
      locked ({source})
    </span>
  )
}

export function Profile() {
  const [profile, setProfile] = useState<CurrentUser | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [tab, setTab] = useState<'emails' | 'identities' | 'permissions'>('emails')
  const [permissions, setPermissions] = useState<RepoPermission[]>([])

  const [newEmail, setNewEmail] = useState('')
  const [emailError, setEmailError] = useState<string | null>(null)
  const [emailBusy, setEmailBusy] = useState(false)

  const [providers, setProviders] = useState<{ name: string; id: string; host: string }[]>([])
  const [newProvider, setNewProvider] = useState('')
  const [newScmUsername, setNewScmUsername] = useState('')
  const [identityError, setIdentityError] = useState<string | null>(null)
  const [identityBusy, setIdentityBusy] = useState(false)

  useEffect(() => {
    fetchMe()
      .then((data) => {
        setProfile(data)
        setPermissions(data.permissions ?? [])
      })
      .catch(() => setError('Failed to load profile'))
      .finally(() => setLoading(false))
    fetchProviders()
      .then((list: { name: string; id: string; host: string }[]) => {
        setProviders(list)
        if (list.length > 0) setNewProvider(list[0].id)
      })
      .catch(() => {})
  }, [])

  async function handleAddEmail(e: React.FormEvent) {
    e.preventDefault()
    if (!newEmail.trim()) return
    setEmailBusy(true)
    setEmailError(null)
    try {
      await addEmail(newEmail.trim())
      const updated = await fetchMe()
      setProfile(updated)
      setNewEmail('')
    } catch (err: unknown) {
      setEmailError(err instanceof Error ? err.message : 'Failed to add email')
    } finally {
      setEmailBusy(false)
    }
  }

  async function handleRemoveEmail(entry: EmailEntry) {
    setEmailError(null)
    try {
      await removeEmail(entry.email)
      setProfile((p) => p && { ...p, emails: p.emails.filter((e) => e.email !== entry.email) })
    } catch (err: unknown) {
      setEmailError(err instanceof Error ? err.message : 'Failed to remove email')
    }
  }

  async function handleAddIdentity(e: React.FormEvent) {
    e.preventDefault()
    if (!newScmUsername.trim()) return
    setIdentityBusy(true)
    setIdentityError(null)
    try {
      await addScmIdentity(newProvider, newScmUsername.trim())
      const updated = await fetchMe()
      setProfile(updated)
      setNewScmUsername('')
    } catch (err: unknown) {
      setIdentityError(err instanceof Error ? err.message : 'Failed to add identity')
    } finally {
      setIdentityBusy(false)
    }
  }

  async function handleRemoveIdentity(identity: ScmIdentity) {
    setIdentityError(null)
    try {
      await removeScmIdentity(identity.provider, identity.username)
      setProfile(
        (p) =>
          p && {
            ...p,
            scmIdentities: p.scmIdentities.filter(
              (id) => !(id.provider === identity.provider && id.username === identity.username),
            ),
          },
      )
    } catch (err: unknown) {
      setIdentityError(err instanceof Error ? err.message : 'Failed to remove identity')
    }
  }

  if (loading)
    return <div className="max-w-2xl mx-auto px-4 py-16 text-center text-gray-400">Loading…</div>
  if (error)
    return <div className="max-w-2xl mx-auto px-4 py-16 text-center text-red-500">{error}</div>
  if (!profile) return null

  return (
    <div className="max-w-2xl mx-auto px-4 py-6 space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-gray-800">Profile</h2>
        <p className="text-sm text-gray-500 mt-1">
          Signed in as <span className="font-medium text-gray-700">{profile.username}</span>
        </p>
        <div className="flex flex-wrap gap-1 mt-2">
          {profile.authorities
            .filter((a: string) => a.startsWith('ROLE_'))
            .map((a: string) => {
              const label = a.replace('ROLE_', '')
              const colour =
                label === 'ADMIN'
                  ? 'bg-purple-100 text-purple-700'
                  : label === 'SELF_CERTIFY'
                    ? 'bg-amber-100 text-amber-700'
                    : 'bg-gray-100 text-gray-600'
              return (
                <span
                  key={a}
                  className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${colour}`}
                >
                  {label}
                </span>
              )
            })}
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-gray-200">
        {(['emails', 'identities', 'permissions'] as const).map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={
              'px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px ' +
              (tab === t
                ? 'border-slate-700 text-slate-800'
                : 'border-transparent text-gray-500 hover:text-gray-700')
            }
          >
            {t === 'emails'
              ? 'Email Addresses'
              : t === 'identities'
                ? 'SCM Identities'
                : 'Permissions'}
          </button>
        ))}
      </div>

      {/* Emails tab */}
      {tab === 'emails' && (
        <div className="space-y-4">
          <p className="text-sm text-gray-500">
            Email addresses linked to your commits. The identity verification hook uses these to
            confirm your authorship on push.
          </p>

          {profile.emails.length === 0 ? (
            <p className="text-sm text-gray-400 italic">No email addresses registered.</p>
          ) : (
            <ul className="divide-y divide-gray-100 rounded-lg border border-gray-200 bg-white">
              {profile.emails.map((entry) => (
                <li
                  key={entry.email}
                  className="flex items-center justify-between px-4 py-3 text-sm"
                >
                  <span className="flex items-center gap-2">
                    <span className="text-gray-800">{entry.email}</span>
                    {entry.locked && <LockedBadge source={entry.source} />}
                  </span>
                  {!entry.locked && (
                    <button
                      onClick={() => handleRemoveEmail(entry)}
                      className="text-gray-400 hover:text-red-500 transition-colors text-xs"
                      title="Remove"
                    >
                      Remove
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}

          {emailError && <p className="text-sm text-red-600">{emailError}</p>}

          <form onSubmit={handleAddEmail} className="flex gap-2">
            <input
              type="email"
              value={newEmail}
              onChange={(e) => setNewEmail(e.target.value)}
              placeholder="you@example.com"
              className="flex-1 rounded border border-gray-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
            />
            <button
              type="submit"
              disabled={emailBusy || !newEmail.trim()}
              className="px-4 py-2 rounded bg-slate-700 text-white text-sm hover:bg-slate-600 disabled:opacity-50 transition-colors"
            >
              Add
            </button>
          </form>
        </div>
      )}

      {/* Permissions tab */}
      {tab === 'permissions' && (
        <div className="space-y-4">
          <p className="text-sm text-gray-500">
            Repository access permissions granted to your account.
          </p>
          {loading ? (
            <p className="text-sm text-gray-400">Loading…</p>
          ) : permissions.length === 0 ? (
            <p className="text-sm text-gray-400 italic">No permissions configured.</p>
          ) : (
            <div className="rounded-lg border border-gray-200 bg-white overflow-hidden">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-gray-100 bg-gray-50 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide">
                    <th className="px-4 py-3">Provider</th>
                    <th className="px-4 py-3">Type</th>
                    <th className="px-4 py-3">Path</th>
                    <th className="px-4 py-3">Operations</th>
                    <th className="px-4 py-3">Source</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-100">
                  {permissions.map((p) => (
                    <tr key={p.id} className="hover:bg-gray-50 transition-colors">
                      <td className="px-4 py-3">
                        <span className="rounded bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600">
                          {p.provider}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <PathTypeBadge pathType={p.pathType} />
                      </td>
                      <td className="px-4 py-3 font-mono text-gray-700 text-xs">{p.path}</td>
                      <td className="px-4 py-3">
                        <OperationsBadge operations={p.operations} />
                      </td>
                      <td className="px-4 py-3">
                        {p.source === 'CONFIG' ? (
                          <LockedBadge source="config" />
                        ) : (
                          <span className="text-xs text-gray-400">local</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* SCM Identities tab */}
      {tab === 'identities' && (
        <div className="space-y-4">
          <p className="text-sm text-gray-500">
            Your usernames on upstream SCM providers. Used to verify that your SCM login matches the
            account pushing code through the proxy.
          </p>

          {profile.scmIdentities.length === 0 ? (
            <p className="text-sm text-gray-400 italic">No SCM identities registered.</p>
          ) : (
            <ul className="divide-y divide-gray-100 rounded-lg border border-gray-200 bg-white">
              {profile.scmIdentities.map((id) => (
                <li
                  key={`${id.provider}/${id.username}`}
                  className="flex items-center justify-between px-4 py-3 text-sm"
                >
                  <span className="flex items-center gap-2">
                    <span className="rounded bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600">
                      {id.provider}
                    </span>
                    <span className="text-gray-800">{id.username}</span>
                    {id.source === 'config' && <LockedBadge source="config" />}
                  </span>
                  {id.source !== 'config' && (
                    <button
                      onClick={() => handleRemoveIdentity(id)}
                      className="text-gray-400 hover:text-red-500 transition-colors text-xs"
                      title="Remove"
                    >
                      Remove
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}

          {identityError && <p className="text-sm text-red-600">{identityError}</p>}

          <form onSubmit={handleAddIdentity} className="flex gap-2">
            <select
              value={newProvider}
              onChange={(e) => setNewProvider(e.target.value)}
              className="rounded border border-gray-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
              disabled={providers.length === 0}
            >
              {providers.map((p) => (
                <option key={p.name} value={p.id}>
                  {p.host}
                </option>
              ))}
            </select>
            <input
              type="text"
              value={newScmUsername}
              onChange={(e) => setNewScmUsername(e.target.value)}
              placeholder="your-username"
              className="flex-1 rounded border border-gray-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
            />
            <button
              type="submit"
              disabled={identityBusy || !newScmUsername.trim()}
              className="px-4 py-2 rounded bg-slate-700 text-white text-sm hover:bg-slate-600 disabled:opacity-50 transition-colors"
            >
              Add
            </button>
          </form>
        </div>
      )}
    </div>
  )
}
