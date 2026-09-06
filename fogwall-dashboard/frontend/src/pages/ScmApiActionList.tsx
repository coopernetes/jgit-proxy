import { useCallback, useEffect, useRef, useState } from 'react'
import { fetchScmApiActions } from '../api'
import { StatusBadge } from '../components/StatusBadge'
import type { ScmApiActionRecord, ScmApiActionStatus, CurrentUser } from '../types'

const PAGE_SIZE = 25
const STATUSES: ScmApiActionStatus[] = ['FORWARDED', 'DENIED', 'REJECTED', 'ERROR']

function formatTime(ts: string | number | undefined) {
  if (!ts) return ''
  try {
    return new Date(ts).toLocaleString()
  } catch {
    return String(ts)
  }
}

interface ScmApiActionListProps {
  currentUser: CurrentUser | null
}

export function ScmApiActionList({ currentUser }: ScmApiActionListProps) {
  const [actions, setActions] = useState<ScmApiActionRecord[]>([])
  const [filterStatus, setFilterStatus] = useState<string>('')
  const [filterSearch, setFilterSearch] = useState('')
  const [myActionsOnly, setMyActionsOnly] = useState(false)
  const [newestFirst, setNewestFirst] = useState(true)
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [lastRefresh, setLastRefresh] = useState('')
  const [expandedId, setExpandedId] = useState<string | null>(null)

  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const load = useCallback(
    async (status: string, search: string, myOnly: boolean, newest: boolean, pageNum: number) => {
      const offset = pageNum * PAGE_SIZE
      const params = new URLSearchParams({ limit: String(PAGE_SIZE + 1), offset: String(offset) })
      if (status) params.set('status', status)
      if (search) params.set('search', search)
      if (myOnly && currentUser?.username) params.set('user', currentUser.username)
      params.set('newestFirst', String(newest))
      const data = await fetchScmApiActions(params)
      setHasMore(data.length > PAGE_SIZE)
      setActions(data.slice(0, PAGE_SIZE))
      setLastRefresh(new Date().toLocaleTimeString())
    },
    [currentUser],
  )

  useEffect(() => {
    void Promise.resolve().then(() =>
      load(filterStatus, filterSearch, myActionsOnly, newestFirst, page),
    )
    const timer = setInterval(
      () => load(filterStatus, filterSearch, myActionsOnly, newestFirst, page),
      10_000,
    )
    return () => clearInterval(timer)
  }, [filterStatus, filterSearch, myActionsOnly, newestFirst, page, load])

  function handleSearchChange(value: string) {
    setFilterSearch(value)
    setPage(0)
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(
      () => load(filterStatus, value, myActionsOnly, newestFirst, 0),
      300,
    )
  }

  return (
    <div>
      {/* Status filter chips */}
      <div className="max-w-7xl mx-auto px-4 pt-4 flex gap-2 flex-wrap">
        <button
          onClick={() => {
            setFilterStatus('')
            setPage(0)
          }}
          className={`px-3 py-1 rounded-full text-xs font-medium border transition-colors ${
            filterStatus === ''
              ? 'bg-gray-900 text-white border-gray-900 dark:bg-slate-100 dark:text-gray-900 dark:border-slate-100'
              : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50 dark:bg-slate-800 dark:text-gray-300 dark:border-slate-600 dark:hover:bg-gray-700'
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
                ? 'bg-gray-900 text-white border-gray-900 dark:bg-slate-100 dark:text-gray-900 dark:border-slate-100'
                : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50 dark:bg-slate-800 dark:text-gray-300 dark:border-slate-600 dark:hover:bg-gray-700'
            }`}
          >
            {s.charAt(0) + s.slice(1).toLowerCase()}
          </button>
        ))}
      </div>

      {/* Filter bar */}
      <div className="max-w-7xl mx-auto px-4 py-3 flex gap-3 flex-wrap items-center border-b border-gray-100 dark:border-slate-700">
        <input
          value={filterSearch}
          onChange={(e) => handleSearchChange(e.target.value)}
          type="text"
          placeholder="Filter by repo owner or name..."
          className="border border-gray-300 rounded px-3 py-1.5 text-sm bg-white shadow-sm w-56 dark:bg-slate-700 dark:border-slate-600 dark:text-gray-200 dark:placeholder-gray-400"
        />

        {currentUser && (
          <button
            onClick={() => {
              setMyActionsOnly((v) => !v)
              setPage(0)
            }}
            className={`px-3 py-1.5 text-sm rounded border transition-colors ${
              myActionsOnly
                ? 'bg-blue-600 text-white border-blue-600'
                : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50 dark:bg-slate-800 dark:text-gray-300 dark:border-slate-600 dark:hover:bg-gray-700'
            }`}
          >
            My actions
          </button>
        )}

        <button
          onClick={() => {
            setNewestFirst((v) => !v)
            setPage(0)
          }}
          className="px-3 py-1.5 text-sm rounded border border-gray-300 bg-white text-gray-600 hover:bg-gray-50 transition-colors dark:bg-slate-800 dark:text-gray-300 dark:border-slate-600 dark:hover:bg-gray-700"
          title={newestFirst ? 'Currently: newest first' : 'Currently: oldest first'}
        >
          {newestFirst ? '↓ Newest first' : '↑ Oldest first'}
        </button>

        <div className="ml-auto flex items-center gap-4 text-sm text-gray-400 dark:text-gray-500">
          <span>
            {actions.length} record{actions.length !== 1 ? 's' : ''}
            {page > 0 ? ` (page ${page + 1})` : ''}
          </span>
          {lastRefresh && <span>refreshed {lastRefresh}</span>}
          <button
            onClick={() => load(filterStatus, filterSearch, myActionsOnly, newestFirst, page)}
            className="text-blue-600 hover:underline dark:text-blue-400"
          >
            &#8635; Refresh
          </button>
        </div>
      </div>

      {/* List */}
      <div className="max-w-7xl mx-auto px-4 space-y-2 py-4 pb-12">
        {actions.length === 0 && (
          <div className="text-center text-gray-400 dark:text-gray-500 py-16">
            No SCM API action records found.
          </div>
        )}
        {actions.map((action) => (
          <div
            key={action.id}
            onClick={() => setExpandedId((id) => (id === action.id ? null : action.id))}
            className="bg-white rounded-lg shadow border border-gray-200 hover:border-blue-300 transition-colors cursor-pointer dark:bg-slate-800 dark:border-slate-700 dark:hover:border-blue-500"
          >
            <div className="flex items-center gap-4 px-5 py-3">
              <StatusBadge status={action.status} />
              <div className="flex-1 min-w-0 space-y-0.5">
                <div className="font-mono text-sm text-gray-900 truncate dark:text-gray-100">
                  {action.provider ?? '—'} · {action.mutationField ?? '—'}
                </div>
                <div className="text-xs text-gray-500 truncate dark:text-gray-400">
                  {action.repoOwner && action.repoName
                    ? `${action.repoOwner}/${action.repoName}`
                    : '—'}
                </div>
                {action.reason && (
                  <div className="text-xs text-gray-400 truncate dark:text-gray-500">
                    {action.reason}
                  </div>
                )}
              </div>
              <div className="text-right text-sm text-gray-500 shrink-0 dark:text-gray-400">
                <div>{action.resolvedUser ?? action.scmUsername ?? '—'}</div>
                <div className="text-xs text-gray-400 mt-0.5 dark:text-gray-500">
                  {formatTime(action.timestamp)}
                </div>
              </div>
            </div>
            {expandedId === action.id && (
              <div className="px-5 pb-4 space-y-1 text-xs text-gray-500 border-t border-gray-100 pt-3 dark:border-slate-700 dark:text-gray-400">
                <div>id: {action.id}</div>
                <div>nodeId: {action.nodeId ?? '—'}</div>
                <div>nodeType: {action.nodeType ?? '—'}</div>
                {action.variablesJson && (
                  <pre className="mt-2 bg-gray-50 rounded p-2 overflow-x-auto whitespace-pre-wrap break-all dark:bg-slate-900">
                    {action.variablesJson}
                  </pre>
                )}
              </div>
            )}
          </div>
        ))}
      </div>

      {/* Pagination */}
      {(page > 0 || hasMore) && (
        <div className="max-w-7xl mx-auto px-4 pb-12 flex justify-center gap-4">
          <button
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0}
            className="px-4 py-2 text-sm rounded border border-gray-300 bg-white text-gray-700 hover:bg-gray-50 disabled:opacity-40 dark:bg-slate-800 dark:border-slate-600 dark:text-gray-300 dark:hover:bg-gray-700"
          >
            ← Previous
          </button>
          <span className="px-4 py-2 text-sm text-gray-500 dark:text-gray-400">
            Page {page + 1}
          </span>
          <button
            onClick={() => setPage((p) => p + 1)}
            disabled={!hasMore}
            className="px-4 py-2 text-sm rounded border border-gray-300 bg-white text-gray-700 hover:bg-gray-50 disabled:opacity-40 dark:bg-slate-800 dark:border-slate-600 dark:text-gray-300 dark:hover:bg-gray-700"
          >
            Next →
          </button>
        </div>
      )}
    </div>
  )
}
