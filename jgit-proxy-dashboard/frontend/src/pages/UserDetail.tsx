import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  addUserEmail,
  addUserIdentity,
  addUserPermission,
  deleteUser,
  deleteUserPermission,
  fetchProviders,
  fetchPushes,
  fetchUser,
  fetchUserPermissions,
  removeUserEmail,
  removeUserIdentity,
  resetUserPassword,
} from '../api'
import { OperationsBadge, PathTypeBadge } from '../components/PermissionBadges'
import { StatusBadge } from '../components/StatusBadge'
import type {
  CurrentUser,
  EmailEntry,
  Provider,
  PushRecord,
  RepoPermission,
  ScmIdentity,
  UserDetail as UserDetailType,
} from '../types'

interface UserDetailProps {
  authProvider: string
  currentUser: CurrentUser | null
}

type Tab = 'overview' | 'pushes' | 'permissions'

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

function AddEmailModal({
  username,
  onClose,
  onAdded,
}: {
  username: string
  onClose: () => void
  onAdded: () => void
}) {
  const [email, setEmail] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await addUserEmail(username, email.trim())
      onAdded()
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to add email')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="w-full max-w-sm rounded-lg bg-white p-6 shadow-xl">
        <h3 className="text-base font-semibold text-gray-800 mb-4">Add Email</h3>
        <form onSubmit={handleSubmit} className="space-y-3">
          <input
            required
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="user@example.com"
            className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm focus:border-slate-500 focus:outline-none"
          />
          {error && <p className="text-xs text-red-500">{error}</p>}
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={onClose}
              className="px-3 py-1.5 rounded border border-gray-200 text-xs text-gray-600 hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="px-3 py-1.5 rounded bg-slate-700 text-white text-xs hover:bg-slate-800 disabled:opacity-50"
            >
              {submitting ? 'Adding…' : 'Add'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

function AddScmIdentityModal({
  username,
  onClose,
  onAdded,
}: {
  username: string
  onClose: () => void
  onAdded: () => void
}) {
  const [provider, setProvider] = useState('')
  const [scmUsername, setScmUsername] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await addUserIdentity(username, provider.trim(), scmUsername.trim())
      onAdded()
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to add identity')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="w-full max-w-sm rounded-lg bg-white p-6 shadow-xl">
        <h3 className="text-base font-semibold text-gray-800 mb-4">Add SCM Identity</h3>
        <form onSubmit={handleSubmit} className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">Provider</label>
            <input
              required
              value={provider}
              onChange={(e) => setProvider(e.target.value)}
              placeholder="github"
              className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm focus:border-slate-500 focus:outline-none"
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">SCM Username</label>
            <input
              required
              value={scmUsername}
              onChange={(e) => setScmUsername(e.target.value)}
              placeholder="github-handle"
              className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm focus:border-slate-500 focus:outline-none"
            />
          </div>
          {error && <p className="text-xs text-red-500">{error}</p>}
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={onClose}
              className="px-3 py-1.5 rounded border border-gray-200 text-xs text-gray-600 hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="px-3 py-1.5 rounded bg-slate-700 text-white text-xs hover:bg-slate-800 disabled:opacity-50"
            >
              {submitting ? 'Adding…' : 'Add'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

function ResetPasswordModal({ username, onClose }: { username: string; onClose: () => void }) {
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (password !== confirm) {
      setError('Passwords do not match')
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      await resetUserPassword(username, password)
      setDone(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to reset password')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="w-full max-w-sm rounded-lg bg-white p-6 shadow-xl">
        <h3 className="text-base font-semibold text-gray-800 mb-4">Reset Password — {username}</h3>
        {done ? (
          <div className="space-y-3">
            <p className="text-sm text-green-600">Password updated successfully.</p>
            <div className="flex justify-end">
              <button
                onClick={onClose}
                className="px-3 py-1.5 rounded bg-slate-700 text-white text-xs"
              >
                Close
              </button>
            </div>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="space-y-3">
            <div>
              <label className="block text-xs font-medium text-gray-600 mb-1">New Password</label>
              <input
                required
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm focus:border-slate-500 focus:outline-none"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-600 mb-1">
                Confirm Password
              </label>
              <input
                required
                type="password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm focus:border-slate-500 focus:outline-none"
              />
            </div>
            {error && <p className="text-xs text-red-500">{error}</p>}
            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={onClose}
                className="px-3 py-1.5 rounded border border-gray-200 text-xs text-gray-600 hover:bg-gray-50"
              >
                Cancel
              </button>
              <button
                type="submit"
                disabled={submitting}
                className="px-3 py-1.5 rounded bg-slate-700 text-white text-xs hover:bg-slate-800 disabled:opacity-50"
              >
                {submitting ? 'Saving…' : 'Update Password'}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  )
}

function OverviewTab({
  user,
  isLocalAuth,
  onRefresh,
  onDeleted,
}: {
  user: UserDetailType
  isLocalAuth: boolean
  onRefresh: () => void
  onDeleted: () => void
}) {
  const [showAddEmail, setShowAddEmail] = useState(false)
  const [showAddScm, setShowAddScm] = useState(false)
  const [showResetPw, setShowResetPw] = useState(false)
  const [deletingEmail, setDeletingEmail] = useState<string | null>(null)
  const [deletingScm, setDeletingScm] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  async function handleRemoveEmail(email: string) {
    setDeletingEmail(email)
    setActionError(null)
    try {
      await removeUserEmail(user.username, email)
      onRefresh()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : 'Failed to remove email')
    } finally {
      setDeletingEmail(null)
    }
  }

  async function handleRemoveScm(provider: string, scmUsername: string) {
    const key = `${provider}/${scmUsername}`
    setDeletingScm(key)
    setActionError(null)
    try {
      await removeUserIdentity(user.username, provider, scmUsername)
      onRefresh()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : 'Failed to remove SCM identity')
    } finally {
      setDeletingScm(null)
    }
  }

  async function handleDelete() {
    if (!confirm(`Delete user "${user.username}"? This cannot be undone.`)) return
    setDeleting(true)
    setActionError(null)
    try {
      await deleteUser(user.username)
      onDeleted()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : 'Failed to delete user')
      setDeleting(false)
    }
  }

  return (
    <div className="space-y-6">
      {showAddEmail && (
        <AddEmailModal
          username={user.username}
          onClose={() => setShowAddEmail(false)}
          onAdded={onRefresh}
        />
      )}
      {showAddScm && (
        <AddScmIdentityModal
          username={user.username}
          onClose={() => setShowAddScm(false)}
          onAdded={onRefresh}
        />
      )}
      {showResetPw && (
        <ResetPasswordModal username={user.username} onClose={() => setShowResetPw(false)} />
      )}

      {/* Emails */}
      <section className="space-y-2">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold text-gray-700">Email Addresses</h3>
          {isLocalAuth && (
            <button
              onClick={() => setShowAddEmail(true)}
              className="text-xs text-slate-600 hover:text-slate-800"
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
                    onClick={() => handleRemoveEmail(entry.email)}
                    disabled={deletingEmail === entry.email}
                    className="text-red-400 text-xs hover:text-red-600 disabled:opacity-40"
                  >
                    {deletingEmail === entry.email ? '…' : 'Remove'}
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
              onClick={() => setShowAddScm(true)}
              className="text-xs text-slate-600 hover:text-slate-800"
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
                  {id.source === 'config' && <LockedBadge source="config" />}
                </span>
                {isLocalAuth && id.source !== 'config' && (
                  <button
                    onClick={() => handleRemoveScm(id.provider, id.username)}
                    disabled={deletingScm === `${id.provider}/${id.username}`}
                    className="text-red-400 text-xs hover:text-red-600 disabled:opacity-40"
                  >
                    {deletingScm === `${id.provider}/${id.username}` ? '…' : 'Remove'}
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

      {actionError && <p className="text-xs text-red-500">{actionError}</p>}

      {isLocalAuth && (
        <section className="border-t border-gray-100 pt-4">
          <h3 className="text-sm font-semibold text-gray-700 mb-2">Account Actions</h3>
          <div className="flex gap-2">
            <button
              onClick={() => setShowResetPw(true)}
              className="px-3 py-1.5 rounded border border-gray-200 text-xs text-gray-600 hover:bg-gray-50"
            >
              Reset Password
            </button>
            <button
              onClick={handleDelete}
              disabled={deleting}
              className="px-3 py-1.5 rounded border border-red-200 text-xs text-red-600 hover:bg-red-50 disabled:opacity-40"
            >
              {deleting ? 'Deleting…' : 'Delete User'}
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

function AddPermissionModal({
  username,
  onClose,
  onAdded,
}: {
  username: string
  onClose: () => void
  onAdded: () => void
}) {
  const [providers, setProviders] = useState<Provider[]>([])
  const [provider, setProvider] = useState('')
  const [path, setPath] = useState('')
  const [pathType, setPathType] = useState<'LITERAL' | 'GLOB' | 'REGEX'>('LITERAL')
  const [operations, setOperations] = useState<'PUSH' | 'APPROVE' | 'ALL'>('ALL')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [regexError, setRegexError] = useState<string | null>(null)

  function handlePathChange(value: string) {
    setPath(value)
    if (pathType === 'REGEX') {
      try {
        new RegExp(value)
        setRegexError(null)
      } catch (e) {
        setRegexError(e instanceof SyntaxError ? e.message : 'Invalid regex')
      }
    } else {
      setRegexError(null)
    }
  }

  function handlePathTypeChange(value: typeof pathType) {
    setPathType(value)
    if (value === 'REGEX' && path) {
      try {
        new RegExp(path)
        setRegexError(null)
      } catch (e) {
        setRegexError(e instanceof SyntaxError ? e.message : 'Invalid regex')
      }
    } else {
      setRegexError(null)
    }
  }

  useEffect(() => {
    fetchProviders()
      .then((list: Provider[]) => {
        setProviders(list)
        if (list.length > 0) setProvider(list[0].name)
      })
      .catch(console.error)
  }, [])

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await addUserPermission(username, {
        provider: provider.trim(),
        path: path.trim(),
        pathType,
        operations,
      })
      onAdded()
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to add permission')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="w-full max-w-sm rounded-lg bg-white p-6 shadow-xl">
        <h3 className="text-base font-semibold text-gray-800 mb-4">Add Permission</h3>
        <form onSubmit={handleSubmit} className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">Provider</label>
            <select
              required
              value={provider}
              onChange={(e) => setProvider(e.target.value)}
              disabled={providers.length === 0}
              className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm focus:border-slate-500 focus:outline-none disabled:bg-gray-50 disabled:text-gray-400"
            >
              {providers.length === 0 && <option value="">Loading…</option>}
              {providers.map((p) => (
                <option key={p.name} value={p.name}>
                  {p.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">Path</label>
            <input
              required
              value={path}
              onChange={(e) => handlePathChange(e.target.value)}
              placeholder="/owner/repo"
              className={`w-full rounded border px-3 py-1.5 text-sm font-mono focus:outline-none ${regexError ? 'border-amber-400 focus:border-amber-500' : 'border-gray-300 focus:border-slate-500'}`}
            />
            {regexError && <p className="mt-1 text-xs text-amber-600">⚠ {regexError}</p>}
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">Path Type</label>
            <select
              value={pathType}
              onChange={(e) => handlePathTypeChange(e.target.value as typeof pathType)}
              className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm focus:border-slate-500 focus:outline-none"
            >
              <option value="LITERAL">Literal — exact match</option>
              <option value="GLOB">Glob — wildcard (* / **)</option>
              <option value="REGEX">Regex — full Java regex</option>
            </select>
          </div>
          <div>
            <label className="block text-xs font-medium text-gray-600 mb-1">Operations</label>
            <select
              value={operations}
              onChange={(e) => setOperations(e.target.value as typeof operations)}
              className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm focus:border-slate-500 focus:outline-none"
            >
              <option value="ALL">All (push + approve)</option>
              <option value="PUSH">Push only</option>
              <option value="APPROVE">Approve only</option>
            </select>
          </div>
          {error && <p className="text-xs text-red-500">{error}</p>}
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={onClose}
              className="px-3 py-1.5 rounded border border-gray-200 text-xs text-gray-600 hover:bg-gray-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="px-3 py-1.5 rounded bg-slate-700 text-white text-xs hover:bg-slate-800 disabled:opacity-50"
            >
              {submitting ? 'Adding…' : 'Add'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

function PermissionsTab({ username, isAdmin }: { username: string; isAdmin: boolean }) {
  const [permissions, setPermissions] = useState<RepoPermission[]>([])
  const [loading, setLoading] = useState(true)
  const [showAdd, setShowAdd] = useState(false)
  const [deletingId, setDeletingId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  function loadPermissions() {
    setLoading(true)
    fetchUserPermissions(username)
      .then(setPermissions)
      .catch(console.error)
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    loadPermissions()
  }, [username])

  async function handleDelete(id: string) {
    setDeletingId(id)
    setActionError(null)
    try {
      await deleteUserPermission(username, id)
      loadPermissions()
    } catch (err) {
      setActionError(err instanceof Error ? err.message : 'Failed to remove permission')
    } finally {
      setDeletingId(null)
    }
  }

  if (loading) return <div className="py-8 text-center text-gray-400 text-sm">Loading…</div>

  return (
    <div className="space-y-4">
      {showAdd && (
        <AddPermissionModal
          username={username}
          onClose={() => setShowAdd(false)}
          onAdded={loadPermissions}
        />
      )}

      {permissions.length === 0 ? (
        <p className="text-sm text-gray-400 italic py-4">
          No permissions configured for this user.
        </p>
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
                {isAdmin && <th className="px-4 py-3" />}
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
                  {isAdmin && (
                    <td className="px-4 py-3 text-right">
                      {p.source !== 'CONFIG' && (
                        <button
                          onClick={() => handleDelete(p.id)}
                          disabled={deletingId === p.id}
                          className="text-red-400 text-xs hover:text-red-600 disabled:opacity-40"
                        >
                          {deletingId === p.id ? '…' : 'Remove'}
                        </button>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {actionError && <p className="text-xs text-red-500">{actionError}</p>}

      {isAdmin && (
        <button
          onClick={() => setShowAdd(true)}
          className="px-3 py-1.5 rounded bg-slate-700 text-white text-xs hover:bg-slate-800"
        >
          + Add Permission
        </button>
      )}

      <div className="pt-2 space-y-2">
        <p className="text-xs font-semibold text-gray-400 uppercase tracking-wide">Coming soon</p>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div className="rounded-lg border border-dashed border-gray-200 bg-gray-50 px-4 py-3 space-y-1 opacity-70">
            <p className="text-sm font-medium text-gray-500">Time-bound permissions</p>
            <p className="text-xs text-gray-400">
              Grant access valid only within a date range, e.g. valid 2025-01-01 → 2025-12-31.
            </p>
          </div>
          <div className="rounded-lg border border-dashed border-gray-200 bg-gray-50 px-4 py-3 space-y-1 opacity-70">
            <p className="text-sm font-medium text-gray-500">Just-in-time permissions</p>
            <p className="text-xs text-gray-400">
              Self-service, short-lived access requests with automatic expiry and audit trail.
            </p>
          </div>
        </div>
      </div>
    </div>
  )
}

// ── Main component ───────────────────────────────────────────────────────────

export function UserDetail({ authProvider, currentUser }: UserDetailProps) {
  const { username } = useParams<{ username: string }>()
  const navigate = useNavigate()
  const [user, setUser] = useState<UserDetailType | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('overview')

  const isLocalAuth = authProvider === 'local'
  const isAdmin = currentUser?.authorities?.includes('ROLE_ADMIN') ?? false

  function loadUser() {
    if (!username) return
    fetchUser(username)
      .then(setUser)
      .catch(() => setError('User not found'))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    loadUser()
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

      {tab === 'overview' && (
        <OverviewTab
          user={user}
          isLocalAuth={isLocalAuth}
          onRefresh={loadUser}
          onDeleted={() => navigate('/users')}
        />
      )}
      {tab === 'pushes' && <PushesTab username={user.username} />}
      {tab === 'permissions' && <PermissionsTab username={user.username} isAdmin={isAdmin} />}
    </div>
  )
}
