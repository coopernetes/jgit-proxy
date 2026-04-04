import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { fetchPushes } from '../api'
import { StatusBadge } from '../components/StatusBadge'
import type { PushRecord } from '../types'

const STATUSES = ['', 'BLOCKED', 'APPROVED', 'REJECTED', 'FORWARDED', 'RECEIVED', 'ERROR']

function formatTime(ts: string | number | undefined) {
  if (!ts) return ''
  try {
    return new Date(ts).toLocaleString()
  } catch {
    return String(ts)
  }
}

export function PushList() {
  const navigate = useNavigate()
  const [pushes, setPushes] = useState<PushRecord[]>([])
  const [filterStatus, setFilterStatus] = useState('BLOCKED')
  const [filterRepo, setFilterRepo] = useState('')
  const [lastRefresh, setLastRefresh] = useState('')

  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const load = useCallback(async (status: string, search: string) => {
    const params = new URLSearchParams({ limit: '100' })
    if (status) params.set('status', status)
    if (search) params.set('search', search)
    const data = await fetchPushes(params)
    setPushes(data)
    setLastRefresh(new Date().toLocaleTimeString())
  }, [])

  // Initial load + auto-refresh every 10s
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load(filterStatus, filterRepo)
    const timer = setInterval(() => load(filterStatus, filterRepo), 10_000)
    return () => clearInterval(timer)
  }, [filterStatus, filterRepo, load])

  function handleRepoChange(value: string) {
    setFilterRepo(value)
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => load(filterStatus, value), 300)
  }

  return (
    <div>
      <div className="max-w-7xl mx-auto px-4 py-4 flex gap-3 flex-wrap items-center">
        <select
          value={filterStatus}
          onChange={(e) => setFilterStatus(e.target.value)}
          className="border border-gray-300 rounded px-3 py-1.5 text-sm bg-white shadow-sm"
        >
          <option value="">All statuses</option>
          {STATUSES.filter(Boolean).map((s) => (
            <option key={s} value={s}>
              {s.charAt(0) + s.slice(1).toLowerCase().replace('_', ' ')}
            </option>
          ))}
        </select>

        <input
          value={filterRepo}
          onChange={(e) => handleRepoChange(e.target.value)}
          type="text"
          placeholder="Filter by project or repo..."
          className="border border-gray-300 rounded px-3 py-1.5 text-sm bg-white shadow-sm w-52"
        />

        <div className="ml-auto flex items-center gap-4 text-sm text-gray-400">
          <span>{pushes.length} records</span>
          {lastRefresh && <span>refreshed {lastRefresh}</span>}
          <button
            onClick={() => load(filterStatus, filterRepo)}
            className="text-blue-600 hover:underline"
          >
            &#8635; Refresh
          </button>
        </div>
      </div>

      <div className="max-w-7xl mx-auto px-4 space-y-2 pb-12">
        {pushes.length === 0 && (
          <div className="text-center text-gray-400 py-16">No push records found.</div>
        )}
        {pushes.map((push) => (
          <div
            key={push.id}
            onClick={() => navigate(`/push/${push.id}`)}
            className="bg-white rounded-lg shadow border border-gray-200 hover:border-blue-300 cursor-pointer transition-colors"
          >
            <div className="flex items-center gap-4 px-5 py-3">
              <StatusBadge status={push.status} />
              <div className="flex-1 min-w-0">
                <div className="font-medium text-gray-900 truncate">
                  {(push.project ?? '') + '/' + (push.repoName ?? push.url ?? 'unknown')}
                </div>
                <div className="text-sm text-gray-500 truncate">
                  <span>{push.branch ?? '—'}</span>
                  <span className="mx-1 text-gray-300">&middot;</span>
                  <span className="font-mono text-xs">{(push.commitTo ?? '').substring(0, 8)}</span>
                  {push.message && (
                    <span>
                      {' '}
                      &middot; <em>{push.message}</em>
                    </span>
                  )}
                </div>
              </div>
              <div className="text-right text-sm text-gray-500 shrink-0">
                <div>{push.author ?? push.user ?? '—'}</div>
                <div className="text-xs text-gray-400">{formatTime(push.timestamp)}</div>
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
    </div>
  )
}
