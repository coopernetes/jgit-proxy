import { NavLink } from 'react-router-dom'
import type { CurrentUser } from '../types'

interface NavProps {
  currentUser: CurrentUser | null
}

function getCsrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return match ? decodeURIComponent(match[1]) : null
}

async function logout() {
  const token = getCsrfToken()
  await fetch('/logout', {
    method: 'POST',
    headers: token ? { 'X-XSRF-TOKEN': token } : {},
    credentials: 'same-origin',
  })
  window.location.href = '/login.html?logout'
}

export function Nav({ currentUser }: NavProps) {
  return (
    <header className="bg-slate-800 text-white px-6 py-4 flex items-center gap-4 shadow">
      <NavLink to="/" className="text-xl font-semibold tracking-wide shrink-0 hover:text-slate-200">
        &#9889; git-proxy
      </NavLink>

      <nav className="flex gap-1 ml-2">
        <NavLink
          to="/"
          end
          className={({ isActive }) =>
            'px-3 py-1 rounded text-sm transition-colors ' +
            (isActive
              ? 'bg-slate-600 text-white'
              : 'text-slate-300 hover:text-white hover:bg-slate-700')
          }
        >
          Push Records
        </NavLink>
        <NavLink
          to="/providers"
          className={({ isActive }) =>
            'px-3 py-1 rounded text-sm transition-colors ' +
            (isActive
              ? 'bg-slate-600 text-white'
              : 'text-slate-300 hover:text-white hover:bg-slate-700')
          }
        >
          Providers
        </NavLink>
        <NavLink
          to="/repos"
          className={({ isActive }) =>
            'px-3 py-1 rounded text-sm transition-colors ' +
            (isActive
              ? 'bg-slate-600 text-white'
              : 'text-slate-300 hover:text-white hover:bg-slate-700')
          }
        >
          Repositories
        </NavLink>
      </nav>

      {currentUser && (
        <div className="ml-auto flex items-center gap-3 text-sm text-slate-300">
          <NavLink
            to="/profile"
            className={({ isActive }) =>
              'transition-colors ' + (isActive ? 'text-white' : 'hover:text-white')
            }
          >
            {currentUser.username}
          </NavLink>
          <button
            onClick={logout}
            className="px-2 py-1 rounded text-xs bg-slate-700 hover:bg-slate-600 text-slate-300 hover:text-white transition-colors"
          >
            Sign out
          </button>
        </div>
      )}
    </header>
  )
}
