// Stub page — repository list is not yet backed by an API.
// Hardcoded entries mirror the Alpine.js SPA placeholder.
const REPOS = [
  {
    url: 'https://github.com/coopernetes/test-repo',
    path: 'coopernetes/test-repo',
    providerName: 'GitHub',
    providerHost: 'github.com',
    description: 'Test repository used for jgit-proxy integration and e2e testing.',
    approved: true,
  },
  {
    url: 'https://github.com/finos/git-proxy',
    path: 'finos/git-proxy',
    providerName: 'GitHub',
    providerHost: 'github.com',
    description: 'FINOS git-proxy — Node.js reference implementation this project is porting.',
    approved: false,
  },
];

export function Repos() {
  return (
    <div className="max-w-5xl mx-auto px-4 py-6 space-y-4">
      <div className="flex items-baseline gap-3">
        <h2 className="text-lg font-semibold text-gray-800">Repositories</h2>
        <span className="text-xs text-gray-400">Repos visible through this proxy</span>
      </div>

      {REPOS.map(repo => (
        <div key={repo.url} className="bg-white rounded-lg shadow border border-gray-200 px-6 py-4">
          <div className="flex items-start gap-4">
            <div className="flex-1">
              <div className="flex items-center gap-2 mb-1">
                <img
                  src={`https://${repo.providerHost}/favicon.ico`}
                  className="w-4 h-4 rounded"
                  alt=""
                  onError={e => (e.currentTarget.style.display = 'none')}
                />
                <span className="text-xs font-medium text-gray-500">{repo.providerName}</span>
              </div>
              <a
                href={repo.url}
                target="_blank"
                rel="noopener noreferrer"
                className="font-semibold text-blue-700 hover:underline text-base"
              >
                {repo.url}
              </a>
              <div className="text-sm text-gray-500 mt-1">{repo.description}</div>
            </div>
            <div className="text-right shrink-0">
              <span
                className={`text-xs px-2 py-0.5 rounded-full ${
                  repo.approved
                    ? 'bg-green-100 text-green-800 border border-green-300'
                    : 'bg-amber-100 text-amber-800 border border-amber-300'
                }`}
              >
                {repo.approved ? 'Approved' : 'Pending approval'}
              </span>
              <div className="mt-2 text-xs text-gray-400">
                Clone via proxy:
                <div className="font-mono text-xs text-gray-600 mt-0.5">
                  {window.location.origin}/push/{repo.providerHost}/{repo.path}.git
                </div>
              </div>
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}
