import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { fetchPushes, fetchUser } from '../api'
import { StatusBadge } from '../components/StatusBadge'
import type { EmailEntry, PushRecord, ScmIdentity, UserDetail as UserDetailType } from '../types'

interface UserDetailProps {
  authProvider: string
}

type Tab = 'overview' | 'pushes' | 'permissions'

// ── Permission stub data ─────────────────────────────────────────────────────

const PERMISSION_SCOPES = [
  {
    icon: '🌐',
    title: 'Provider',
    description: 'Access to all repositories on a provider',
    example: 'github.com — allow all pushes',
  },
  {
    icon: '👤',
    title: 'Owner / Organisation',
    description: 'Access scoped to an owner or org on a specific provider',
    example: 'github.com/acme — allow owner',
  },
  {
    icon: '📦',
    title: 'Repository',
    description: 'Access to a specific repository',
    example: 'github.com/acme/my-repo — allow repo',
  },
  {
    icon: '🌿',
    title: 'Ref (branch / tag)',
    description: 'Access restricted to matching refs',
    example: 'branch: main, tag: v*',
  },
  {
    icon: '🕐',
    title: 'Time-bound',
    description: 'Permission valid only within a date range',
    example: 'valid 2025-01-01 → 2025-12-31',
  },
]

// ── Sub-components ───────────────────────────────────────────────────────────

function LockedBadge({ source }: { source: string }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-blue-50 px-2 py-0.5 text-xs font-medium text-blue-600">
      locked ({source})
    </span>
  )
}

function VerifiedBadge() {
  return (
    <span className="inline-flex items-center rounded-full bg-green-50 px-2 py-0.5 text-xs font-medium text-green-600">
      verified
    </span>
  )
}

