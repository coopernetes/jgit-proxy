import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { approvePush, fetchPushes, rejectPush } from '../api'
import { StatusBadge } from '../components/StatusBadge'
import type { CurrentUser, PushRecord, PushStatus } from '../types'

const PAGE_SIZE = 25
const STATUSES: PushStatus[] = ['BLOCKED', 'APPROVED', 'REJECTED', 'FORWARDED', 'RECEIVED', 'ERROR']

const STATUS_COLORS: Record<string, string> = {
  BLOCKED: 'bg-amber-50 text-amber-700 border-amber-200 hover:bg-amber-100',
  APPROVED: 'bg-green-50 text-green-700 border-green-200 hover:bg-green-100',
  FORWARDED: 'bg-blue-50 text-blue-700 border-blue-200 hover:bg-blue-100',
  REJECTED: 'bg-red-50 text-red-700 border-red-200 hover:bg-red-100',
  RECEIVED: 'bg-slate-50 text-slate-700 border-slate-200 hover:bg-slate-100',
  ERROR: 'bg-rose-50 text-rose-800 border-rose-200 hover:bg-rose-100',
}

function formatTime(ts: string | number | undefined) {
  if (!ts) return ''
  try {
    return new Date(ts).toLocaleString()
  } catch {
    return String(ts)
  }
}

interface PushListProps {
  currentUser: CurrentUser | null
}

