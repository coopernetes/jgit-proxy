import { useEffect, useRef, useState } from 'react'
import { createAccessRule, deleteAccessRule, fetchProviders } from '../api'

interface ActiveRepo {
  provider: string
  owner: string
  repoName: string
  pushCount: number
  fetchCount: number
  blockedFetchCount: number
}

interface AccessRule {
  id: string
  provider: string | null
  slug: string | null
  owner: string | null
  name: string | null
  access: 'ALLOW' | 'DENY'
  operations: 'FETCH' | 'PUSH' | 'ALL'
  description: string | null
  enabled: boolean
  ruleOrder: number
  source: 'CONFIG' | 'DB'
}

function CloneButton({ cloneUrl }: { cloneUrl: string }) {
  const [open, setOpen] = useState(false)
  const [copied, setCopied] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [open])

  const copy = () => {
    navigator.clipboard.writeText(`git clone ${cloneUrl}`)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold rounded border border-green-600 text-green-700 bg-green-50 hover:bg-green-100 transition-colors"
      >
        <svg className="w-3.5 h-3.5" viewBox="0 0 16 16" fill="currentColor">
          <path d="M2 2.5A2.5 2.5 0 0 1 4.5 0h7A2.5 2.5 0 0 1 14 2.5v10.528c0 .3-.05.654-.272.moorings M4.5 1A1.5 1.5 0 0 0 3 2.5v9.539l1.446-1.085a.749.749 0 0 1 .85-.006L7 12.266l1.704-1.318a.749.749 0 0 1 .85.006L11 12.039V2.5A1.5 1.5 0 0 0 9.5 1Z" />
        </svg>
        Code
        <svg className="w-3 h-3" viewBox="0 0 16 16" fill="currentColor">
          <path d="M4.427 7.427l3.396 3.396a.25.25 0 0 0 .354 0l3.396-3.396A.25.25 0 0 0 11.396 7H4.604a.25.25 0 0 0-.177.427Z" />
        </svg>
      </button>

      {open && (
        <div className="absolute right-0 mt-1 w-80 bg-white border border-gray-200 rounded-lg shadow-lg z-10 p-4">
          <div className="flex items-center gap-1.5 mb-2">
            <svg className="w-3.5 h-3.5 text-gray-500" viewBox="0 0 16 16" fill="currentColor">
              <path d="M0 2.75C0 1.784.784 1 1.75 1h12.5c.966 0 1.75.784 1.75 1.75v10.5A1.75 1.75 0 0 1 14.25 15H1.75A1.75 1.75 0 0 1 0 13.25Zm1.75-.25a.25.25 0 0 0-.25.25v10.5c0 .138.112.25.25.25h12.5a.25.25 0 0 0 .25-.25V2.75a.25.25 0 0 0-.25-.25ZM7.25 8a.75.75 0 0 1-.22.53l-2.25 2.25a.75.75 0 1 1-1.06-1.06L5.44 8 3.72 6.28a.75.75 0 1 1 1.06-1.06l2.25 2.25c.141.14.22.331.22.53Zm1.5 1.5h3a.75.75 0 0 1 0 1.5h-3a.75.75 0 0 1 0-1.5Z" />
            </svg>
            <span className="text-xs font-semibold text-gray-700">Clone via proxy</span>
          </div>
          <div className="flex items-center gap-2 px-2 py-1.5 border border-gray-300 rounded font-mono text-xs text-gray-700 bg-gray-50">
            <span className="flex-1 truncate">{cloneUrl}</span>
            <button
              onClick={copy}
              className="shrink-0 text-gray-400 hover:text-gray-700 transition-colors"
            >
              {copied ? (
                <svg className="w-4 h-4 text-green-600" viewBox="0 0 16 16" fill="currentColor">
                  <path d="M13.78 4.22a.75.75 0 0 1 0 1.06l-7.25 7.25a.75.75 0 0 1-1.06 0L2.22 9.28a.751.751 0 0 1 .018-1.042.751.751 0 0 1 1.042-.018L6 10.94l6.72-6.72a.75.75 0 0 1 1.06 0Z" />
                </svg>
              ) : (
                <svg className="w-4 h-4" viewBox="0 0 16 16" fill="currentColor">
                  <path d="M0 6.75C0 5.784.784 5 1.75 5h1.5a.75.75 0 0 1 0 1.5h-1.5a.25.25 0 0 0-.25.25v7.5c0 .138.112.25.25.25h7.5a.25.25 0 0 0 .25-.25v-1.5a.75.75 0 0 1 1.5 0v1.5A1.75 1.75 0 0 1 9.25 16h-7.5A1.75 1.75 0 0 1 0 14.25Z" />
                  <path d="M5 1.75C5 .784 5.784 0 6.75 0h7.5C15.216 0 16 .784 16 1.75v7.5A1.75 1.75 0 0 1 14.25 11h-7.5A1.75 1.75 0 0 1 5 9.25Zm1.75-.25a.25.25 0 0 0-.25.25v7.5c0 .138.112.25.25.25h7.5a.25.25 0 0 0 .25-.25v-7.5a.25.25 0 0 0-.25-.25Z" />
                </svg>
              )}
            </button>
          </div>
          <p className="mt-2 text-xs text-gray-400">Use Git or run this in your terminal 👍</p>
        </div>
      )}
    </div>
  )
}

