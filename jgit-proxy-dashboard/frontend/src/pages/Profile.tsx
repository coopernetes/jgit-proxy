import { useEffect, useState } from 'react'
import { addEmail, addScmIdentity, fetchMe, removeEmail, removeScmIdentity } from '../api'
import type { CurrentUser, EmailEntry, ScmIdentity } from '../types'

const KNOWN_PROVIDERS = ['github', 'gitlab', 'codeberg', 'gitea', 'bitbucket']

function LockedBadge({ source }: { source: string }) {
  return (
    <span
      className="inline-flex items-center gap-1 rounded-full bg-blue-50 px-2 py-0.5 text-xs font-medium text-blue-600"
      title={`Email address is managed by your ${source.toUpperCase()} identity provider and cannot be removed`}
    >
      locked ({source})
    </span>
  )
}

export function Profile() {
  const [profile, setProfile] = useState<CurrentUser | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [tab, setTab] = useState<'emails' | 'identities'>('emails')

  const [newEmail, setNewEmail] = useState('')
  const [emailError, setEmailError] = useState<string | null>(null)
  const [emailBusy, setEmailBusy] = useState(false)

  const [newProvider, setNewProvider] = useState(KNOWN_PROVIDERS[0])
  const [newScmUsername, setNewScmUsername] = useState('')
  const [identityError, setIdentityError] = useState<string | null>(null)
  const [identityBusy, setIdentityBusy] = useState(false)

  useEffect(() => {
    fetchMe()
      .then(setProfile)
      .catch(() => setError('Failed to load profile'))
      .finally(() => setLoading(false))
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
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-gray-200">
        {(['emails', 'identities'] as const).map((t) => (
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
            {t === 'emails' ? 'Email Addresses' : 'SCM Identities'}
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

      {/* SCM Identities tab */}
      {tab === 'identities' && (
        <div className="space-y-4">
          <p className="text-sm text-gray-500">
            Your usernames on upstream SCM providers. Used to verify that push credentials match the
            commit author.
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
                  </span>
                  <button
                    onClick={() => handleRemoveIdentity(id)}
                    className="text-gray-400 hover:text-red-500 transition-colors text-xs"
                    title="Remove"
                  >
                    Remove
                  </button>
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
            >
              {KNOWN_PROVIDERS.map((p) => (
                <option key={p} value={p}>
                  {p}
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