export function PushList({ currentUser }: PushListProps) {
  const navigate = useNavigate()
  const [pushes, setPushes] = useState<PushRecord[]>([])
  const [filterStatus, setFilterStatus] = useState<string>('BLOCKED')
  const [filterRepo, setFilterRepo] = useState('')
  const [myPushesOnly, setMyPushesOnly] = useState(false)
  const [newestFirst, setNewestFirst] = useState(true)
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [lastRefresh, setLastRefresh] = useState('')
  const [counts, setCounts] = useState<Partial<Record<string, number>>>({})
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set())
  const [bulkReason, setBulkReason] = useState('')
  const [bulkWorking, setBulkWorking] = useState(false)
  const [bulkError, setBulkError] = useState('')

  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // Only allow selection when viewing BLOCKED pushes
  const selectionEnabled = filterStatus === 'BLOCKED'

  function toggleSelect(id: string, e: React.MouseEvent) {
    e.stopPropagation()
    setSelectedIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
      return next
    })
  }

  function toggleSelectAll() {
    const blockedIds = pushes.filter((p) => p.status === 'BLOCKED').map((p) => p.id)
    setSelectedIds((prev) => (prev.size === blockedIds.length ? new Set() : new Set(blockedIds)))
  }

  async function handleBulkApprove() {
    if (!bulkReason.trim() || selectedIds.size === 0) return
    setBulkWorking(true)
    setBulkError('')
    try {
      await Promise.all(
        [...selectedIds].map((id) =>
          approvePush(id, {
            reviewerUsername: currentUser?.username ?? '',
            reviewerEmail: currentUser?.emails[0]?.email ?? '',
            reason: bulkReason,
          }),
        ),
      )
      setSelectedIds(new Set())
      setBulkReason('')
      await load(filterStatus, filterRepo, myPushesOnly, newestFirst, page)
    } catch (e) {
      setBulkError(String(e))
    } finally {
      setBulkWorking(false)
    }
  }

  async function handleBulkReject() {
    if (!bulkReason.trim() || selectedIds.size === 0) return
    setBulkWorking(true)
    setBulkError('')
    try {
      await Promise.all(
        [...selectedIds].map((id) =>
          rejectPush(id, {
            reviewerUsername: currentUser?.username ?? '',
            reviewerEmail: currentUser?.emails[0]?.email ?? '',
            reason: bulkReason,
          }),
        ),
      )
      setSelectedIds(new Set())
      setBulkReason('')
      await load(filterStatus, filterRepo, myPushesOnly, newestFirst, page)
    } catch (e) {
      setBulkError(String(e))
    } finally {
      setBulkWorking(false)
    }
  }

  const load = useCallback(
    async (status: string, search: string, myOnly: boolean, newest: boolean, pageNum: number) => {
      const offset = pageNum * PAGE_SIZE
      const params = new URLSearchParams({ limit: String(PAGE_SIZE + 1), offset: String(offset) })
      if (status) params.set('status', status)
      if (search) params.set('search', search)
      if (myOnly && currentUser?.username) params.set('user', currentUser.username)
      params.set('newestFirst', String(newest))
      const data: PushRecord[] = await fetchPushes(params)
      // Fetch one extra to detect if there's a next page
      setHasMore(data.length > PAGE_SIZE)
      setPushes(data.slice(0, PAGE_SIZE))
      setLastRefresh(new Date().toLocaleTimeString())
    },
    [currentUser],
  )

  const loadCounts = useCallback(async () => {
    const results: Partial<Record<string, number>> = {}
    await Promise.all(
      STATUSES.map(async (s) => {
        const params = new URLSearchParams({ status: s, limit: '1000' })
        const data: PushRecord[] = await fetchPushes(params)
        results[s] = data.length
      }),
    )
    setCounts(results)
  }, [])

  // Clear selection when leaving BLOCKED filter
  useEffect(() => {
    if (filterStatus !== 'BLOCKED') setSelectedIds(new Set())
  }, [filterStatus])

  // Load data when filters or page changes
  useEffect(() => {
    load(filterStatus, filterRepo, myPushesOnly, newestFirst, page)
    const timer = setInterval(
      () => load(filterStatus, filterRepo, myPushesOnly, newestFirst, page),
      10_000,
    )
    return () => clearInterval(timer)
  }, [filterStatus, filterRepo, myPushesOnly, newestFirst, page, load])

  // Load counts once on mount
  useEffect(() => {
    loadCounts()
  }, [loadCounts])

  function handleRepoChange(value: string) {
    setFilterRepo(value)
    setPage(0)
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(
      () => load(filterStatus, value, myPushesOnly, newestFirst, 0),
      300,
    )
  }

  return (
    <div>
      {/* Status summary chips */}
      <div className="max-w-7xl mx-auto px-4 pt-4 flex gap-2 flex-wrap">
        <button
          onClick={() => {
            setFilterStatus('')
            setPage(0)
          }}
          className={`px-3 py-1 rounded-full text-xs font-medium border transition-colors ${
            filterStatus === ''
              ? 'bg-gray-900 text-white border-gray-900'
              : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50'
          }`}
        >
          All
        </button>
        {STATUSES.map((s) => (
          <button
            key={s}
            onClick={() => {
              setFilterStatus(s)
              setPage(0)
            }}
            className={`px-3 py-1 rounded-full text-xs font-medium border transition-colors ${
              filterStatus === s
                ? 'bg-gray-900 text-white border-gray-900'
                : (STATUS_COLORS[s] ?? 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50')
            }`}
          >
            {s.charAt(0) + s.slice(1).toLowerCase()}
            {counts[s] != null ? ` · ${counts[s]}` : ''}
          </button>
        ))}
      </div>

      {/* Bulk action bar — shown when items are selected */}
      {selectionEnabled && selectedIds.size > 0 && (
        <div className="max-w-7xl mx-auto px-4 py-3 flex gap-3 flex-wrap items-center bg-amber-50 border-y border-amber-200">
          <span className="text-sm font-medium text-amber-800">
            {selectedIds.size} push{selectedIds.size !== 1 ? 'es' : ''} selected
          </span>
          <input
            value={bulkReason}
            onChange={(e) => setBulkReason(e.target.value)}
            type="text"
            placeholder="Reason (required)..."
            className="flex-1 min-w-40 border border-amber-300 rounded px-3 py-1.5 text-sm bg-white focus:outline-none focus:ring-2 focus:ring-amber-300"
          />
          <button
            onClick={handleBulkApprove}
            disabled={bulkWorking || !bulkReason.trim()}
            className="px-3 py-1.5 text-sm font-medium rounded bg-green-600 text-white hover:bg-green-700 disabled:opacity-50"
          >
            {bulkWorking ? '…' : `✓ Approve ${selectedIds.size}`}
          </button>
          <button
            onClick={handleBulkReject}
            disabled={bulkWorking || !bulkReason.trim()}
            className="px-3 py-1.5 text-sm font-medium rounded bg-red-600 text-white hover:bg-red-700 disabled:opacity-50"
          >
            {bulkWorking ? '…' : `✗ Reject ${selectedIds.size}`}
          </button>
          <button
            onClick={() => {
              setSelectedIds(new Set())
              setBulkReason('')
              setBulkError('')
            }}
            className="text-sm text-amber-700 hover:underline ml-auto"
          >
            Clear
          </button>
          {bulkError && <div className="w-full text-sm text-red-600">{bulkError}</div>}
        </div>
      )}

      {/* Filter bar */}
      <div className="max-w-7xl mx-auto px-4 py-3 flex gap-3 flex-wrap items-center border-b border-gray-100">
        {selectionEnabled && (
          <label className="flex items-center gap-1.5 text-sm text-gray-500 cursor-pointer select-none">
            <input
              type="checkbox"
              checked={
                selectedIds.size > 0 &&
                selectedIds.size === pushes.filter((p) => p.status === 'BLOCKED').length
              }
              onChange={toggleSelectAll}
              className="rounded border-gray-300"
            />
            <span className="text-xs">All</span>
          </label>
        )}
        <input
          value={filterRepo}
          onChange={(e) => handleRepoChange(e.target.value)}
          type="text"
          placeholder="Filter by project or repo..."
          className="border border-gray-300 rounded px-3 py-1.5 text-sm bg-white shadow-sm w-52"
        />

        {currentUser && (
          <button
            onClick={() => {
              setMyPushesOnly((v) => !v)
              setPage(0)
            }}
            className={`px-3 py-1.5 text-sm rounded border transition-colors ${
              myPushesOnly
                ? 'bg-blue-600 text-white border-blue-600'
                : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50'
            }`}
          >
            My pushes
          </button>
        )}

        <button
          onClick={() => {
            setNewestFirst((v) => !v)
            setPage(0)
          }}
          className="px-3 py-1.5 text-sm rounded border border-gray-300 bg-white text-gray-600 hover:bg-gray-50 transition-colors"
          title={newestFirst ? 'Currently: newest first' : 'Currently: oldest first'}
        >
          {newestFirst ? '↓ Newest first' : '↑ Oldest first'}
        </button>

        <div className="ml-auto flex items-center gap-4 text-sm text-gray-400">
          <span>
            {pushes.length} record{pushes.length !== 1 ? 's' : ''}
            {page > 0 ? ` (page ${page + 1})` : ''}
          </span>
          {lastRefresh && <span>refreshed {lastRefresh}</span>}
          <button
            onClick={() => load(filterStatus, filterRepo, myPushesOnly, newestFirst, page)}
            className="text-blue-600 hover:underline"
          >
            &#8635; Refresh
          </button>
        </div>
      </div>

      {/* List */}
      <div className="max-w-7xl mx-auto px-4 space-y-2 py-4 pb-12">
        {pushes.length === 0 && (
          <div className="text-center text-gray-400 py-16">No push records found.</div>
        )}
        {pushes.map((push) => (
          <div
            key={push.id}
            onClick={() => navigate(`/push/${push.id}`)}
            className={`bg-white rounded-lg shadow border transition-colors cursor-pointer ${
              selectedIds.has(push.id)
                ? 'border-amber-300 bg-amber-50'
                : 'border-gray-200 hover:border-blue-300'
            }`}
          >
            <div className="flex items-center gap-4 px-5 py-3">
              {selectionEnabled && push.status === 'BLOCKED' && (
                <input
                  type="checkbox"
                  checked={selectedIds.has(push.id)}
                  onClick={(e) => toggleSelect(push.id, e)}
                  onChange={() => {}}
                  className="rounded border-gray-300 shrink-0"
                />
              )}
              <StatusBadge status={push.status} />
              <div className="flex-1 min-w-0 space-y-0.5">
                <div className="font-mono text-sm text-gray-900 truncate">
                  {push.upstreamUrl ??
                    push.url ??
                    (push.project ?? '') + '/' + (push.repoName ?? 'unknown')}
                </div>
                <div className="text-xs text-gray-500 truncate">{push.branch ?? '—'}</div>
                <div className="font-mono text-xs text-gray-400 break-all">
                  {push.commitTo ?? '—'}
                </div>
              </div>
              <div className="text-right text-sm text-gray-500 shrink-0">
                <div>{push.author ?? push.user ?? '—'}</div>
                {push.resolvedUser ? (
                  <span className="inline-flex items-center gap-0.5 text-xs text-green-600 font-medium">
                    ● identity resolved
                  </span>
                ) : push.user ? (
                  <span className="inline-flex items-center gap-0.5 text-xs text-gray-400 font-medium">
                    ● identity unresolved
                  </span>
                ) : null}
                <div className="text-xs text-gray-400 mt-0.5">{formatTime(push.timestamp)}</div>
              </div>
              <svg
                className="w-4 h-4 text-gray-300 shrink-0"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M9 5l7 7-7 7"
                />
              </svg>
            </div>
          </div>
        ))}
      </div>

      {/* Pagination */}
      {(page > 0 || hasMore) && (
        <div className="max-w-7xl mx-auto px-4 pb-12 flex justify-center gap-4">
          <button
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0}
            className="px-4 py-2 text-sm rounded border border-gray-300 bg-white text-gray-700 hover:bg-gray-50 disabled:opacity-40"
          >
            ← Previous
          </button>
          <span className="px-4 py-2 text-sm text-gray-500">Page {page + 1}</span>
          <button
            onClick={() => setPage((p) => p + 1)}
            disabled={!hasMore}
            className="px-4 py-2 text-sm rounded border border-gray-300 bg-white text-gray-700 hover:bg-gray-50 disabled:opacity-40"
          >
            Next →
          </button>
        </div>
      )}
    </div>
  )
}
