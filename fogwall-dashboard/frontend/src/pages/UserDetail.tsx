import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import {
  addUserEmail,
  addUserIdentity,
  addUserPermission,
  deleteUser,
  deleteUserPermission,
  fetchProviders,
  fetchPushes,
  fetchUser,
  fetchUserGroups,
  fetchUserPermissions,
  removeUserEmail,
  removeUserIdentity,
  resetUserPassword,
  testUserPermission,
} from '../api'
import type { PermissionTestResponse } from '../api'
import { OperationsBadge, PathTypeBadge } from '../components/PermissionBadges'
import { StatusBadge } from '../components/StatusBadge'
import type {
  CurrentUser,
  EmailEntry,
  GroupPermissionRule,
  Provider,
  PushRecord,
  RepoPermission,
  ScmIdentity,
  UserDetail as UserDetailType,
} from '../types'

interface UserGroupView {
  id: string
  name: string
  description: string | null
  source: string
  rules: GroupPermissionRule[]
}

interface UserDetailProps {
  authProvider: string
  currentUser: CurrentUser | null
}

type Tab = 'overview' | 'pushes' | 'permissions'

function LockedBadge({ source }: { source: string }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-blue-50 px-2 py-0.5 text-xs font-medium text-blue-600 dark:bg-blue-900/30 dark:text-blue-300">
      locked ({source})
    </span>
  )
}

function VerifiedBadge() {
  return (
    <span className="inline-flex items-center rounded-full bg-green-50 px-2 py-0.5 text-xs font-medium text-green-600 dark:bg-green-900/30 dark:text-green-300">
      verified
    </span>
  )
}

const modalClass =
  'fixed inset-0 z-50 flex items-center justify-center bg-black/40 dark:bg-black/60'
const modalPanelClass = 'w-full max-w-sm rounded-lg bg-white p-6 shadow-xl dark:bg-slate-800'
const modalTitleClass = 'text-base font-semibold text-gray-800 mb-4 dark:text-gray-200'
const labelClass = 'block text-xs font-medium text-gray-600 mb-1 dark:text-gray-400'
const inputClass =
  'w-full rounded border border-gray-300 px-3 py-1.5 text-sm focus:border-slate-500 focus:outline-none dark:bg-slate-700 dark:border-slate-600 dark:text-gray-200'
const cancelBtnClass =
  'px-3 py-1.5 rounded border border-gray-200 text-xs text-gray-600 hover:bg-gray-50 dark:border-slate-600 dark:text-gray-400 dark:hover:bg-gray-700'
