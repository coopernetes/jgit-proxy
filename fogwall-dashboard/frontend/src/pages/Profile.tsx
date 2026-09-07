import { useEffect, useState } from 'react'
import {
  addEmail,
  addScmIdentity,
  addSshKey,
  fetchConfig,
  fetchMe,
  fetchMyPermissions,
  fetchMySshKeys,
  fetchProviders,
  removeEmail,
  removeScmIdentity,
  removeSshKey,
  unlinkScmOAuth,
} from '../api'
import type { UserGroupView } from '../api'
import { OperationsBadge, PathTypeBadge } from '../components/PermissionBadges'
import type {
  CurrentUser,
  EmailEntry,
  RepoPermission,
  ScmIdentity,
  ScmOAuthProviderInfo,
  SshKeyEntry,
} from '../types'
import githubMarkLight from '../assets/logos/github-mark-light.svg'
import githubMarkDark from '../assets/logos/github-mark-dark.svg'
import gitlabLogo from '../assets/logos/gitlab-logo.png'
import codebergIconBlue from '../assets/logos/codeberg-icon-blue.svg'
import codebergIconWhite from '../assets/logos/codeberg-icon-white.svg'
import giteaLogo from '../assets/logos/gitea-logo.svg'

/** codeberg.org and gitea.com are the two `forgejo`-type hosts with their own dedicated logo. */
function isCodeberg(hostname: string): boolean {
  return hostname.toLowerCase() === 'codeberg.org'
}
function isGiteaCom(hostname: string): boolean {
  return hostname.toLowerCase() === 'gitea.com'
}

/**
 * GitHub's Invertocat mark, used per GitHub's logo usage guidelines (brand.github.com/foundations/logo#legal) as a
 * permitted, unmodified social/integration button; GitLab's tanuki mark, used unmodified to identify this OAuth
 * integration; Codeberg's icon mark (CC0-licensed, per codeberg.org/Codeberg/Design), used per its own usage
 * guidelines (white variant on dark backgrounds); and Gitea's mascot mark (CC BY-SA 4.0, per gitea.com/gitea/design,
 * now archived), used unmodified — see the Legal page for the full trademark/license notices for all four. Any other
 * self-hosted Forgejo/Gitea instance has no fixed brand to show, so renders no logo.
 */
function ProviderLogo({
  type,
  hostname,
  onDark,
}: {
  type: string
  hostname: string
  onDark?: boolean
}) {
  if (type === 'github') {
    // onDark: the mark sits on an always-dark button background regardless of page theme, so always use the
    // white variant there. Otherwise (e.g. next to the badge on the page's own background) switch with the theme.
    if (onDark) return <img src={githubMarkDark} alt="" className="h-4 w-4" />
    return (
      <>
        <img src={githubMarkLight} alt="" className="h-4 w-4 dark:hidden" />
        <img src={githubMarkDark} alt="" className="hidden h-4 w-4 dark:inline" />
      </>
    )
  }
  if (type === 'gitlab') return <img src={gitlabLogo} alt="" className="h-4 w-4" />
  if (type === 'forgejo' && isCodeberg(hostname)) {
    if (onDark) return <img src={codebergIconWhite} alt="" className="h-4 w-4" />
    return (
      <>
        <img src={codebergIconBlue} alt="" className="h-4 w-4 dark:hidden" />
        <img src={codebergIconWhite} alt="" className="hidden h-4 w-4 dark:inline" />
      </>
    )
  }
  // The mascot mark has no separate light/dark variant — its own green/white artwork reads fine on both.
  if (type === 'forgejo' && isGiteaCom(hostname))
    return <img src={giteaLogo} alt="" className="h-4 w-4" />
  return null
}

/**
 * Named after the host being linked, not the provider brand — a github/gitlab-type provider isn't always
 * github.com/gitlab.com (GHE data residency, self-managed GHES, self-hosted GitLab), so the hostname is the only
 * thing that actually distinguishes which account a developer is about to link.
 */
function providerLinkLabel(hostname: string): string {
  return hostname ? `Link with ${hostname}` : 'Link via OAuth'
}

function VerifiedBadge({ source }: { source?: string }) {
  return (
    <span
      className="inline-flex items-center gap-1 rounded-full bg-green-50 px-2 py-0.5 text-xs font-medium text-green-700 dark:bg-green-900/30 dark:text-green-300"
      title="Confirmed via OAuth account linking"
    >
      verified{source ? ` (${source})` : ''}
    </span>
  )
}

