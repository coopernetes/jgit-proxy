import { useEffect, useRef, useState } from 'react'
import type { Provider } from '../types'
import {
  type GitProbeResult,
  type LogStep,
  type ProviderConnectivity,
  type TcpResult,
  type TlsResult,
  type HttpResult,
  checkConnectivity,
  checkTargetedConnectivity,
  fetchProviders,
  triggerConfigReload,
} from '../api'

function ms(n: number) {
  return `${n} ms`
}

function TcpBadge({ tcp }: { tcp: TcpResult }) {
  const ok = tcp.status === 'ok'
  return (
    <span
      className={`inline-flex items-center gap-1 text-xs font-medium px-2 py-0.5 rounded-full ${
        ok ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
      }`}
    >
      {ok ? '✓' : '✗'} TCP {ok ? ms(tcp.durationMs) : (tcp.error ?? 'ERROR')}
    </span>
  )
}

function TlsBadge({ tls }: { tls: TlsResult | null }) {
  if (tls === null) return null
  const ok = tls.status === 'ok'
  return (
    <span
      className={`inline-flex items-center gap-1 text-xs font-medium px-2 py-0.5 rounded-full ${
        ok ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
      }`}
    >
      {ok ? '✓' : '✗'} TLS {ok ? ms(tls.durationMs) : (tls.error ?? 'ERROR')}
    </span>
  )
}

function HttpBadge({ http }: { http: HttpResult | null }) {
  if (http === null) return null
  const ok = typeof http.status === 'number' && http.status < 500
  const label =
    typeof http.status === 'number' ? `HTTP ${http.status} ${ms(http.durationMs)}` : 'HTTP ERROR'
  return (
    <span
      className={`inline-flex items-center gap-1 text-xs font-medium px-2 py-0.5 rounded-full ${
        ok ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
      }`}
    >
      {ok ? '✓' : '✗'} {label}
    </span>
  )
}

function GitProbeBadge({ label, result }: { label: string; result: GitProbeResult }) {
  const ok = result.status === 'ok'
  const detail = ok ? `${result.httpStatus} ${ms(result.durationMs)}` : (result.error ?? 'ERROR')
  return (
    <span
      className={`inline-flex items-center gap-1 text-xs font-medium px-2 py-0.5 rounded-full ${
        ok ? 'bg-green-100 text-green-800' : 'bg-red-100 text-red-800'
      }`}
    >
      {ok ? '✓' : '✗'} {label} {detail}
    </span>
  )
}

function formatSteps(steps: LogStep[]): string {
  return steps
    .map((s) => {
      const time = new Date(s.timestamp).toISOString().substring(11, 23) // HH:MM:SS.mmm
      const dur = s.durationMs != null ? ` (${s.durationMs}ms)` : ''
      const label = s.step.padEnd(12)
      return `[${time}] ${label} ${s.detail}${dur}`
    })
    .join('\n')
}

function DiagnosticLog({ steps }: { steps: LogStep[] }) {
  const [copied, setCopied] = useState(false)
  const textRef = useRef<HTMLPreElement>(null)

  function handleCopy() {
    navigator.clipboard.writeText(formatSteps(steps)).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }

  return (
    <div className="relative mt-2">
      <button
        onClick={handleCopy}
        title="Copy to clipboard"
        className="absolute top-2 right-2 text-xs px-2 py-0.5 rounded bg-slate-600 hover:bg-slate-500 text-slate-200 transition-colors"
      >
        {copied ? 'Copied!' : 'Copy'}
      </button>
      <pre
        ref={textRef}
        className="text-xs font-mono bg-slate-900 text-slate-200 rounded p-3 pr-20 overflow-x-auto whitespace-pre leading-5"
      >
        {formatSteps(steps)}
      </pre>
    </div>
  )
}

