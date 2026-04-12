import { useState } from 'react'
import {
  type ProviderConnectivity,
  type TcpResult,
  type TlsResult,
  type HttpResult,
  checkConnectivity,
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

function ConnectivityRow({ name, result }: { name: string; result: ProviderConnectivity }) {
  const tcpOk = result.tcp.status === 'ok'
  const tlsOk = result.tls === null || result.tls.status === 'ok'

  return (
    <div className="border border-gray-200 rounded-lg p-4 space-y-2">
      {/* Header row */}
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <span className="font-medium text-gray-800 text-sm">{name}</span>
          <span className="ml-2 text-xs text-gray-400 font-mono break-all">{result.uri}</span>
        </div>
        <div className="flex flex-wrap gap-1.5 shrink-0">
          <TcpBadge tcp={result.tcp} />
          <TlsBadge tls={result.tls} />
          <HttpBadge http={result.http} />
        </div>
      </div>

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
    </div>
  )
}
