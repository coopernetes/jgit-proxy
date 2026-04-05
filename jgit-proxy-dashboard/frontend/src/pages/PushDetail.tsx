import { useEffect, useRef, useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { Diff2HtmlUI } from 'diff2html/lib/ui/js/diff2html-ui-slim'
import 'diff2html/bundles/css/diff2html.min.css'
import { approvePush, cancelPush, fetchPush, rejectPush } from '../api'
import { StatusBadge } from '../components/StatusBadge'
import type { CurrentUser, PushRecord, Step } from '../types'

// Steps that are infrastructure/pre-processing, not user-visible validation checks
const NON_VALIDATION_STEPS = new Set([
  'inspection',
  'diff',
  'diff:default-branch',
  'ForceGitClientFilter',
  'ParseGitRequestFilter',
  'EnrichPushCommitsFilter',
  'AllowApprovedPushFilter',
  'PushStoreAuditFilter',
  'AuditLogFilter',
  'ValidationSummaryFilter',
])

const STEP_DISPLAY_NAMES: Record<string, string> = {
  checkAuthorEmails: 'Author & committer email addresses',
  AuthorEmail: 'Author & committer email addresses',
  CheckAuthorEmailsFilter: 'Author & committer email addresses',
  checkCommitMessages: 'Commit message(s)',
  CommitMessage: 'Commit message(s)',
  CheckCommitMessagesFilter: 'Commit message(s)',
  CheckEmptyBranchHook: 'Check empty branch',
  CheckEmptyBranchFilter: 'Check empty branch',
  CheckHiddenCommitsHook: 'Check hidden commits',
  CheckHiddenCommitsFilter: 'Check hidden commits',
  scanDiff: 'Scan diff',
  DiffContent: 'Scan diff',
  ScanDiffFilter: 'Scan diff',
  GpgSignatureFilter: 'GPG signature',
  GpgSignatureHook: 'GPG signature',
  scanSecrets: 'Secret scanning',
  SecretScanningFilter: 'Secret scanning',
  CheckUserPushPermissionFilter: 'Push permissions',
  CheckUserPushPermissionHook: 'Push permissions',
  WhitelistAggregateFilter: 'Repository whitelist',
  RepositoryWhitelistHook: 'Repository whitelist',
}

function stepDisplayName(name: string): string {
  return (
    STEP_DISPLAY_NAMES[name] ??
    name
      .replace(/Filter$|Hook$/, '')
      .replace(/([A-Z])/g, ' $1')
      .trim()
  )
}

function formatTime(ts: string | number | undefined) {
  if (!ts) return ''
  try {
    return new Date(ts).toLocaleString()
  } catch {
    return String(ts)
  }
}

function stripAnsi(str: string) {
  // eslint-disable-next-line no-control-regex
  return (str ?? '').replace(/\x1b\[[0-9;]*m/g, '')
}

/**
 * Build a direct link to the commit on the upstream SCM.
 * GitHub / Gitea / Codeberg: {repo}/commit/{sha}
 * GitLab: {repo}/-/commit/{sha}
 */
function upstreamCommitUrl(upstreamUrl: string, sha: string): string {
  const base = upstreamUrl.replace(/\/$/, '').replace(/\.git$/, '')
  if (/gitlab\./.test(base)) return `${base}/-/commit/${sha}`
  return `${base}/commit/${sha}`
}

// Steps that represent forwarding/post-receive operations
const FORWARDING_STEPS = new Set(['ForwardingPostReceiveHook', 'forward', 'ForwardingHook'])

function PushTimeline({ record }: { record: PushRecord }) {
  type TimelineEvent = {
    icon: string
    label: string
    detail?: string
    link?: string
    time?: string | number
    color: string
  }

  const events: TimelineEvent[] = []

  // 1. Received
  events.push({
    icon: '↓',
    label: 'Push received',
    time: record.timestamp,
    color: 'text-gray-500',
  })

  // 2. Validation ran — summarise as a single event using the first/last step timestamp
  const visibleSteps = (record.steps ?? []).filter((s) => !NON_VALIDATION_STEPS.has(s.stepName))
  if (visibleSteps.length > 0) {
    const failed = visibleSteps.filter((s) => s.status === 'FAIL' || s.status === 'BLOCKED')
    const lastStep = [...visibleSteps].sort((a, b) => a.stepOrder - b.stepOrder).at(-1)
    events.push({
      icon: failed.length > 0 ? '✗' : '✓',
      label:
        failed.length > 0
          ? `Validation failed (${failed.length} issue${failed.length > 1 ? 's' : ''})`
          : `Validation passed (${visibleSteps.length} check${visibleSteps.length > 1 ? 's' : ''})`,
      detail: failed.map((s) => stepDisplayName(s.stepName)).join(', ') || undefined,
      time: lastStep?.timestamp,
      color: failed.length > 0 ? 'text-red-500' : 'text-green-500',
    })
  }

  // 3. Blocked event — show if currently blocked/rejected, or if there was a review
  //    (attestation present means it went through the approval gate, regardless of final status)
  const wasBlocked =
    record.status === 'BLOCKED' ||
    record.status === 'REJECTED' ||
    record.status === 'CANCELED' ||
    (record.attestation != null && !record.autoApproved)
  if (wasBlocked) {
    events.push({
      icon: '⏸',
      label: 'Blocked — pending review',
      detail: record.blockedMessage ?? undefined,
      time: record.timestamp,
      color: 'text-amber-500',
    })
  }

  // 4. Attestation (review event)
  if (record.attestation) {
    const att = record.attestation
    const typeLabel =
      att.type === 'APPROVAL' ? 'Approved' : att.type === 'REJECTION' ? 'Rejected' : 'Canceled'
    events.push({
      icon: att.type === 'APPROVAL' ? '✓' : att.type === 'REJECTION' ? '✗' : '○',
      label: `${typeLabel} by ${att.reviewerUsername}${att.reviewerEmail ? ` (${att.reviewerEmail})` : ''}`,
      detail: att.reason ?? undefined,
      time: att.timestamp,
      color:
        att.type === 'APPROVAL'
          ? 'text-green-600'
          : att.type === 'REJECTION'
            ? 'text-red-600'
            : 'text-gray-400',
    })
  }

  // 5. Forwarded — check for a forwarding step first (S&F), else use record status
  const forwardStep = (record.steps ?? []).find((s) => FORWARDING_STEPS.has(s.stepName))
  if (record.status === 'FORWARDED') {
    const commitLink =
      record.upstreamUrl && record.commitTo
        ? upstreamCommitUrl(record.upstreamUrl, record.commitTo)
        : undefined
    events.push({
      icon: '→',
      label: 'Forwarded to upstream',
      detail: record.upstreamUrl ?? undefined,
      link: commitLink,
      time: forwardStep?.timestamp,
      color: 'text-blue-500',
    })
  }

  // 6. Error
  if (record.status === 'ERROR') {
    events.push({
      icon: '!',
      label: 'Error',
      detail: record.errorMessage ?? undefined,
      time: record.timestamp,
      color: 'text-red-600',
    })
  }

  return (
    <div className="bg-white rounded-lg shadow border border-gray-200 px-6 py-4">
      <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-4">Timeline</h2>
      <ol className="relative border-l border-gray-200 ml-2 space-y-4">
        {events.map((ev, i) => (
          <li key={i} className="ml-5">
            <span
              className={`absolute -left-2.5 flex h-5 w-5 items-center justify-center rounded-full bg-white border border-gray-200 text-xs font-bold ${ev.color}`}
            >
              {ev.icon}
            </span>
            <div className="text-sm text-gray-800 font-medium leading-snug">{ev.label}</div>
            {ev.detail && <div className="text-xs text-gray-500 mt-0.5 font-mono">{ev.detail}</div>}
            {ev.link && (
              <a
                href={ev.link}
                target="_blank"
                rel="noopener noreferrer"
                className="text-xs text-blue-600 hover:underline mt-0.5 block"
              >
                View commit ↗
              </a>
            )}
            {ev.time && <div className="text-xs text-gray-400 mt-0.5">{formatTime(ev.time)}</div>}
          </li>
        ))}
      </ol>
    </div>
  )
}

function RePushGuidance({ record }: { record: PushRecord }) {
  const branch = record.branch?.replace('refs/heads/', '') ?? '<branch>'

  if (record.status === 'APPROVED') {
    return (
      <div className="bg-blue-50 border border-blue-200 rounded-lg px-6 py-4">
        <div className="text-sm font-semibold text-blue-800 mb-1">
          Push approved — re-push to forward
        </div>
        <p className="text-sm text-blue-700 mb-3">
          This push has been approved. Push the same commits again to forward them upstream.
        </p>
        <pre className="text-xs bg-white border border-blue-200 rounded px-3 py-2 font-mono text-blue-900 select-all">
          git push origin {branch}
        </pre>
      </div>
    )
  }

  const att = record.attestation
  const reviewer = att?.reviewerUsername ?? 'a reviewer'
  const reason = att?.reason

  if (record.status === 'CANCELED') {
    return (
      <div className="bg-gray-50 border border-gray-200 rounded-lg px-6 py-4">
        <div className="text-sm font-semibold text-gray-700 mb-1">Push canceled</div>
        <p className="text-sm text-gray-600 mb-3">
          <strong>{reviewer}</strong> canceled this push before review
          {reason ? (
            <>
              : <em>"{reason}"</em>
            </>
          ) : (
            '.'
          )}{' '}
          The commits themselves are fine — re-push when ready.
        </p>
        <pre className="text-xs bg-white border border-gray-200 rounded px-3 py-2 font-mono text-gray-800 select-all">
          git push origin {branch}
        </pre>
      </div>
    )
  }

  return (
    <div className="bg-red-50 border border-red-200 rounded-lg px-6 py-4">
      <div className="text-sm font-semibold text-red-800 mb-1">Push rejected — action required</div>
      <p className="text-sm text-red-700 mb-1">
        <strong>{reviewer}</strong> rejected this push
        {reason ? (
          <>
            : <em>"{reason}"</em>
          </>
        ) : (
          '.'
        )}
      </p>
      <p className="text-sm text-red-700 mb-3">
        Address the feedback, amend your commits, and push again.
      </p>
      <pre className="text-xs bg-white border border-red-200 rounded px-3 py-2 font-mono text-red-900 select-all whitespace-pre-wrap">{`# Amend the last commit and re-push
git commit --amend
git push origin ${branch}

# Or delete the remote branch
git push origin :${branch}`}</pre>
    </div>
  )
}

interface PushDetailProps {
  currentUser: CurrentUser | null
}

export function PushDetail({ currentUser }: PushDetailProps) {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const diffRef = useRef<HTMLDivElement>(null)

  const [record, setRecord] = useState<PushRecord | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const [reviewReason, setReviewReason] = useState('')
  const [saving, setSaving] = useState(false)
  const [actionError, setActionError] = useState('')
  const [openSteps, setOpenSteps] = useState<Record<string, boolean>>({})
  const [canceling, setCanceling] = useState(false)

  async function load(pushId: string) {
    setLoading(true)
    setError('')
    setRecord(null)
    setReviewReason('')
    setActionError('')
    setOpenSteps({})
    try {
      const data = await fetchPush(pushId)
      setRecord(data)
    } catch (e) {
      setError(String(e))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (id) load(id)
  }, [id])

  // Render diff via diff2html after record is set
  useEffect(() => {
    if (!record || !diffRef.current) return
    const diffStep = record.steps?.find((s) => s.stepName === 'diff' && s.content)
    if (!diffStep?.content) return
    try {
      const ui = new Diff2HtmlUI(diffRef.current, diffStep.content, {
        drawFileList: true,
        matching: 'lines',
        outputFormat: 'side-by-side',
        highlight: true,
      })
      ui.draw()
      ui.highlightCode()
    } catch {
      if (diffRef.current) {
        diffRef.current.innerHTML =
          '<pre class="text-xs text-gray-700 whitespace-pre-wrap overflow-x-auto">' +
          diffStep.content.replace(/</g, '&lt;') +
          '</pre>'
      }
    }
  }, [record])

  const validationSteps: Step[] = (record?.steps ?? [])
    .filter((s) => !NON_VALIDATION_STEPS.has(s.stepName))
    .sort((a, b) => a.stepOrder - b.stepOrder)

  const hasDiff = record?.steps?.some((s) => s.stepName === 'diff' && s.content)

  async function handleApprove() {
    if (!record || !reviewReason.trim()) return
    setSaving(true)
    setActionError('')
    try {
      await approvePush(record.id, {
        reviewerUsername: currentUser?.username ?? '',
        reviewerEmail: currentUser?.emails[0] ?? '',
        reason: reviewReason,
      })
      await load(record.id)
    } catch (e) {
      setActionError(String(e))
    } finally {
      setSaving(false)
    }
  }

  async function handleReject() {
    if (!record || !reviewReason.trim()) return
    setSaving(true)
    setActionError('')
    try {
      await rejectPush(record.id, {
        reviewerUsername: currentUser?.username ?? '',
        reviewerEmail: currentUser?.emails[0] ?? '',
        reason: reviewReason,
      })
      await load(record.id)
    } catch (e) {
      setActionError(String(e))
    } finally {
      setSaving(false)
    }
  }

  async function handleCancel() {
    if (!record) return
    setCanceling(true)
    setActionError('')
    try {
      await cancelPush(record.id)
      await load(record.id)
    } catch (e) {
      setActionError(String(e))
    } finally {
      setCanceling(false)
    }
  }

  return (
    <div className="max-w-5xl mx-auto px-4 py-6 space-y-6">
      <button onClick={() => navigate('/')} className="text-sm text-blue-600 hover:underline">
        ← Back to push records
      </button>

      {loading && <div className="text-center text-gray-400 py-16">Loading…</div>}
      {error && <div className="text-red-600 py-8 text-center">{error}</div>}

      {record && !loading && (
        <>
          {/* Header card */}
          <div className="bg-white rounded-lg shadow border border-gray-200 px-6 py-4">
            <div className="flex items-start gap-4">
              <StatusBadge status={record.status} className="mt-1" />
              <div className="flex-1">
                <div className="text-lg font-semibold text-gray-900">
                  {(record.project ?? '') + '/' + (record.repoName ?? record.url ?? '')}
                </div>
                <div className="text-sm text-gray-500 mt-0.5 font-mono">{record.branch}</div>
                {record.upstreamUrl && (
                  <div className="text-xs text-gray-400 mt-1">
                    <span className="text-gray-500">Upstream: </span>
                    <a
                      href={record.upstreamUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="font-mono text-blue-600 hover:underline"
                    >
                      {record.upstreamUrl}
                    </a>
                  </div>
                )}
                {record.message && (
                  <div className="text-sm text-gray-700 italic mt-1">{record.message}</div>
                )}
              </div>
              <div className="text-right text-sm text-gray-500 shrink-0">
                <div>{record.author ?? record.user ?? '—'}</div>
                {record.committer && record.committer !== record.author && (
                  <div className="text-xs text-gray-400">committer: {record.committer}</div>
                )}
                <div className="text-xs font-mono text-gray-400 mt-0.5">
                  {record.commitTo ?? ''}
                </div>
                <div className="text-xs text-gray-400">{formatTime(record.timestamp)}</div>
              </div>
            </div>
            {record.blockedMessage && (
              <div className="mt-3 flex gap-2 text-sm text-amber-800 bg-amber-50 border border-amber-200 rounded px-3 py-2">
                <span>⚠️</span>
                <span>{record.blockedMessage}</span>
              </div>
            )}
            {record.attestation && (
              <div className="mt-3 text-sm text-gray-500 border-t border-gray-100 pt-2">
                Reviewed by <strong>{record.attestation.reviewerUsername}</strong>
                {record.attestation.reviewerEmail && ` (${record.attestation.reviewerEmail})`}
                {record.attestation.reason && ` · "${record.attestation.reason}"`}
              </div>
            )}
          </div>

          {/* Push timeline */}
          <PushTimeline record={record} />

          {/* Commits */}
          {record.commits && record.commits.length > 0 && (
            <div className="bg-white rounded-lg shadow border border-gray-200 px-6 py-4">
              <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-3">
                Commits ({record.commits.length})
              </h2>
              <div className="space-y-2">
                {record.commits.map((c) => (
                  <div key={c.sha} className="border border-gray-100 rounded p-3 space-y-1">
                    <div className="font-mono text-xs text-gray-400">{c.sha}</div>
                    <div className="text-sm text-gray-800 whitespace-pre-wrap">
                      {(c.message ?? '').split('\n')[0].trim()}
                    </div>
                    <div className="text-xs text-gray-500">
                      <span className="font-medium">Author: </span>
                      {c.authorName} &lt;{c.authorEmail}&gt;
                    </div>
                    {c.committerName &&
                      (c.committerName !== c.authorName || c.committerEmail !== c.authorEmail) && (
                        <div className="text-xs text-gray-500">
                          <span className="font-medium">Committer: </span>
                          {c.committerName} &lt;{c.committerEmail}&gt;
                        </div>
                      )}
                    {c.signedOffBy && c.signedOffBy.length > 0 && (
                      <div className="text-xs text-gray-500">
                        <span className="font-medium">Signed-off-by: </span>
                        {c.signedOffBy.map((sob) => (
                          <span
                            key={sob}
                            className="ml-1 bg-green-50 text-green-700 border border-green-200 rounded px-1"
                          >
                            {sob}
                          </span>
                        ))}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Validation steps */}
          {validationSteps.length > 0 && (
            <div className="bg-white rounded-lg shadow border border-gray-200 px-6 py-4">
              <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-3">
                Validation steps
              </h2>
              <div className="space-y-1">
                {validationSteps.map((s) => {
                  const isFailed = s.status === 'FAIL' || s.status === 'BLOCKED'
                  const isOpen = openSteps[s.id]
                  return (
                    <div key={s.id}>
                      <div
                        className="flex gap-3 items-baseline text-sm cursor-pointer"
                        onClick={() => setOpenSteps((prev) => ({ ...prev, [s.id]: !prev[s.id] }))}
                      >
                        <span
                          className={
                            s.status === 'PASS'
                              ? 'text-green-500'
                              : isFailed
                                ? 'text-red-500'
                                : 'text-gray-400'
                          }
                        >
                          {s.status === 'PASS' ? '✓' : isFailed ? '✗' : '–'}
                        </span>
                        <span className="text-sm text-gray-700 w-56 shrink-0">
                          {stepDisplayName(s.stepName)}
                        </span>
                        <span className="text-gray-500 text-xs truncate flex-1">
                          {s.errorMessage ?? s.blockedMessage ?? ''}
                        </span>
                        {isFailed && s.content && (
                          <span className="text-xs text-gray-400 shrink-0">
                            {isOpen ? '▲ hide' : '▼ details'}
                          </span>
                        )}
                      </div>
                      {isOpen && s.content && (
                        <pre className="mt-2 ml-6 text-xs bg-gray-50 border border-gray-200 rounded p-3 whitespace-pre-wrap font-mono text-gray-800 overflow-x-auto">
                          {stripAnsi(s.content)}
                        </pre>
                      )}
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          {/* Diff */}
          <div className="bg-white rounded-lg shadow border border-gray-200 px-6 py-4">
            <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-3">
              Diff
            </h2>
            {hasDiff ? (
              <div ref={diffRef} className="text-sm overflow-x-auto" />
            ) : (
              <div className="text-gray-400 text-sm">No diff available.</div>
            )}
          </div>

          {/* Re-push guidance */}
          {(record.status === 'REJECTED' ||
            record.status === 'CANCELED' ||
            record.status === 'APPROVED') && <RePushGuidance record={record} />}

          {/* Approve / Reject / Cancel */}
          {record.status === 'BLOCKED' && (
            <div className="bg-white rounded-lg shadow border border-gray-200 px-6 py-5">
              <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-3">
                Review
              </h2>
              <div className="text-xs text-gray-400 mb-3">
                Reviewing as{' '}
                <strong>
                  {currentUser
                    ? currentUser.username +
                      (currentUser.emails[0] ? ` (${currentUser.emails[0]})` : '')
                    : '…'}
                </strong>
              </div>
              <textarea
                value={reviewReason}
                onChange={(e) => setReviewReason(e.target.value)}
                rows={3}
                placeholder={
                  'Reason (required for both approve and reject)\nDescribe the basis for your decision...'
                }
                className="w-full border border-gray-300 rounded px-3 py-2 text-sm mb-3 resize-none focus:outline-none focus:ring-2 focus:ring-blue-300"
              />
              {actionError && <div className="text-red-600 text-sm mb-3">{actionError}</div>}
              <div className="flex gap-3">
                <button
                  onClick={handleApprove}
                  disabled={saving || canceling || !reviewReason.trim()}
                  className="px-4 py-2 text-sm font-medium rounded bg-green-600 text-white hover:bg-green-700 disabled:opacity-50"
                >
                  ✓ Approve
                </button>
                <button
                  onClick={handleReject}
                  disabled={saving || canceling || !reviewReason.trim()}
                  className="px-4 py-2 text-sm font-medium rounded bg-red-600 text-white hover:bg-red-700 disabled:opacity-50"
                >
                  ✗ Reject
                </button>
                <button
                  onClick={handleCancel}
                  disabled={saving || canceling}
                  className="ml-auto px-4 py-2 text-sm font-medium rounded border border-gray-300 text-gray-600 hover:bg-gray-50 disabled:opacity-50"
                >
                  {canceling ? 'Canceling…' : 'Cancel push'}
                </button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  )
}