function ConnectivityRow({ result }: { name: string; result: ProviderConnectivity }) {
  const tcpOk = result.tcp.status === 'ok'
  const tlsOk = result.tls === null || result.tls.status === 'ok'

  return (
    <div className="border border-gray-200 rounded-lg p-4 space-y-2">
      {/* Header row */}
      <div className="flex items-start justify-between gap-2">
        <div className="flex flex-wrap gap-1.5 shrink-0">
          <TcpBadge tcp={result.tcp} />
          <TlsBadge tls={result.tls} />
          <HttpBadge http={result.http} />
        </div>
        <span className="text-xs text-gray-400 font-mono text-right break-all">{result.uri}</span>
      </div>
      {result.gitProbe && (
        <div className="flex flex-wrap gap-1.5">
          <GitProbeBadge label="fetch" result={result.gitProbe.uploadPack} />
          <GitProbeBadge label="push" result={result.gitProbe.receivePack} />
        </div>
      )}

      {/* Error details */}
      {!tcpOk && (
        <div className="text-xs font-mono bg-red-50 text-red-700 rounded px-2 py-1.5 space-y-0.5">
          <div>
            TCP {result.tcp.host}:{result.tcp.port} → <strong>{result.tcp.error}</strong> (
            {ms(result.tcp.durationMs)})
          </div>
          {result.tcp.detail && <div className="text-red-500">{result.tcp.detail}</div>}
        </div>
      )}
      {tcpOk && !tlsOk && result.tls && (
        <div className="text-xs font-mono bg-red-50 text-red-700 rounded px-2 py-1.5 space-y-0.5">
          <div>
            TLS → <strong>{result.tls.error}</strong> ({ms(result.tls.durationMs)})
          </div>
          {result.tls.detail && <div className="text-red-500">{result.tls.detail}</div>}
        </div>
      )}
      {result.http && typeof result.http.status === 'string' && (
        <div className="text-xs font-mono bg-red-50 text-red-700 rounded px-2 py-1.5">
          HTTP → ERROR ({ms(result.http.durationMs)})
          {result.http.detail && <div className="text-red-500">{result.http.detail}</div>}
        </div>
      )}
      {result.gitProbe &&
        (['uploadPack', 'receivePack'] as const)
          .filter((k) => result.gitProbe![k].status === 'error')
          .map((k) => {
            const r = result.gitProbe![k]
            const label = k === 'uploadPack' ? 'fetch (upload-pack)' : 'push (receive-pack)'
            return (
              <div
                key={k}
                className="text-xs font-mono bg-red-50 text-red-700 rounded px-2 py-1.5 space-y-0.5"
              >
                <div>
                  Git {label} → <strong>{r.error}</strong> ({ms(r.durationMs)})
                </div>
                {r.detail && <div className="text-red-500">{r.detail}</div>}
                {r.probeUrl && <div className="text-red-400 break-all">URL: {r.probeUrl}</div>}
              </div>
            )
          })}

      {/* Success details */}
      {tcpOk && tlsOk && result.tls && result.tls.status === 'ok' && (
        <div className="text-xs text-gray-400 font-mono">
          {result.tls.protocol} · {result.tls.cipher}
          {result.tls.peerCn && <span className="ml-2">· CN={result.tls.peerCn}</span>}
        </div>
      )}
      {result.http && typeof result.http.status === 'number' && result.http.location && (
        <div className="text-xs text-gray-400 font-mono">→ {result.http.location}</div>
      )}
      {result.gitProbe && (
        <>
          {result.gitProbe.uploadPack.status === 'ok' && result.gitProbe.uploadPack.contentType && (
            <div className="text-xs text-gray-400 font-mono">
              fetch content-type: {result.gitProbe.uploadPack.contentType}
            </div>
          )}
          {result.gitProbe.receivePack.status === 'ok' &&
            result.gitProbe.receivePack.contentType && (
              <div className="text-xs text-gray-400 font-mono">
                push content-type: {result.gitProbe.receivePack.contentType}
              </div>
            )}
        </>
      )}
      {result.steps && result.steps.length > 0 && <DiagnosticLog steps={result.steps} />}
    </div>
  )
}

