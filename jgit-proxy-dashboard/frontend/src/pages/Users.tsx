import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchUsers } from '../api'
import type { PushStatus, UserSummary } from '../types'

interface UsersProps {
  authProvider: string
}

const PUSH_STAT_CONFIG: {
  status: PushStatus
  label: string
  icon: string
  classes: string
}[] = [
  {
    status: 'FORWARDED',
    label: 'Forwarded',
    icon: '✓',
    classes: 'bg-blue-50 text-blue-700 border-blue-200',
  },
  {
    status: 'APPROVED',
    label: 'Approved',
    icon: '✓',
    classes: 'bg-green-50 text-green-700 border-green-200',
  },
  {
    status: 'BLOCKED',
    label: 'Blocked',
    icon: '⊘',
    classes: 'bg-amber-50 text-amber-700 border-amber-200',
  },
  {
    status: 'REJECTED',
    label: 'Rejected',
    icon: '✗',
    classes: 'bg-red-50 text-red-700 border-red-200',
  },
]

function PushStatChip({
  count,
  icon,
  label,
  classes,
}: {
  count: number
  icon: string
  label: string
  classes: string
}) {
  if (count === 0) return null
  return (
    <span
      className={`inline-flex items-center gap-1 rounded border px-1.5 py-0.5 text-xs font-medium ${classes}`}
      title={`${count} ${label}`}
    >
      <span>{icon}</span>
      <span>{count}</span>
    </span>
  )
}

export function Users({ authProvider }: UsersProps) {
  const navigate = useNavigate()
  const [users, setUsers] = useState<UserSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [search, setSearch] = useState('')

  const isLocalAuth = authProvider === 'local'

  useEffect(() => {
    fetchUsers()
      .then(setUsers)
      .catch(() => setError('Failed to load users'))
      .finally(() => setLoading(false))
  }, [])

  const filtered = users.filter(
    (u) =>
      u.username.toLowerCase().includes(search.toLowerCase()) ||
      (u.primaryEmail ?? '').toLowerCase().includes(search.toLowerCase()),
  )

  if (loading)
    return <div className="max-w-5xl mx-auto px-4 py-16 text-center text-gray-400">Loading…</div>
  if (error)
    return <div className="max-w-5xl mx-auto px-4 py-16 text-center text-red-500">{error}</div>

  return (
    <div className="max-w-5xl mx-auto px-4 py-6 space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold text-gray-800">Users</h2>
        {isLocalAuth && (
          <button
            disabled
            className="px-3 py-1.5 rounded bg-slate-700 text-white text-sm opacity-40 cursor-not-allowed"
            title="User creation coming soon"
          >
            + Add User
          </button>
        )}
      </div>

      <input
        type="text"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        placeholder="Search by username or email…"
        className="w-full rounded border border-gray-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
      />

      {filtered.length === 0 ? (
        <p className="text-sm text-gray-400 italic py-8 text-center">No users found.</p>
      ) : (
        <div className="rounded-lg border border-gray-200 bg-white overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-100 bg-gray-50 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide">
                <th className="px-4 py-3">Username</th>
                <th className="px-4 py-3">Email</th>
                <th className="px-4 py-3">SCM Identities</th>
                <th className="px-4 py-3">Push Activity</th>
                <th className="px-4 py-3"></th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {filtered.map((u) => (
                <tr
                  key={u.username}
                  className="hover:bg-gray-50 cursor-pointer transition-colors"
                  onClick={() => navigate(`/users/${encodeURIComponent(u.username)}`)}
                >
                  <td className="px-4 py-3 font-medium text-gray-800">{u.username}</td>
                  <td className="px-4 py-3 text-gray-500">
                    {u.primaryEmail ?? <span className="italic text-gray-300">none</span>}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex flex-wrap gap-1">
                      {u.scmProviders.length === 0 ? (
                        <span className="italic text-gray-300 text-xs">none</span>
                      ) : (
                        u.scmProviders.map((p) => (
                          <span
                            key={p}
                            className="rounded bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600"
                          >
                            {p}
                          </span>
                        ))
                      )}
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex gap-1">
                      {PUSH_STAT_CONFIG.map(({ status, icon, label, classes }) => (
                        <PushStatChip
                          key={status}
                          count={u.pushCounts[status] ?? 0}
                          icon={icon}
                          label={label}
                          classes={classes}
                        />
                      ))}
                    </div>
                  </td>
                  <td className="px-4 py-3 text-right">
                    <span className="text-gray-400 text-xs">›</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
