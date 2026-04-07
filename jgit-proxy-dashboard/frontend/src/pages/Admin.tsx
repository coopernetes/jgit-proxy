import { useState } from 'react'
import { triggerConfigReload } from '../api'

export function Admin() {
  const [reloadStatus, setReloadStatus] = useState<'idle' | 'loading' | 'ok' | 'error'>('idle')
  const [reloadMessage, setReloadMessage] = useState<string | null>(null)

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
    </div>
  )
}