export function Admin() {
  const [reloadStatus, setReloadStatus] = useState<'idle' | 'loading' | 'ok' | 'error'>('idle')
  const [reloadMessage, setReloadMessage] = useState<string | null>(null)

  const [connStatus, setConnStatus] = useState<'idle' | 'loading' | 'done' | 'error'>('idle')
  const [connCheckedAt, setConnCheckedAt] = useState<string | null>(null)
  const [connResults, setConnResults] = useState<Record<string, ProviderConnectivity> | null>(null)
  const [connError, setConnError] = useState<string | null>(null)

  // Targeted probe state
  const [providerList, setProviderList] = useState<Provider[]>([])
  const [selectedProvider, setSelectedProvider] = useState<string>('')
  const [repoPath, setRepoPath] = useState<string>('')
  const [targetStatus, setTargetStatus] = useState<'idle' | 'loading' | 'done' | 'error'>('idle')
  const [targetCheckedAt, setTargetCheckedAt] = useState<string | null>(null)
  const [targetResults, setTargetResults] = useState<Record<string, ProviderConnectivity> | null>(
    null,
  )
  const [targetError, setTargetError] = useState<string | null>(null)

  useEffect(() => {
    fetchProviders()
      .then((list: Provider[]) => {
        setProviderList(list)
        if (list.length > 0) setSelectedProvider(list[0].name)
      })
      .catch(console.error)
  }, [])

  async function handleReload() {
    setReloadStatus('loading')
    setReloadMessage(null)
    try {
      const result = await triggerConfigReload()
      setReloadMessage(result.message)
      setReloadStatus('ok')
    } catch (e) {
      setReloadMessage(e instanceof Error ? e.message : 'Unknown error')
      setReloadStatus('error')
    }
  }

  async function handleConnectivityCheck() {
    setConnStatus('loading')
    setConnResults(null)
    setConnCheckedAt(null)
    setConnError(null)
    try {
      const result = await checkConnectivity()
      setConnResults(result.providers)
      setConnCheckedAt(result.checkedAt)
      setConnStatus('done')
    } catch (e) {
      setConnError(e instanceof Error ? e.message : 'Unknown error')
      setConnStatus('error')
    }
  }

  async function handleTargetedCheck() {
    if (!selectedProvider) return
    setTargetStatus('loading')
    setTargetResults(null)
    setTargetCheckedAt(null)
    setTargetError(null)
    try {
      const result = await checkTargetedConnectivity(selectedProvider, repoPath)
      setTargetResults(result.providers)
      setTargetCheckedAt(result.checkedAt)
      setTargetStatus('done')
    } catch (e) {
      setTargetError(e instanceof Error ? e.message : 'Unknown error')
      setTargetStatus('error')
    }
  }

  return (
    <div className="max-w-2xl mx-auto px-6 py-8 space-y-6">
      <h1 className="text-2xl font-semibold text-gray-800">Admin</h1>

      <section className="bg-white rounded-lg shadow p-6 space-y-4">
        <div>
          <h2 className="text-lg font-medium text-gray-700">Configuration Reload</h2>
          <p className="text-sm text-gray-500 mt-1">
            Reloads hot-reloadable config (commit rules, auth settings) from the configured source
            without restarting the server. Provider, server, and database changes still require a
            restart.
          </p>
        </div>

        <div className="flex items-center gap-4">
          <button
            onClick={handleReload}
            disabled={reloadStatus === 'loading'}
            className="px-4 py-2 bg-slate-700 hover:bg-slate-600 disabled:opacity-50 text-white text-sm rounded transition-colors"
          >
            {reloadStatus === 'loading' ? 'Reloading…' : 'Reload config now'}
          </button>

          {reloadMessage && (
            <p
              className={
                'text-sm ' + (reloadStatus === 'error' ? 'text-red-600' : 'text-green-700')
              }
            >
              {reloadMessage}
            </p>
          )}
        </div>
      </section>

      <section className="bg-white rounded-lg shadow p-6 space-y-4">
        <div>
          <h2 className="text-lg font-medium text-gray-700">Provider Connectivity</h2>
          <p className="text-sm text-gray-500 mt-1">
            Tests outbound connectivity to each configured upstream provider: TCP handshake, TLS
            negotiation, and HTTP response. Error codes: REFUSED (RST received — port closed or
            firewall REJECT), TIMEOUT (no response — firewall DROP), RESET (connection torn down
            mid-stream). Full details logged at INFO level in{' '}
            <code className="font-mono">application.log</code>.
          </p>
        </div>

        <div className="flex items-center gap-4">
          <button
            onClick={handleConnectivityCheck}
            disabled={connStatus === 'loading'}
            className="px-4 py-2 bg-slate-700 hover:bg-slate-600 disabled:opacity-50 text-white text-sm rounded transition-colors"
          >
            {connStatus === 'loading' ? 'Checking…' : 'Run connectivity check'}
          </button>
          {connCheckedAt && (
            <span className="text-xs text-gray-400">
              checked at {new Date(connCheckedAt).toLocaleTimeString()}
            </span>
          )}
        </div>

        {connError && <p className="text-sm text-red-600">{connError}</p>}

        {connResults && (
          <div className="space-y-2">
            {Object.entries(connResults).map(([name, result]) => (
              <ConnectivityRow key={name} name={name} result={result} />
            ))}
          </div>
        )}
      </section>

      <section className="bg-white rounded-lg shadow p-6 space-y-4">
        <div>
          <h2 className="text-lg font-medium text-gray-700">Targeted Git Probe</h2>
          <p className="text-sm text-gray-500 mt-1">
            Runs the full connectivity check for a single provider, then sends{' '}
            <code className="font-mono">GET /info/refs?service=git-upload-pack</code> with{' '}
            <code className="font-mono">User-Agent: git/2.x.x</code> to a specific repo — the same
            request git makes at the start of a clone or fetch. Use this to detect CSAB or DLP
            appliances that pass generic HTTP but block git-specific URL patterns. Any HTTP response
            (200, 401, 403, 404) means the request reached the upstream; TIMEOUT or RESET indicates
            git-specific filtering.
          </p>
        </div>

        <div className="space-y-3">
          <div className="flex flex-col gap-3">
            <div className="flex flex-col gap-1 sm:w-48">
              <label className="text-xs font-medium text-gray-600">Provider</label>
              <select
                value={selectedProvider}
                onChange={(e) => setSelectedProvider(e.target.value)}
                className="border border-gray-300 rounded px-3 py-2 text-sm text-gray-800 bg-white focus:outline-none focus:ring-2 focus:ring-slate-400"
              >
                {providerList.map((p) => (
                  <option key={p.name} value={p.name}>
                    {p.name} ({p.host})
                  </option>
                ))}
              </select>
            </div>
            <div className="flex flex-col gap-1">
              <label className="text-xs font-medium text-gray-600">
                Repo path{' '}
                <span className="font-normal text-gray-400">
                  (optional — skips git probe if blank)
                </span>
              </label>
              <div className="flex items-stretch">
                <span className="inline-flex items-center px-3 rounded-l border border-r-0 border-gray-300 bg-gray-50 text-gray-400 text-xs font-mono select-none">
                  {providerList.find((p) => p.name === selectedProvider)?.uri ?? ''}
                </span>
                <input
                  type="text"
                  placeholder="/owner/repo.git"
                  value={repoPath}
                  onChange={(e) => setRepoPath(e.target.value)}
                  className="flex-1 border border-gray-300 rounded-r px-3 py-2 text-sm font-mono text-gray-800 focus:outline-none focus:ring-2 focus:ring-slate-400"
                />
              </div>
            </div>
          </div>

          <div className="flex items-center gap-4">
            <button
              onClick={handleTargetedCheck}
              disabled={targetStatus === 'loading' || !selectedProvider}
              className="px-4 py-2 bg-slate-700 hover:bg-slate-600 disabled:opacity-50 text-white text-sm rounded transition-colors"
            >
              {targetStatus === 'loading' ? 'Checking…' : 'Run targeted check'}
            </button>
            {targetCheckedAt && (
              <span className="text-xs text-gray-400">
                checked at {new Date(targetCheckedAt).toLocaleTimeString()}
              </span>
            )}
          </div>
        </div>

        {targetError && <p className="text-sm text-red-600">{targetError}</p>}

        {targetResults && (
          <div className="space-y-2">
            {Object.entries(targetResults).map(([name, result]) => (
              <ConnectivityRow key={name} name={name} result={result} />
            ))}
          </div>
        )}
      </section>
    </div>
  )
}