type Tab = 'active' | 'rules'

type TargetType = 'slug' | 'owner' | 'name'

type PatternType = 'LITERAL' | 'GLOB' | 'REGEX'

interface AddRuleForm {
  access: 'ALLOW' | 'DENY'
  targetType: TargetType
  patternType: PatternType
  pattern: string
  provider: string
  operations: 'ALL' | 'PUSH' | 'FETCH'
}

const DEFAULT_FORM: AddRuleForm = {
  access: 'ALLOW',
  targetType: 'slug',
  patternType: 'LITERAL',
  pattern: '',
  provider: '',
  operations: 'ALL',
}

function AddRuleModal({
  onClose,
  onCreated,
}: {
  onClose: () => void
  onCreated: (rule: AccessRule) => void
}) {
  const [form, setForm] = useState<AddRuleForm>(DEFAULT_FORM)
  const [error, setError] = useState<string | null>(null)
  const [regexError, setRegexError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [providerNames, setProviderNames] = useState<string[]>([])

  useEffect(() => {
    fetchProviders()
      .then((data: { name: string }[]) => setProviderNames(data.map((p) => p.name)))
      .catch(() => {})
  }, [])

  const set = <K extends keyof AddRuleForm>(key: K, value: AddRuleForm[K]) =>
    setForm((f) => ({ ...f, [key]: value }))

  function handlePatternChange(value: string) {
    set('pattern', value)
    if (form.patternType === 'REGEX') {
      try {
        new RegExp(value)
        setRegexError(null)
      } catch (e) {
        setRegexError(e instanceof SyntaxError ? e.message : 'Invalid regex')
      }
    }
  }

  function handlePatternTypeChange(value: PatternType) {
    set('patternType', value)
    if (value === 'REGEX' && form.pattern) {
      try {
        new RegExp(form.pattern)
        setRegexError(null)
      } catch (e) {
        setRegexError(e instanceof SyntaxError ? e.message : 'Invalid regex')
      }
    } else {
      setRegexError(null)
    }
  }

  const handleSubmit = async () => {
    if (!form.pattern.trim()) {
      setError('Pattern is required')
      return
    }
    if (form.patternType === 'REGEX' && regexError) {
      setError('Fix the regex error before saving')
      return
    }
    setSubmitting(true)
    setError(null)
    try {
      // Encode the pattern as the filter expects: regex: prefix for REGEX,
      // raw string for GLOB (glob chars trigger detection), raw for LITERAL.
      const raw = form.pattern.trim()
      const encoded = form.patternType === 'REGEX' ? `regex:${raw}` : raw

      const payload: Parameters<typeof createAccessRule>[0] = {
        access: form.access,
        operations: form.operations,
        provider: form.provider || undefined,
      }
      if (form.targetType === 'slug') payload.slug = encoded
      else if (form.targetType === 'owner') payload.owner = encoded
      else payload.name = encoded

      const created = await createAccessRule(payload)
      onCreated(created)
      onClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Unknown error')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-md p-6 space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="text-lg font-semibold text-gray-800">Add Access Rule</h3>
          <button
            onClick={onClose}
            className="text-gray-400 hover:text-gray-600 text-xl leading-none"
          >
            ×
          </button>
        </div>

        <div className="space-y-3">
          {/* Access type */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Access type</label>
            <div className="flex gap-3">
              {(['ALLOW', 'DENY'] as const).map((a) => (
                <label key={a} className="flex items-center gap-1.5 cursor-pointer">
                  <input
                    type="radio"
                    name="access"
                    checked={form.access === a}
                    onChange={() => set('access', a)}
                    className="accent-blue-600"
                  />
                  <span
                    className={`text-sm font-medium ${a === 'ALLOW' ? 'text-green-700' : 'text-red-700'}`}
                  >
                    {a}
                  </span>
                </label>
              ))}
            </div>
          </div>

          {/* Match target */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Match by</label>
            <select
              value={form.targetType}
              onChange={(e) => set('targetType', e.target.value as TargetType)}
              className="w-full border border-gray-300 rounded px-3 py-1.5 text-sm"
            >
              <option value="slug">Slug (owner/repo)</option>
              <option value="owner">Owner / org</option>
              <option value="name">Repository name</option>
            </select>
          </div>

          {/* Pattern type + Pattern */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Match type</label>
            <select
              value={form.patternType}
              onChange={(e) => handlePatternTypeChange(e.target.value as PatternType)}
              className="w-full border border-gray-300 rounded px-3 py-1.5 text-sm"
            >
              <option value="LITERAL">Literal — exact match</option>
              <option value="GLOB">Glob — wildcard (* / **)</option>
              <option value="REGEX">Regex — Java regular expression</option>
            </select>
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Pattern</label>
            <input
              type="text"
              value={form.pattern}
              onChange={(e) => handlePatternChange(e.target.value)}
              placeholder={
                form.patternType === 'REGEX'
                  ? form.targetType === 'slug'
                    ? '^myorg/.*'
                    : form.targetType === 'owner'
                      ? '^(myorg|partnerorg)$'
                      : '^my-service-.*'
                  : form.patternType === 'GLOB'
                    ? form.targetType === 'slug'
                      ? 'myorg/*'
                      : form.targetType === 'owner'
                        ? 'myorg-*'
                        : 'feature-*'
                    : form.targetType === 'slug'
                      ? 'myorg/myrepo'
                      : form.targetType === 'owner'
                        ? 'myorg'
                        : 'myrepo'
              }
              className={`w-full border rounded px-3 py-1.5 text-sm font-mono ${
                regexError ? 'border-amber-400' : 'border-gray-300'
              }`}
            />
            {regexError && <p className="mt-1 text-xs text-amber-600">⚠ {regexError}</p>}
            {form.patternType === 'REGEX' && !regexError && form.pattern && (
              <p className="mt-1 text-xs text-gray-400">
                Stored as <code className="font-mono">regex:{form.pattern}</code>
              </p>
            )}
          </div>

          {/* Provider */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Provider</label>
            <select
              value={form.provider}
              onChange={(e) => set('provider', e.target.value)}
              className="w-full border border-gray-300 rounded px-3 py-1.5 text-sm"
            >
              <option value="">— All providers (applies to any) —</option>
              {providerNames.map((name) => (
                <option key={name} value={name}>
                  {name}
                </option>
              ))}
            </select>
          </div>

          {/* Operations */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Operations</label>
            <select
              value={form.operations}
              onChange={(e) => set('operations', e.target.value as AddRuleForm['operations'])}
              className="w-full border border-gray-300 rounded px-3 py-1.5 text-sm"
            >
              <option value="ALL">Push &amp; Fetch</option>
              <option value="PUSH">Push only</option>
              <option value="FETCH">Fetch only</option>
            </select>
          </div>
        </div>

        {error && <p className="text-sm text-red-600">{error}</p>}

        <div className="flex justify-end gap-2 pt-1">
          <button
            onClick={onClose}
            className="px-4 py-1.5 text-sm border border-gray-300 rounded hover:bg-gray-50"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={submitting}
            className={`px-4 py-1.5 text-sm text-white rounded disabled:opacity-50 transition-colors ${
              form.access === 'DENY'
                ? 'bg-red-600 hover:bg-red-500'
                : 'bg-blue-600 hover:bg-blue-500'
            }`}
          >
            {submitting ? 'Saving…' : `Add ${form.access} rule`}
          </button>
        </div>
      </div>
    </div>
  )
}

export function Repos() {
  const [tab, setTab] = useState<Tab>('active')
  const [activeRepos, setActiveRepos] = useState<ActiveRepo[]>([])
  const [rules, setRules] = useState<AccessRule[]>([])
  const [loadedTab, setLoadedTab] = useState<Tab | null>(null)
  const [showAddRule, setShowAddRule] = useState(false)

  const loading = loadedTab !== tab

  useEffect(() => {
    fetch(tab === 'active' ? '/api/repos/active' : '/api/repos/rules')
      .then((r) => r.json())
      .then((data) => {
        if (tab === 'active') setActiveRepos(data)
        else setRules(data)
        setLoadedTab(tab)
      })
  }, [tab])

  const deleteRule = async (id: string) => {
    await deleteAccessRule(id)
    setRules((prev) => prev.filter((r) => r.id !== id))
  }

  return (
    <div className="max-w-5xl mx-auto px-4 py-6 space-y-4">
      {showAddRule && (
        <AddRuleModal
          onClose={() => setShowAddRule(false)}
          onCreated={(rule) => setRules((prev) => [...prev, rule])}
        />
      )}

      <div className="flex items-baseline gap-3">
        <h2 className="text-lg font-semibold text-gray-800">Repositories</h2>
      </div>

      {/* Tabs */}
      <div className="flex items-center justify-between border-b border-gray-200">
        <div className="flex gap-2">
          {(['active', 'rules'] as Tab[]).map((t) => (
            <button
              key={t}
              onClick={() => setTab(t)}
              className={`px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors ${
                tab === t
                  ? 'border-blue-600 text-blue-700'
                  : 'border-transparent text-gray-500 hover:text-gray-700'
              }`}
            >
              {t === 'active' ? 'Active' : 'Access Rules'}
            </button>
          ))}
        </div>
        {tab === 'rules' && (
          <button
            onClick={() => setShowAddRule(true)}
            className="mb-px px-3 py-1.5 text-sm bg-blue-600 hover:bg-blue-500 text-white rounded transition-colors"
          >
            + Add rule
          </button>
        )}
      </div>

      {loading && <div className="text-sm text-gray-400">Loading…</div>}

      {/* Active repos tab */}
      {!loading && tab === 'active' && (
        <>
          {activeRepos.length === 0 ? (
            <p className="text-sm text-gray-400">
              No repo activity recorded yet. Push or fetch through the proxy to populate this view.
            </p>
          ) : (
            <div className="space-y-3">
              {activeRepos.map((repo) => (
                <div
                  key={`${repo.provider}/${repo.owner}/${repo.repoName}`}
                  className="bg-white rounded-lg shadow border border-gray-200 px-6 py-4 flex items-center justify-between"
                >
                  <div>
                    <div className="text-xs text-gray-400 mb-0.5">{repo.provider}</div>
                    <div className="font-semibold text-gray-800">
                      {repo.owner}/{repo.repoName}
                    </div>
                  </div>
                  <div className="flex items-center gap-6">
                    <div className="flex gap-6 text-sm text-gray-600">
                      <div className="text-center">
                        <div className="font-semibold text-gray-900">{repo.pushCount}</div>
                        <div className="text-xs text-gray-400">pushes</div>
                      </div>
                      <div className="text-center">
                        <div className="font-semibold text-gray-900">{repo.fetchCount}</div>
                        <div className="text-xs text-gray-400">fetches</div>
                      </div>
                      {repo.blockedFetchCount > 0 && (
                        <div className="text-center">
                          <div className="font-semibold text-red-600">{repo.blockedFetchCount}</div>
                          <div className="text-xs text-red-400">blocked</div>
                        </div>
                      )}
                    </div>
                    <CloneButton
                      cloneUrl={`${window.location.origin}/proxy/${repo.provider}/${repo.owner}/${repo.repoName}.git`}
                    />
                  </div>
                </div>
              ))}
            </div>
          )}
        </>
      )}

      {/* Access rules tab */}
      {!loading && tab === 'rules' && (
        <>
          {rules.length === 0 ? (
            <p className="text-sm text-gray-400">No access rules configured.</p>
          ) : (
            <div className="space-y-2">
              {rules.map((rule) => (
                <div
                  key={rule.id}
                  className="bg-white rounded-lg border border-gray-200 px-5 py-3 flex items-center justify-between"
                >
                  <div className="flex items-center gap-3 min-w-0">
                    <span
                      className={`text-xs px-2 py-0.5 rounded-full font-medium shrink-0 ${
                        rule.access === 'ALLOW'
                          ? 'bg-green-100 text-green-800 border border-green-300'
                          : 'bg-red-100 text-red-800 border border-red-300'
                      }`}
                    >
                      {rule.access}
                    </span>
                    <div className="flex flex-col min-w-0">
                      <div className="flex items-center gap-1.5">
                        <span className="text-xs text-gray-400 shrink-0">
                          {rule.slug ? 'slug' : rule.owner ? 'owner' : rule.name ? 'name' : 'any'}:
                        </span>
                        <span className="font-mono text-sm text-gray-800 truncate">
                          {rule.slug ?? rule.owner ?? rule.name ?? '*'}
                        </span>
                      </div>
                      <div className="flex items-center gap-2 mt-0.5">
                        <span className="text-xs text-gray-400">
                          provider: <span className="text-gray-600">{rule.provider ?? 'all'}</span>
                        </span>
                        {rule.description && (
                          <span className="text-xs text-gray-400">— {rule.description}</span>
                        )}
                      </div>
                    </div>
                  </div>
                  <div className="flex flex-col items-end gap-1 shrink-0">
                    <span
                      className="text-xs px-2 py-0.5 rounded bg-gray-100 text-gray-600 font-mono border border-gray-200 cursor-help"
                      title={
                        rule.operations === 'FETCH'
                          ? 'git clone / git fetch'
                          : rule.operations === 'PUSH'
                            ? 'git push'
                            : 'git push / git clone'
                      }
                    >
                      {rule.operations === 'ALL' ? 'PUSH & FETCH' : rule.operations}
                    </span>
                    <span className="text-xs text-gray-400">
                      {rule.source === 'CONFIG' ? 'config' : 'local'}
                    </span>
                    {!rule.enabled && <span className="text-xs text-amber-500">disabled</span>}
                    {rule.source === 'DB' && (
                      <button
                        onClick={() => deleteRule(rule.id)}
                        className="text-xs text-red-500 hover:text-red-700"
                      >
                        Delete
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  )
}