function OverviewTab({ user, isLocalAuth }: { user: UserDetailType; isLocalAuth: boolean }) {
  return (
    <div className="space-y-6">
      {/* Emails */}
      <section className="space-y-2">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold text-gray-700">Email Addresses</h3>
          {isLocalAuth && (
            <button
              disabled
              className="text-xs text-slate-400 cursor-not-allowed"
              title="Coming soon"
            >
              + Add
            </button>
          )}
        </div>
        {user.emails.length === 0 ? (
          <p className="text-sm text-gray-400 italic">No email addresses registered.</p>
        ) : (
          <ul className="divide-y divide-gray-100 rounded-lg border border-gray-200 bg-white">
            {user.emails.map((entry: EmailEntry) => (
              <li key={entry.email} className="flex items-center justify-between px-4 py-3 text-sm">
                <span className="flex items-center gap-2">
                  <span className="text-gray-800">{entry.email}</span>
                  {entry.verified && <VerifiedBadge />}
                  {entry.locked && <LockedBadge source={entry.source} />}
                </span>
                {isLocalAuth && !entry.locked && (
                  <button
                    disabled
                    className="text-gray-300 text-xs cursor-not-allowed"
                    title="Coming soon"
                  >
                    Remove
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* SCM Identities */}
      <section className="space-y-2">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold text-gray-700">SCM Identities</h3>
          {isLocalAuth && (
            <button
              disabled
              className="text-xs text-slate-400 cursor-not-allowed"
              title="Coming soon"
            >
              + Add
            </button>
          )}
        </div>
        {user.scmIdentities.length === 0 ? (
          <p className="text-sm text-gray-400 italic">No SCM identities registered.</p>
        ) : (
          <ul className="divide-y divide-gray-100 rounded-lg border border-gray-200 bg-white">
            {user.scmIdentities.map((id: ScmIdentity) => (
              <li
                key={`${id.provider}/${id.username}`}
                className="flex items-center justify-between px-4 py-3 text-sm"
              >
                <span className="flex items-center gap-2">
                  <span className="rounded bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600">
                    {id.provider}
                  </span>
                  <span className="text-gray-800">{id.username}</span>
                  {id.verified && <VerifiedBadge />}
                </span>
                {isLocalAuth && (
                  <button
                    disabled
                    className="text-gray-300 text-xs cursor-not-allowed"
                    title="Coming soon"
                  >
                    Remove
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* Push summary */}
      <section className="space-y-2">
        <h3 className="text-sm font-semibold text-gray-700">Push Summary</h3>
        <div className="flex flex-wrap gap-3">
          {Object.entries(user.pushCounts).length === 0 ? (
            <p className="text-sm text-gray-400 italic">No push activity recorded.</p>
          ) : (
            Object.entries(user.pushCounts).map(([status, count]) => (
              <div key={status} className="flex items-center gap-2">
                <StatusBadge status={status} />
                <span className="text-sm font-semibold text-gray-700">{String(count)}</span>
              </div>
            ))
          )}
        </div>
      </section>

      {isLocalAuth && (
        <section className="border-t border-gray-100 pt-4">
          <h3 className="text-sm font-semibold text-gray-700 mb-2">Account Actions</h3>
          <div className="flex gap-2">
            <button
              disabled
              className="px-3 py-1.5 rounded border border-gray-200 text-xs text-gray-400 cursor-not-allowed"
              title="Coming soon"
            >
              Reset Password
            </button>
            <button
              disabled
              className="px-3 py-1.5 rounded border border-red-100 text-xs text-red-300 cursor-not-allowed"
              title="Coming soon"
            >
              Delete User
            </button>
          </div>
        </section>
      )}
    </div>
  )
}

function PushesTab({ username }: { username: string }) {
  const navigate = useNavigate()
  const [pushes, setPushes] = useState<PushRecord[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const params = new URLSearchParams({ user: username, limit: '50', newestFirst: 'true' })
    fetchPushes(params)
      .then(setPushes)
      .catch(console.error)
      .finally(() => setLoading(false))
  }, [username])

  if (loading) return <div className="py-8 text-center text-gray-400 text-sm">Loading…</div>

  if (pushes.length === 0)
    return (
      <p className="text-sm text-gray-400 italic py-8 text-center">
        No push records found for this user.
      </p>
    )

  return (
    <div className="rounded-lg border border-gray-200 bg-white overflow-hidden">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-gray-100 bg-gray-50 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide">
            <th className="px-4 py-3">Status</th>
            <th className="px-4 py-3">Repository</th>
            <th className="px-4 py-3">Branch</th>
            <th className="px-4 py-3">Time</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100">
          {pushes.map((p) => (
            <tr
              key={p.id}
              className="hover:bg-gray-50 cursor-pointer transition-colors"
              onClick={() => navigate(`/push/${p.id}`)}
            >
              <td className="px-4 py-3">
                <StatusBadge status={p.status} />
              </td>
              <td className="px-4 py-3 text-gray-700">
                {p.project && p.repoName ? `${p.project}/${p.repoName}` : (p.repoName ?? '—')}
              </td>
              <td className="px-4 py-3 text-gray-500">{p.branch ?? '—'}</td>
              <td className="px-4 py-3 text-gray-400">
                {p.timestamp ? new Date(p.timestamp).toLocaleString() : '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function PermissionsTab({ isLocalAuth }: { isLocalAuth: boolean }) {
  return (
    <div className="space-y-4">
      <div className="rounded-lg border border-dashed border-slate-300 bg-slate-50 px-5 py-4">
        <p className="text-sm font-medium text-slate-600">Permissions — coming soon</p>
        <p className="text-xs text-slate-400 mt-1">
          Per-user push permissions will be configurable at multiple scopes. The taxonomy below
          shows what will be supported.
        </p>
      </div>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {PERMISSION_SCOPES.map(({ icon, title, description, example }) => (
          <div
            key={title}
            className="rounded-lg border border-gray-200 bg-white px-4 py-3 space-y-1 opacity-60"
          >
            <div className="flex items-center gap-2 text-sm font-medium text-gray-700">
              <span>{icon}</span>
              <span>{title}</span>
            </div>
            <p className="text-xs text-gray-500">{description}</p>
            <p className="text-xs font-mono text-slate-400 bg-slate-50 rounded px-2 py-0.5 inline-block">
              {example}
            </p>
          </div>
        ))}
      </div>

      {isLocalAuth && (
        <button
          disabled
          className="px-3 py-1.5 rounded bg-slate-700 text-white text-sm opacity-30 cursor-not-allowed"
        >
          + Add Permission
        </button>
      )}
    </div>
  )
}

// ── Main component ───────────────────────────────────────────────────────────

export function UserDetail({ authProvider }: UserDetailProps) {
  const { username } = useParams<{ username: string }>()
  const navigate = useNavigate()
  const [user, setUser] = useState<UserDetailType | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('overview')

  const isLocalAuth = authProvider === 'local'

  useEffect(() => {
    if (!username) return
    fetchUser(username)
      .then(setUser)
      .catch(() => setError('User not found'))
      .finally(() => setLoading(false))
  }, [username])

  if (loading)
    return <div className="max-w-3xl mx-auto px-4 py-16 text-center text-gray-400">Loading…</div>
  if (error || !user)
    return (
      <div className="max-w-3xl mx-auto px-4 py-16 text-center text-red-500">
        {error ?? 'User not found'}
      </div>
    )

  const tabs: { id: Tab; label: string }[] = [
    { id: 'overview', label: 'Overview' },
    { id: 'pushes', label: 'Pushes' },
    { id: 'permissions', label: 'Permissions' },
  ]

  return (
    <div className="max-w-3xl mx-auto px-4 py-6 space-y-6">
      {/* Header */}
      <div className="flex items-center gap-3">
        <button
          onClick={() => navigate('/users')}
          className="text-xs text-gray-400 hover:text-gray-600 transition-colors"
        >
          ← Users
        </button>
        <span className="text-gray-300">/</span>
        <h2 className="text-lg font-semibold text-gray-800">{user.username}</h2>
        {!isLocalAuth && (
          <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-500">
            {authProvider}
          </span>
        )}
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-gray-200">
        {tabs.map((t) => (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            className={
              'px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px ' +
              (tab === t.id
                ? 'border-slate-700 text-slate-800'
                : 'border-transparent text-gray-500 hover:text-gray-700')
            }
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'overview' && <OverviewTab user={user} isLocalAuth={isLocalAuth} />}
      {tab === 'pushes' && <PushesTab username={user.username} />}
      {tab === 'permissions' && <PermissionsTab isLocalAuth={isLocalAuth} />}
    </div>
  )
}