const submitBtnClass =
  'px-3 py-1.5 rounded bg-slate-700 text-white text-xs hover:bg-slate-800 disabled:opacity-50'

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
    <div className={modalClass}>
      <div className={modalPanelClass}>
        <h3 className={modalTitleClass}>Add Email</h3>
        <form onSubmit={handleSubmit} className="space-y-3">
          <input
            required
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="user@example.com"
            className={inputClass}
          />
          {error && <p className="text-xs text-red-500 dark:text-red-400">{error}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={onClose} className={cancelBtnClass}>
              Cancel
            </button>
            <button type="submit" disabled={submitting} className={submitBtnClass}>
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
  const [providers, setProviders] = useState<Provider[]>([])
  const [provider, setProvider] = useState('')
  const [scmUsername, setScmUsername] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchProviders()
      .then((list: Provider[]) => {
        setProviders(list)
        if (list.length > 0) setProvider(list[0].id)
      })
      .catch(console.error)
  }, [])

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
    <div className={modalClass}>
      <div className={modalPanelClass}>
        <h3 className={modalTitleClass}>Add SCM Identity</h3>
        <form onSubmit={handleSubmit} className="space-y-3">
          <div>
            <label className={labelClass}>Provider</label>
            <select
              required
              value={provider}
              onChange={(e) => setProvider(e.target.value)}
              disabled={providers.length === 0}
              className={`${inputClass} disabled:opacity-50`}
            >
              {providers.length === 0 && <option value="">Loading…</option>}
              {providers.map((p) => (
                <option key={p.name} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className={labelClass}>SCM Username</label>
            <input
              required
              value={scmUsername}
              onChange={(e) => setScmUsername(e.target.value)}
              placeholder="github-handle"
              className={inputClass}
            />
          </div>
          {error && <p className="text-xs text-red-500 dark:text-red-400">{error}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={onClose} className={cancelBtnClass}>
              Cancel
            </button>
            <button type="submit" disabled={submitting} className={submitBtnClass}>
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
    <div className={modalClass}>
      <div className={modalPanelClass}>
        <h3 className={modalTitleClass}>Reset Password — {username}</h3>
        {done ? (
          <div className="space-y-3">
            <p className="text-sm text-green-600 dark:text-green-400">
              Password updated successfully.
            </p>
            <div className="flex justify-end">
              <button onClick={onClose} className={submitBtnClass}>
                Close
              </button>
            </div>
          </div>
        ) : (
          <form onSubmit={handleSubmit} className="space-y-3">
            <div>
              <label className={labelClass}>New Password</label>
              <input
                required
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                className={inputClass}
              />
            </div>
            <div>
              <label className={labelClass}>Confirm Password</label>
              <input
                required
                type="password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                className={inputClass}
              />
            </div>
            {error && <p className="text-xs text-red-500 dark:text-red-400">{error}</p>}
            <div className="flex justify-end gap-2">
              <button type="button" onClick={onClose} className={cancelBtnClass}>
                Cancel
              </button>
              <button type="submit" disabled={submitting} className={submitBtnClass}>
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
  isAdmin,
  onRefresh,
  onDeleted,
}: {
  user: UserDetailType
  isLocalAuth: boolean
  isAdmin: boolean
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

  const listClass =
    'divide-y divide-gray-100 rounded-lg border border-gray-200 bg-white dark:bg-slate-800 dark:border-slate-700 dark:divide-gray-700'

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
          <h3 className="text-sm font-semibold text-gray-700 dark:text-gray-300">
            Email Addresses
          </h3>
          {isAdmin && (
            <button
              onClick={() => setShowAddEmail(true)}
              className="text-xs text-slate-600 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200"
            >
              + Add
            </button>
          )}
        </div>
        {user.emails.length === 0 ? (
          <p className="text-sm text-gray-400 italic dark:text-gray-500">
            No email addresses registered.
          </p>
        ) : (
          <ul className={listClass}>
            {user.emails.map((entry: EmailEntry) => (
              <li key={entry.email} className="flex items-center justify-between px-4 py-3 text-sm">
                <span className="flex items-center gap-2">
                  <span className="text-gray-800 dark:text-gray-200">{entry.email}</span>
                  {entry.verified && <VerifiedBadge />}
                  {entry.locked && <LockedBadge source={entry.source} />}
                </span>
                {isAdmin && !entry.locked && (
                  <button
                    onClick={() => handleRemoveEmail(entry.email)}
                    disabled={deletingEmail === entry.email}
                    className="text-red-400 text-xs hover:text-red-600 disabled:opacity-40 dark:text-red-500 dark:hover:text-red-300"
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
          <h3 className="text-sm font-semibold text-gray-700 dark:text-gray-300">SCM Identities</h3>
          {isAdmin && (
            <button
              onClick={() => setShowAddScm(true)}
              className="text-xs text-slate-600 hover:text-slate-800 dark:text-slate-400 dark:hover:text-slate-200"
            >
              + Add
            </button>
          )}
        </div>
        {user.scmIdentities.length === 0 ? (
          <p className="text-sm text-gray-400 italic dark:text-gray-500">
            No SCM identities registered.
          </p>
        ) : (
          <ul className={listClass}>
            {user.scmIdentities.map((id: ScmIdentity) => (
              <li
                key={`${id.provider}/${id.username}`}
                className="flex items-center justify-between px-4 py-3 text-sm"
              >
                <span className="flex items-center gap-2">
                  <span className="rounded bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600 dark:bg-slate-700 dark:text-slate-300">
                    {id.provider}
                  </span>
                  <span className="text-gray-800 dark:text-gray-200">{id.username}</span>
                  {id.verified && <VerifiedBadge />}
                  {id.source === 'config' && <LockedBadge source="config" />}
                </span>
                {isAdmin && id.source !== 'config' && (
                  <button
                    onClick={() => handleRemoveScm(id.provider, id.username)}
                    disabled={deletingScm === `${id.provider}/${id.username}`}
                    className="text-red-400 text-xs hover:text-red-600 disabled:opacity-40 dark:text-red-500 dark:hover:text-red-300"
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
        <h3 className="text-sm font-semibold text-gray-700 dark:text-gray-300">Push Summary</h3>
        <div className="flex flex-wrap gap-3">
          {Object.entries(user.pushCounts).length === 0 ? (
            <p className="text-sm text-gray-400 italic dark:text-gray-500">
              No push activity recorded.
            </p>
          ) : (
            Object.entries(user.pushCounts).map(([status, count]) => (
              <div key={status} className="flex items-center gap-2">
                <StatusBadge status={status} />
                <span className="text-sm font-semibold text-gray-700 dark:text-gray-300">
                  {String(count)}
                </span>
              </div>
            ))
          )}
        </div>
      </section>

      {actionError && <p className="text-xs text-red-500 dark:text-red-400">{actionError}</p>}

      {isAdmin && (
        <section className="border-t border-gray-100 pt-4 dark:border-slate-700">
          <h3 className="text-sm font-semibold text-gray-700 mb-2 dark:text-gray-300">
            Account Actions
          </h3>
          <div className="flex gap-2">
            {isLocalAuth && (
              <button
                onClick={() => setShowResetPw(true)}
                className="px-3 py-1.5 rounded border border-gray-200 text-xs text-gray-600 hover:bg-gray-50 dark:border-slate-600 dark:text-gray-400 dark:hover:bg-gray-700"
              >
                Reset Password
              </button>
            )}
            <button
              onClick={handleDelete}
              disabled={deleting}
              className="px-3 py-1.5 rounded border border-red-200 text-xs text-red-600 hover:bg-red-50 disabled:opacity-40 dark:border-red-700 dark:text-red-400 dark:hover:bg-red-900/20"
            >
              {deleting ? 'Deleting…' : 'Delete User & Permissions'}
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

  if (loading)
    return <div className="py-8 text-center text-gray-400 text-sm dark:text-gray-500">Loading…</div>

  if (pushes.length === 0)
    return (
      <p className="text-sm text-gray-400 italic py-8 text-center dark:text-gray-500">
        No push records found for this user.
      </p>
    )

  return (
    <div className="rounded-lg border border-gray-200 bg-white overflow-hidden dark:bg-slate-800 dark:border-slate-700">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-gray-100 bg-gray-50 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide dark:bg-slate-700/50 dark:border-slate-700 dark:text-gray-400">
            <th className="px-4 py-3">Status</th>
            <th className="px-4 py-3">Repository</th>
            <th className="px-4 py-3">Branch</th>
            <th className="px-4 py-3">Time</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-100 dark:divide-gray-700">
          {pushes.map((p) => (
            <tr
              key={p.id}
              className="hover:bg-gray-50 cursor-pointer transition-colors dark:hover:bg-gray-700/50"
              onClick={() => navigate(`/push/${p.id}`)}
            >
              <td className="px-4 py-3">
                <StatusBadge status={p.status} />
              </td>
              <td className="px-4 py-3 text-gray-700 dark:text-gray-300">
                {p.project && p.repoName ? `${p.project}/${p.repoName}` : (p.repoName ?? '—')}
              </td>
              <td className="px-4 py-3 text-gray-500 dark:text-gray-400">{p.branch ?? '—'}</td>
              <td className="px-4 py-3 text-gray-400 dark:text-gray-500">
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
  const [pathType, setPathType] = useState<'LITERAL' | 'GLOB' | 'REGEX'>('GLOB')
  const [grant, setGrant] = useState<RepoPermission['grant']>('PUSH')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [regexError, setRegexError] = useState<string | null>(null)

  const requireReviewPermission = providers.length > 0 && providers[0].requireReviewPermission

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
        if (list.length > 0) setProvider(list[0].id)
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
        value: path.trim(),
        matchType: pathType,
        grant,
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
    <div className={modalClass}>
      <div className={modalPanelClass}>
        <h3 className={modalTitleClass}>Add Permission</h3>
        <form onSubmit={handleSubmit} className="space-y-3">
          <div>
            <label className={labelClass}>Provider</label>
            <select
              required
              value={provider}
              onChange={(e) => setProvider(e.target.value)}
              disabled={providers.length === 0}
              className={`${inputClass} disabled:opacity-50`}
            >
              {providers.length === 0 && <option value="">Loading…</option>}
              {providers.map((p) => (
                <option key={p.name} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className={labelClass}>Path</label>
            <input
              required
              value={path}
              onChange={(e) => handlePathChange(e.target.value)}
              placeholder="/owner/repo"
              className={`w-full rounded border px-3 py-1.5 text-sm font-mono focus:outline-none dark:bg-slate-700 dark:text-gray-200 ${
                regexError
                  ? 'border-amber-400 focus:border-amber-500 dark:border-amber-600'
                  : 'border-gray-300 focus:border-slate-500 dark:border-slate-600'
              }`}
            />
            {regexError && (
              <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">⚠ {regexError}</p>
            )}
          </div>
          <div>
            <label className={labelClass}>Path Type</label>
            <select
              value={pathType}
              onChange={(e) => handlePathTypeChange(e.target.value as typeof pathType)}
              className={inputClass}
            >
              <option value="LITERAL">Literal — exact match (any case)</option>
              <option value="GLOB">Glob — wildcard (* / **)</option>
              <option value="REGEX">Regex — full Java regex</option>
            </select>
          </div>
          <div>
            <label className={labelClass}>Grant</label>
            <select
              value={grant}
              onChange={(e) => setGrant(e.target.value as typeof grant)}
              className={inputClass}
            >
              {requireReviewPermission && <option value="PUSH_AND_REVIEW">Push and review</option>}
              <option value="PUSH">Push only</option>
              {requireReviewPermission && <option value="REVIEW">Review only</option>}
              <option value="SELF_CERTIFY">Self-certify</option>
            </select>
          </div>
          {error && <p className="text-xs text-red-500 dark:text-red-400">{error}</p>}
          <div className="flex justify-end gap-2">
            <button type="button" onClick={onClose} className={cancelBtnClass}>
              Cancel
            </button>
            <button type="submit" disabled={submitting} className={submitBtnClass}>
              {submitting ? 'Adding…' : 'Add'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

function TestPermissionModal({ username, onClose }: { username: string; onClose: () => void }) {
  const [providers, setProviders] = useState<Provider[]>([])
  const [provider, setProvider] = useState('')
  const [path, setPath] = useState('')
  const [grant, setGrant] = useState<RepoPermission['grant']>('PUSH')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<PermissionTestResponse | null>(null)

  useEffect(() => {
    fetchProviders()
      .then((list: Provider[]) => {
        setProviders(list)
        if (list.length > 0) setProvider(list[0].id)
      })
      .catch(console.error)
  }, [])

  async function handleTest() {
    if (!provider || !path.trim()) {
      setError('Provider and path are required')
      return
    }
    setSubmitting(true)
    setError(null)
    setResult(null)
    try {
      const res = await testUserPermission(username, { provider, path: path.trim(), grant })
      setResult(res)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Permission test failed')
    } finally {
      setSubmitting(false)
    }
  }

  function handleReset() {
    setPath('')
    setGrant('PUSH')
    setError(null)
    setResult(null)
  }

  return (
    <div className={modalClass}>
      <div className={modalPanelClass}>
        <h3 className={modalTitleClass}>Test Permission</h3>
        <p className="text-xs text-gray-400 mb-3 dark:text-gray-500">
          Read-only evaluation of whether <span className="font-mono">{username}</span> has this
          grant, and whether it comes from a direct permission or an inherited group rule.
        </p>
        <div className="space-y-3">
          <div>
            <label className={labelClass}>Provider</label>
            <select
              value={provider}
              onChange={(e) => setProvider(e.target.value)}
              disabled={providers.length === 0}
              className={`${inputClass} disabled:opacity-50`}
            >
              {providers.length === 0 && <option value="">Loading…</option>}
              {providers.map((p) => (
                <option key={p.name} value={p.id}>
                  {p.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className={labelClass}>Path</label>
            <input
              value={path}
              onChange={(e) => setPath(e.target.value)}
              placeholder="/owner/repo"
              className={`${inputClass} font-mono`}
            />
          </div>
          <div>
            <label className={labelClass}>Grant</label>
            <select
              value={grant}
              onChange={(e) => setGrant(e.target.value as typeof grant)}
              className={inputClass}
            >
              <option value="PUSH">Push</option>
              <option value="REVIEW">Review</option>
            </select>
          </div>
        </div>

        {error && <p className="mt-3 text-xs text-red-500 dark:text-red-400">{error}</p>}

        {result && (
          <div
            className={`mt-3 rounded border px-3 py-2 text-sm ${
              result.allowed
                ? 'border-green-300 bg-green-50 text-green-800 dark:border-green-700 dark:bg-green-900/30 dark:text-green-300'
                : 'border-red-300 bg-red-50 text-red-800 dark:border-red-700 dark:bg-red-900/30 dark:text-red-300'
            }`}
          >
            <div className="font-medium">{result.allowed ? 'ALLOWED' : 'DENIED'}</div>
            {result.allowed && result.source === 'DIRECT' && (
              <div className="mt-1 text-xs opacity-80">
                Granted by direct permission {result.entryId?.slice(0, 8)}
              </div>
            )}
            {result.allowed && result.source === 'GROUP' && (
              <div className="mt-1 text-xs opacity-80">
                Granted via group membership: {result.groupName}
              </div>
            )}
            {!result.allowed && (
              <div className="mt-1 text-xs opacity-80">
                No direct or group permission grants this user access.
              </div>
            )}
          </div>
        )}

        <div className="flex justify-end gap-2 mt-4">
          <button type="button" onClick={onClose} className={cancelBtnClass}>
            Close
          </button>
          <button type="button" onClick={handleReset} className={cancelBtnClass}>
            Reset
          </button>
          <button onClick={handleTest} disabled={submitting} className={submitBtnClass}>
            {submitting ? 'Testing…' : 'Run test'}
          </button>
        </div>
      </div>
    </div>
  )
}

function PermissionsTab({ username, isAdmin }: { username: string; isAdmin: boolean }) {
  const [permissions, setPermissions] = useState<RepoPermission[]>([])
  const [groups, setGroups] = useState<UserGroupView[]>([])
  const [loading, setLoading] = useState(true)
  const [showAdd, setShowAdd] = useState(false)
  const [showTest, setShowTest] = useState(false)
  const [deletingId, setDeletingId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const loadPermissions = useCallback(() => {
    setLoading(true)
    Promise.all([fetchUserPermissions(username), fetchUserGroups(username).catch(() => [])])
      .then(([perms, grps]) => {
        setPermissions(perms)
        setGroups(grps)
      })
      .catch(console.error)
      .finally(() => setLoading(false))
  }, [username])

  useEffect(() => {
    void Promise.resolve().then(() => loadPermissions())
  }, [loadPermissions])

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

  if (loading)
    return <div className="py-8 text-center text-gray-400 text-sm dark:text-gray-500">Loading…</div>

  return (
    <div className="space-y-4">
      {showAdd && (
        <AddPermissionModal
          username={username}
          onClose={() => setShowAdd(false)}
          onAdded={loadPermissions}
        />
      )}
      {showTest && <TestPermissionModal username={username} onClose={() => setShowTest(false)} />}

      {permissions.length === 0 ? (
        <p className="text-sm text-gray-400 italic py-4 dark:text-gray-500">
          No permissions configured for this user.
        </p>
      ) : (
        <div className="rounded-lg border border-gray-200 bg-white overflow-hidden dark:bg-slate-800 dark:border-slate-700">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-100 bg-gray-50 text-left text-xs font-semibold text-gray-500 uppercase tracking-wide dark:bg-slate-700/50 dark:border-slate-700 dark:text-gray-400">
                <th className="px-4 py-3">Provider</th>
                <th className="px-4 py-3">Type</th>
                <th className="px-4 py-3">Path</th>
                <th className="px-4 py-3">Grant</th>
                <th className="px-4 py-3">Source</th>
                {isAdmin && <th className="px-4 py-3" />}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100 dark:divide-gray-700">
              {permissions.map((p) => (
                <tr
                  key={p.id}
                  className="hover:bg-gray-50 transition-colors dark:hover:bg-gray-700/50"
                >
                  <td className="px-4 py-3">
                    <span className="rounded bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600 dark:bg-slate-700 dark:text-slate-300">
                      {p.provider}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <PathTypeBadge matchType={p.matchType} />
                  </td>
                  <td className="px-4 py-3 font-mono text-gray-700 text-xs dark:text-gray-300">
                    {p.value}
                  </td>
                  <td className="px-4 py-3">
                    <OperationsBadge operations={p.grant} />
                  </td>
                  <td className="px-4 py-3">
                    {p.source === 'CONFIG' ? (
                      <LockedBadge source="config" />
                    ) : (
                      <span className="text-xs text-gray-400 dark:text-gray-500">local</span>
                    )}
                  </td>
                  {isAdmin && (
                    <td className="px-4 py-3 text-right">
                      {p.source !== 'CONFIG' && (
                        <button
                          onClick={() => handleDelete(p.id)}
                          disabled={deletingId === p.id}
                          className="text-red-400 text-xs hover:text-red-600 disabled:opacity-40 dark:text-red-500 dark:hover:text-red-300"
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

      {actionError && <p className="text-xs text-red-500 dark:text-red-400">{actionError}</p>}

      {isAdmin && (
        <div className="flex gap-2">
          <button
            onClick={() => setShowAdd(true)}
            className="px-3 py-1.5 rounded bg-slate-700 text-white text-xs hover:bg-slate-800"
          >
            + Add Permission
          </button>
          <button
            onClick={() => setShowTest(true)}
            className="px-3 py-1.5 rounded border border-gray-300 text-xs text-gray-600 hover:bg-gray-50 dark:border-slate-600 dark:text-gray-300 dark:hover:bg-gray-700"
          >
            Test Permission
          </button>
        </div>
      )}

      {/* group memberships */}
      <div className="pt-2 space-y-2">
        <p className="text-xs font-semibold text-gray-400 uppercase tracking-wide dark:text-gray-500">
          Group memberships
        </p>
        {groups.length === 0 ? (
          <p className="text-sm text-gray-400 italic dark:text-gray-500">
            Not a member of any groups.
          </p>
        ) : (
          <div className="space-y-3">
            {groups.map((g) => (
              <div
                key={g.id}
                className="rounded-lg border border-gray-200 bg-white overflow-hidden dark:bg-slate-800 dark:border-slate-700"
              >
                <div className="px-4 py-2 bg-gray-50 dark:bg-slate-700/50 flex items-center gap-2 border-b border-gray-100 dark:border-slate-700">
                  <span className="text-sm font-medium text-gray-700 dark:text-gray-200">
                    {g.name}
                  </span>
                  {g.source === 'CONFIG' && (
                    <span className="text-xs bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400 rounded px-1.5 py-0.5">
                      config
                    </span>
                  )}
                  {g.description && (
                    <span className="text-xs text-gray-400 dark:text-gray-500">
                      {g.description}
                    </span>
                  )}
                </div>
                {g.rules.length === 0 ? (
                  <p className="px-4 py-2 text-xs text-gray-400 italic dark:text-gray-500">
                    No rules defined.
                  </p>
                ) : (
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="text-left text-gray-400 dark:text-gray-500 border-b border-gray-100 dark:border-slate-700">
                        <th className="px-4 py-2">Provider</th>
                        <th className="px-4 py-2">Type</th>
                        <th className="px-4 py-2">Path</th>
                        <th className="px-4 py-2">Grant</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-50 dark:divide-slate-700/50">
                      {g.rules.map((r) => (
                        <tr key={r.id} className="text-gray-600 dark:text-gray-300">
                          <td className="px-4 py-2">
                            <span className="rounded bg-slate-100 px-1.5 py-0.5 text-xs font-medium text-slate-600 dark:bg-slate-700 dark:text-slate-300">
                              {r.provider}
                            </span>
                          </td>
                          <td className="px-4 py-2">
                            <PathTypeBadge matchType={r.matchType} />
                          </td>
                          <td className="px-4 py-2 font-mono">{r.value}</td>
                          <td className="px-4 py-2">
                            <OperationsBadge operations={r.grant} />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      <div className="pt-2 space-y-2">
        <p className="text-xs font-semibold text-gray-400 uppercase tracking-wide dark:text-gray-500">
          Coming soon
        </p>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div className="rounded-lg border border-dashed border-gray-200 bg-gray-50 px-4 py-3 space-y-1 opacity-70 dark:bg-slate-800 dark:border-slate-700">
            <p className="text-sm font-medium text-gray-500 dark:text-gray-400">
              Time-bound permissions
            </p>
            <p className="text-xs text-gray-400 dark:text-gray-500">
              Grant access valid only within a date range, e.g. valid 2025-01-01 → 2025-12-31.
            </p>
          </div>
          <div className="rounded-lg border border-dashed border-gray-200 bg-gray-50 px-4 py-3 space-y-1 opacity-70 dark:bg-slate-800 dark:border-slate-700">
            <p className="text-sm font-medium text-gray-500 dark:text-gray-400">
              Just-in-time permissions
            </p>
            <p className="text-xs text-gray-400 dark:text-gray-500">
              Self-service, short-lived access requests with automatic expiry and audit trail.
            </p>
          </div>
        </div>
      </div>
    </div>
  )
}

export function UserDetail({ authProvider, currentUser }: UserDetailProps) {
  const { username } = useParams<{ username: string }>()
  const navigate = useNavigate()
  const [user, setUser] = useState<UserDetailType | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('overview')

  const isLocalAuth = authProvider === 'local'
  const isAdmin = currentUser?.authorities?.includes('ROLE_ADMIN') ?? false

  const loadUser = useCallback(() => {
    if (!username) return
    fetchUser(username)
      .then(setUser)
      .catch(() => setError('User not found'))
      .finally(() => setLoading(false))
  }, [username])

  useEffect(() => {
    loadUser()
  }, [loadUser])

  if (loading)
    return (
      <div className="max-w-3xl mx-auto px-4 py-16 text-center text-gray-400 dark:text-gray-500">
        Loading…
      </div>
    )
  if (error || !user)
    return (
      <div className="max-w-3xl mx-auto px-4 py-16 text-center text-red-500 dark:text-red-400">
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
          className="text-xs text-gray-400 hover:text-gray-600 transition-colors dark:text-gray-500 dark:hover:text-gray-300"
        >
          ← Users
        </button>
        <span className="text-gray-300 dark:text-gray-600">/</span>
        <h2 className="text-lg font-semibold text-gray-800 dark:text-gray-200">{user.username}</h2>
        {!isLocalAuth && (
          <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-500 dark:bg-slate-700 dark:text-slate-400">
            {authProvider}
          </span>
        )}
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-gray-200 dark:border-slate-700">
        {tabs.map((t) => (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            className={
              'px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px ' +
              (tab === t.id
                ? 'border-slate-700 text-slate-800 dark:border-slate-400 dark:text-slate-200'
                : 'border-transparent text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300')
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
          isAdmin={isAdmin}
          onRefresh={loadUser}
          onDeleted={() => navigate('/users')}
        />
      )}
      {tab === 'pushes' && <PushesTab username={user.username} />}
      {tab === 'permissions' && <PermissionsTab username={user.username} isAdmin={isAdmin} />}
    </div>
  )
}