function LockedBadge({ source }: { source: string }) {
  const title =
    source === 'config'
      ? 'Defined in server configuration and cannot be removed'
      : `Managed by your ${source.toUpperCase()} identity provider and cannot be removed`
  return (
    <span
      className="inline-flex items-center gap-1 rounded-full bg-blue-50 px-2 py-0.5 text-xs font-medium text-blue-600 dark:bg-blue-900/30 dark:text-blue-300"
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

  const [tab, setTab] = useState<'emails' | 'identities' | 'sshkeys' | 'permissions'>('emails')
  const [permissions, setPermissions] = useState<RepoPermission[]>([])
  const [groups, setGroups] = useState<UserGroupView[]>([])

  const [sshKeys, setSshKeys] = useState<SshKeyEntry[]>([])
  const [newSshKey, setNewSshKey] = useState('')
  const [newSshLabel, setNewSshLabel] = useState('')
  const [sshLabelTouched, setSshLabelTouched] = useState(false)
  const [sshError, setSshError] = useState<string | null>(null)
  const [sshBusy, setSshBusy] = useState(false)

  const [newEmail, setNewEmail] = useState('')
  const [emailError, setEmailError] = useState<string | null>(null)
  const [emailBusy, setEmailBusy] = useState(false)

  const [providers, setProviders] = useState<{ name: string; id: string; host: string }[]>([])
  const [newProvider, setNewProvider] = useState('')
  const [newScmUsername, setNewScmUsername] = useState('')
  const [identityError, setIdentityError] = useState<string | null>(null)
  const [identityBusy, setIdentityBusy] = useState(false)

  const [scmOAuthProviders, setScmOAuthProviders] = useState<ScmOAuthProviderInfo[]>([])
  const [scmOAuthLinkAvailable, setScmOAuthLinkAvailable] = useState(false)
  const [scmIdentityMode, setScmIdentityMode] = useState<string>('permissive')

  // The OAuth callback redirects back here with a query param on both success and failure — read it once as
  // initial state (not in an effect — this derives from the URL present at mount, it isn't subscribing to
  // anything), then strip it from the URL in an effect so a page refresh doesn't re-show a stale notice.
  const [oauthResult] = useState<{ notice: string | null; error: string | null }>(() => {
    const params = new URLSearchParams(window.location.search)
    const linked = params.get('scmOAuthLinked')
    const err = params.get('scmOAuthError')
    return {
      notice: linked ? `Linked your ${linked} account via OAuth.` : null,
      error: err ? `SCM OAuth linking failed: ${err.replaceAll('_', ' ')}` : null,
    }
  })
  const { notice: oauthNotice, error: oauthError } = oauthResult

  useEffect(() => {
    if (oauthNotice || oauthError) {
      window.history.replaceState(null, '', window.location.pathname)
    }
  }, [oauthNotice, oauthError])

  useEffect(() => {
    fetchConfig()
      .then((c) => {
        setScmOAuthProviders(c.scmOAuthProviders ?? [])
        setScmOAuthLinkAvailable(c.scmOAuthLinkAvailable ?? false)
        setScmIdentityMode(c.scmIdentityMode ?? 'permissive')
      })
      .catch(() => {})
  }, [])

  useEffect(() => {
    fetchMe()
      .then((data) => setProfile(data))
      .catch(() => setError('Failed to load profile'))
      .finally(() => setLoading(false))
    fetchMyPermissions()
      .then((data) => {
        setPermissions(data.direct)
        setGroups(data.groups)
      })
      .catch(() => {})
    fetchProviders()
      .then((list: { name: string; id: string; host: string }[]) => {
        setProviders(list)
        if (list.length > 0) setNewProvider(list[0].id)
      })
      .catch(() => {})
    fetchMySshKeys()
      .then((keys: SshKeyEntry[]) => setSshKeys(keys))
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

  async function handleUnlinkOAuth(provider: string) {
    setIdentityError(null)
    try {
      await unlinkScmOAuth(provider)
      setProfile(
        (p) =>
          p && { ...p, scmIdentities: p.scmIdentities.filter((id) => id.provider !== provider) },
      )
      // Refetch rather than filter: a key several providers vouch for survives one unlink, and the label
      // ("github, gitlab") does not equal the provider being removed. The server decides what is left.
      await fetchMySshKeys()
        .then((keys: SshKeyEntry[]) => setSshKeys(keys))
        .catch(() => {})
    } catch (err: unknown) {
      setIdentityError(err instanceof Error ? err.message : 'Failed to unlink OAuth account')
    }
  }

  async function handleAddSshKey(e: React.FormEvent) {
    e.preventDefault()
    if (!newSshKey.trim()) return
    setSshBusy(true)
    setSshError(null)
    try {
      const entry = await addSshKey(newSshKey.trim(), newSshLabel.trim())
      setSshKeys((prev) => [...prev, entry])
      setNewSshKey('')
      setNewSshLabel('')
      setSshLabelTouched(false)
    } catch (err: unknown) {
      setSshError(err instanceof Error ? err.message : 'Failed to add SSH key')
    } finally {
      setSshBusy(false)
    }
  }

  async function handleRemoveSshKey(key: SshKeyEntry) {
    setSshError(null)
    try {
      await removeSshKey(key.id)
      setSshKeys((prev) => prev.filter((k) => k.id !== key.id))
    } catch (err: unknown) {
      setSshError(err instanceof Error ? err.message : 'Failed to remove SSH key')
    }
  }

  if (loading)
    return (
      <div className="max-w-2xl mx-auto px-4 py-16 text-center text-gray-400 dark:text-gray-500">
        Loading…
      </div>
    )
  if (error)
    return (
      <div className="max-w-2xl mx-auto px-4 py-16 text-center text-red-500 dark:text-red-400">
        {error}
      </div>
    )
  if (!profile) return null

  return (
    <div className="max-w-2xl mx-auto px-4 py-6 space-y-6">
      <div>
        <h2 className="text-lg font-semibold text-gray-800 dark:text-gray-200">Profile</h2>
        <p className="text-sm text-gray-500 mt-1 dark:text-gray-400">
          Signed in as{' '}
          <span className="font-medium text-gray-700 dark:text-gray-300">{profile.username}</span>
        </p>
        <div className="flex flex-wrap gap-1 mt-2">
          {profile.authorities
            .filter((a: string) => a.startsWith('ROLE_'))
            .map((a: string) => {
              const label = a.replace('ROLE_', '')
              const colour =
                label === 'ADMIN'
                  ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300'
                  : label === 'SELF_CERTIFY'
                    ? 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300'
                    : 'bg-gray-100 text-gray-600 dark:bg-slate-700 dark:text-gray-300'
              const isSelfCertify = label === 'SELF_CERTIFY'
              return (
                <span key={a} className="relative group inline-flex">
                  <span
                    className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${colour}`}
                  >
                    {label}
                  </span>
                  {isSelfCertify && (
                    <span className="pointer-events-none absolute bottom-full left-1/2 -translate-x-1/2 mb-1.5 w-56 rounded bg-gray-800 px-2 py-1.5 text-xs text-white opacity-0 group-hover:opacity-100 transition-opacity z-10">
                      This role allows you to self-approve your own pushes, but an admin must also
                      grant the Self-certify permission on each individual repository.
                      <span className="absolute top-full left-1/2 -translate-x-1/2 border-4 border-transparent border-t-gray-800" />
                    </span>
                  )}
                </span>
              )
            })}
        </div>
      </div>

      {/* Connected accounts */}
      {scmOAuthLinkAvailable && scmOAuthProviders.length > 0 && (
        <div className="space-y-2 rounded-lg border border-gray-200 bg-white p-4 dark:bg-slate-800 dark:border-slate-700">
          <h2 className="text-sm font-semibold text-gray-700 dark:text-gray-300">
            Connected accounts
          </h2>
          {oauthNotice && (
            <p className="text-sm text-green-700 dark:text-green-400">{oauthNotice}</p>
          )}
          {oauthError && <p className="text-sm text-red-600 dark:text-red-400">{oauthError}</p>}
          <ul className="divide-y divide-gray-100 dark:divide-gray-700">
            {scmOAuthProviders.map((p) => {
              const linked = profile.scmIdentities.find((id) => id.provider === p.id && id.verified)
              return (
                <li key={p.id} className="flex items-center justify-between py-2 text-sm">
                  <span className="flex items-center gap-2">
                    <ProviderLogo type={p.type} hostname={p.hostname} />
                    <span className="rounded bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600 dark:bg-slate-700 dark:text-slate-300">
                      {p.id}
                    </span>
                    {linked ? (
                      <span className="text-gray-800 dark:text-gray-200">{linked.username}</span>
                    ) : (
                      <span className="text-gray-400 italic dark:text-gray-500">Not linked</span>
                    )}
                  </span>
                  {linked ? (
                    <button
                      onClick={() => handleUnlinkOAuth(p.id)}
                      className="text-gray-400 hover:text-red-500 transition-colors text-xs dark:text-gray-500 dark:hover:text-red-400"
                      title="Unlink OAuth account"
                    >
                      Unlink
                    </button>
                  ) : (
                    <a
                      href={`/api/scm-oauth/${encodeURIComponent(p.id)}/link`}
                      className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded bg-slate-700 text-white text-xs hover:bg-slate-600 transition-colors"
                    >
                      <ProviderLogo type={p.type} hostname={p.hostname} onDark />
                      {providerLinkLabel(p.hostname)}
                    </a>
                  )}
                </li>
              )
            })}
          </ul>
        </div>
      )}

      {/* Tabs */}
      <div className="flex gap-1 border-b border-gray-200 dark:border-slate-700">
        {(['emails', 'identities', 'sshkeys', 'permissions'] as const).map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={
              'px-4 py-2 text-sm font-medium transition-colors border-b-2 -mb-px ' +
              (tab === t
                ? 'border-slate-700 text-slate-800 dark:border-slate-400 dark:text-slate-200'
                : 'border-transparent text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300')
            }
          >
            {t === 'emails'
              ? 'Email Addresses'
              : t === 'identities'
                ? 'SCM Identities'
                : t === 'sshkeys'
                  ? 'SSH Keys'
                  : 'Permissions'}
          </button>
        ))}
      </div>

      {/* Emails tab */}
      {tab === 'emails' && (
        <div className="space-y-4">
          <p className="text-sm text-gray-500 dark:text-gray-400">
            Email addresses linked to your commits. Commit attribution hooks use these to confirm
            your authorship on push.
          </p>

          {profile.emails.length === 0 ? (
            <p className="text-sm text-gray-400 italic dark:text-gray-500">
              No email addresses registered.
            </p>
          ) : (
            <ul className="divide-y divide-gray-100 rounded-lg border border-gray-200 bg-white dark:bg-slate-800 dark:border-slate-700 dark:divide-gray-700">
              {profile.emails.map((entry) => (
                <li
                  key={entry.email}
                  className="flex items-center justify-between px-4 py-3 text-sm"
                >
                  <span className="flex items-center gap-2">
                    <span className="text-gray-800 dark:text-gray-200">{entry.email}</span>
                    {entry.verified ? (
                      <VerifiedBadge source={entry.source} />
                    ) : (
                      entry.locked && <LockedBadge source={entry.source} />
                    )}
                  </span>
                  {!entry.locked && (
                    <button
                      onClick={() => handleRemoveEmail(entry)}
                      className="text-gray-400 hover:text-red-500 transition-colors text-xs dark:text-gray-500 dark:hover:text-red-400"
                      title="Remove"
                    >
                      Remove
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}

          {emailError && <p className="text-sm text-red-600 dark:text-red-400">{emailError}</p>}

          <form onSubmit={handleAddEmail} className="flex gap-2">
            <input
              type="email"
              value={newEmail}
              onChange={(e) => setNewEmail(e.target.value)}
              placeholder="you@example.com"
              className="flex-1 rounded border border-gray-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none dark:bg-slate-700 dark:border-slate-600 dark:text-gray-200 dark:placeholder-gray-400"
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

      {/* SSH Keys tab */}
      {tab === 'sshkeys' && (
        <div className="space-y-4">
          <p className="text-sm text-gray-500 dark:text-gray-400">
            SSH public keys used to authenticate git push over SSH. Register the same key you use
            with <code className="text-xs bg-gray-100 dark:bg-slate-700 px-1 rounded">ssh -A</code>.
            Your key fingerprint is looked up on push to identify you.
          </p>

          {sshKeys.length === 0 ? (
            <p className="text-sm text-gray-400 italic dark:text-gray-500">
              No SSH keys registered.
            </p>
          ) : (
            <ul className="divide-y divide-gray-100 rounded-lg border border-gray-200 bg-white dark:bg-slate-800 dark:border-slate-700 dark:divide-gray-700">
              {sshKeys.map((key) => (
                <li key={key.id} className="px-4 py-3 text-sm space-y-1">
                  <div className="flex items-center justify-between">
                    <span className="font-medium text-gray-800 dark:text-gray-200 flex items-center gap-2">
                      {key.label || (
                        <span className="italic text-gray-400 dark:text-gray-500">unlabelled</span>
                      )}
                      {key.locked &&
                        (key.source === 'config' ? (
                          <LockedBadge source={key.source} />
                        ) : (
                          <VerifiedBadge source={key.source} />
                        ))}
                    </span>
                    {!key.locked && (
                      <button
                        onClick={() => handleRemoveSshKey(key)}
                        className="text-gray-400 hover:text-red-500 transition-colors text-xs dark:text-gray-500 dark:hover:text-red-400"
                      >
                        Remove
                      </button>
                    )}
                  </div>
                  <p className="font-mono text-xs text-gray-500 dark:text-gray-400 break-all">
                    {key.fingerprint}
                  </p>
                  <p className="text-xs text-gray-400 dark:text-gray-500">
                    {key.locked
                      ? key.source === 'config'
                        ? 'Defined in server configuration'
                        : `Imported from your ${key.source} account`
                      : `Added ${new Date(key.createdAt).toLocaleDateString()}`}
                  </p>
                </li>
              ))}
            </ul>
          )}

          {sshError && <p className="text-sm text-red-600 dark:text-red-400">{sshError}</p>}

          <form onSubmit={handleAddSshKey} className="space-y-2">
            <textarea
              value={newSshKey}
              onChange={(e) => {
                const val = e.target.value
                setNewSshKey(val)
                if (!sshLabelTouched) {
                  const comment = val.trim().split(/\s+/)[2] ?? ''
                  setNewSshLabel(comment)
                }
              }}
              placeholder="ssh-ed25519 AAAA... or ssh-rsa AAAA..."
              rows={3}
              className="w-full rounded border border-gray-300 px-3 py-2 text-sm font-mono focus:border-slate-500 focus:outline-none dark:bg-slate-700 dark:border-slate-600 dark:text-gray-200 dark:placeholder-gray-400"
            />
            <div className="flex gap-2">
              <input
                type="text"
                value={newSshLabel}
                onChange={(e) => {
                  setNewSshLabel(e.target.value)
                  setSshLabelTouched(true)
                }}
                placeholder="Label (optional)"
                className="flex-1 rounded border border-gray-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none dark:bg-slate-700 dark:border-slate-600 dark:text-gray-200 dark:placeholder-gray-400"
              />
              <button
                type="submit"
                disabled={sshBusy || !newSshKey.trim()}
                className="px-4 py-2 rounded bg-slate-700 text-white text-sm hover:bg-slate-600 disabled:opacity-50 transition-colors"
              >
                Add
              </button>
            </div>
          </form>
        </div>
      )}

      {/* Permissions tab */}
      {tab === 'permissions' && (
        <div className="space-y-4">
          <p className="text-sm text-gray-500 dark:text-gray-400">
            Repository access permissions granted to your account.
          </p>
          {loading ? (
            <p className="text-sm text-gray-400 dark:text-gray-500">Loading…</p>
          ) : permissions.length === 0 ? (
            <p className="text-sm text-gray-400 italic dark:text-gray-500">
              No permissions configured.
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
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {groups.length > 0 && (
            <div className="space-y-3 mt-6">
              <h3 className="text-sm font-semibold text-gray-700 dark:text-gray-300">
                Group memberships
              </h3>
              {groups.map((g) => (
                <div
                  key={g.id}
                  className="rounded-lg border border-gray-200 bg-white dark:bg-slate-800 dark:border-slate-700 overflow-hidden"
                >
                  <div className="px-4 py-3 flex items-center gap-2 border-b border-gray-100 dark:border-slate-700">
                    <span className="text-sm font-medium text-gray-800 dark:text-gray-100">
                      {g.name}
                    </span>
                    <span className="text-xs bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400 rounded px-1.5 py-0.5">
                      {g.source.toLowerCase()}
                    </span>
                    {g.description && (
                      <span className="text-xs text-gray-500 dark:text-gray-400">
                        — {g.description}
                      </span>
                    )}
                  </div>
                  {g.rules.length === 0 ? (
                    <p className="px-4 py-3 text-xs text-gray-400 italic">
                      No rules in this group.
                    </p>
                  ) : (
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="text-left text-xs text-gray-500 dark:text-gray-400 bg-gray-50 dark:bg-slate-700/50">
                          <th className="px-4 py-2">Provider</th>
                          <th className="px-4 py-2">Type</th>
                          <th className="px-4 py-2">Path</th>
                          <th className="px-4 py-2">Grant</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-gray-100 dark:divide-slate-700">
                        {g.rules.map((r) => (
                          <tr key={r.id} className="text-gray-700 dark:text-gray-300">
                            <td className="px-4 py-2">
                              <span className="rounded bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600 dark:bg-slate-700 dark:text-slate-300">
                                {r.provider}
                              </span>
                            </td>
                            <td className="px-4 py-2">
                              <PathTypeBadge matchType={r.matchType} />
                            </td>
                            <td className="px-4 py-2 font-mono text-xs">{r.value}</td>
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
      )}

      {/* SCM Identities tab */}
      {tab === 'identities' && (
        <div className="space-y-4">
          <p className="text-sm text-gray-500 dark:text-gray-400">
            Your usernames on upstream SCM providers. Used to verify that your SCM login matches the
            account pushing code through the proxy.
          </p>

          {profile.scmIdentities.length === 0 ? (
            <p className="text-sm text-gray-400 italic dark:text-gray-500">
              No SCM identities registered.
            </p>
          ) : (
            <ul className="divide-y divide-gray-100 rounded-lg border border-gray-200 bg-white dark:bg-slate-800 dark:border-slate-700 dark:divide-gray-700">
              {profile.scmIdentities.map((id) => (
                <li
                  key={`${id.provider}/${id.username}`}
                  className="flex items-center justify-between px-4 py-3 text-sm"
                >
                  <span className="flex items-center gap-2">
                    <span className="rounded bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600 dark:bg-slate-700 dark:text-slate-300">
                      {id.provider}
                    </span>
                    <span className="text-gray-800 dark:text-gray-200">{id.username}</span>
                    {id.verified ? <VerifiedBadge /> : null}
                    {id.source === 'config' && <LockedBadge source="config" />}
                  </span>
                  {!id.verified && id.source !== 'config' && (
                    <button
                      onClick={() => handleRemoveIdentity(id)}
                      className="text-gray-400 hover:text-red-500 transition-colors text-xs dark:text-gray-500 dark:hover:text-red-400"
                      title="Remove"
                    >
                      Remove
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}

          {identityError && (
            <p className="text-sm text-red-600 dark:text-red-400">{identityError}</p>
          )}

          {scmIdentityMode === 'strict' ? (
            <p className="text-xs text-gray-400 italic dark:text-gray-500">
              This deployment requires an OAuth-verified SCM identity — manual entry is disabled.
              Use the "Link via OAuth" button above.
            </p>
          ) : (
            <form onSubmit={handleAddIdentity} className="flex gap-2">
              <select
                value={newProvider}
                onChange={(e) => setNewProvider(e.target.value)}
                className="rounded border border-gray-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none dark:bg-slate-700 dark:border-slate-600 dark:text-gray-200"
                disabled={providers.length === 0}
              >
                {providers.map((p) => (
                  <option key={p.name} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
              <input
                type="text"
                value={newScmUsername}
                onChange={(e) => setNewScmUsername(e.target.value)}
                placeholder="your-username"
                className="flex-1 rounded border border-gray-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none dark:bg-slate-700 dark:border-slate-600 dark:text-gray-200 dark:placeholder-gray-400"
              />
              <button
                type="submit"
                disabled={identityBusy || !newScmUsername.trim()}
                className="px-4 py-2 rounded bg-slate-700 text-white text-sm hover:bg-slate-600 disabled:opacity-50 transition-colors"
              >
                Add
              </button>
            </form>
          )}
        </div>
      )}
    </div>
  )
}
