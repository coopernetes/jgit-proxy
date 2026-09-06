import { NavLink } from 'react-router'
import type { CurrentUser } from '../types'

interface NavProps {
  currentUser: CurrentUser | null
  dark: boolean
  toggleDark: () => void
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

export function Nav({ currentUser, dark, toggleDark }: NavProps) {
  return (
    <header className="bg-slate-800 text-white px-6 py-4 flex items-center gap-4 shadow">
      <NavLink
        to="/"
        className="flex items-center gap-2 shrink-0 hover:opacity-80 transition-opacity"
      >
        <svg
          className="h-8 w-auto"
          viewBox="0 0 28 32"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
          aria-hidden="true"
        >
          {/* Shield outline */}
          <path
            d="M14 1 L26 6 L26 16 C26 23 14 31 14 31 C14 31 2 23 2 16 L2 6 Z"
            stroke="white"
            strokeWidth="1.8"
            strokeLinejoin="round"
            fill="none"
            opacity="0.85"
          />
          {/* Waves inside shield */}
          <path
            d="M7 10 Q10 8 14 10 Q18 12 21 10"
            stroke="white"
            strokeWidth="2"
            strokeLinecap="round"
            fill="none"
            opacity="0.9"
          />
          <path
            d="M7 15 Q10 13 14 15 Q18 17 21 15"
            stroke="white"
            strokeWidth="2"
            strokeLinecap="round"
            fill="none"
            opacity="0.7"
          />
          <path
            d="M8 20 Q11 18 14 20 Q17 22 20 20"
            stroke="white"
            strokeWidth="2"
            strokeLinecap="round"
            fill="none"
            opacity="0.5"
          />
        </svg>
        <span className="text-white font-semibold tracking-tight text-xl">fogwall</span>
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
          Pushes
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
          Repos
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
          to="/scm-api-actions"
          className={({ isActive }) =>
            'px-3 py-1 rounded text-sm transition-colors ' +
            (isActive
              ? 'bg-slate-600 text-white'
              : 'text-slate-300 hover:text-white hover:bg-slate-700')
          }
        >
          SCM API
        </NavLink>
        {currentUser?.authorities.includes('ROLE_ADMIN') && (
          <>
            <NavLink
              to="/users"
              className={({ isActive }) =>
                'px-3 py-1 rounded text-sm transition-colors ' +
                (isActive
                  ? 'bg-slate-600 text-white'
                  : 'text-slate-300 hover:text-white hover:bg-slate-700')
              }
            >
              Users
            </NavLink>
            <NavLink
              to="/groups"
              className={({ isActive }) =>
                'px-3 py-1 rounded text-sm transition-colors ' +
                (isActive
                  ? 'bg-slate-600 text-white'
                  : 'text-slate-300 hover:text-white hover:bg-slate-700')
              }
            >
              Groups
            </NavLink>
            <NavLink
              to="/admin"
              className={({ isActive }) =>
                'px-3 py-1 rounded text-sm transition-colors ' +
                (isActive
                  ? 'bg-slate-600 text-white'
                  : 'text-slate-300 hover:text-white hover:bg-slate-700')
              }
            >
              Admin
            </NavLink>
          </>
        )}
      </nav>

      <div className="ml-auto flex items-center gap-3 text-sm text-slate-300">
        {/* Public setup / quick-start guide — a help icon rather than a top-level tab, reachable by everyone. */}
        <NavLink
          to="/setup"
          className={({ isActive }) =>
            'transition-colors ' + (isActive ? 'text-white' : 'text-slate-400 hover:text-white')
          }
          title="Setup & quick start"
          aria-label="Setup and quick start"
        >
          <svg
            className="h-4 w-4"
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={2}
            aria-hidden="true"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M8.228 9c.549-1.165 2.03-2 3.772-2 2.21 0 4 1.343 4 3 0 1.4-1.278 2.575-3.006 2.907-.542.104-.994.54-.994 1.093m0 3h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
            />
          </svg>
        </NavLink>
        <button
          onClick={toggleDark}
          className="text-slate-400 hover:text-white transition-colors"
          title={dark ? 'Switch to light mode' : 'Switch to dark mode'}
          aria-label={dark ? 'Switch to light mode' : 'Switch to dark mode'}
        >
          {dark ? (
            <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
              <path
                fillRule="evenodd"
                d="M10 2a1 1 0 011 1v1a1 1 0 11-2 0V3a1 1 0 011-1zm4 8a4 4 0 11-8 0 4 4 0 018 0zm-.464 4.95l.707.707a1 1 0 001.414-1.414l-.707-.707a1 1 0 00-1.414 1.414zm2.12-10.607a1 1 0 010 1.414l-.706.707a1 1 0 11-1.414-1.414l.707-.707a1 1 0 011.414 0zM17 11a1 1 0 100-2h-1a1 1 0 100 2h1zm-7 4a1 1 0 011 1v1a1 1 0 11-2 0v-1a1 1 0 011-1zM5.05 6.464A1 1 0 106.465 5.05l-.708-.707a1 1 0 00-1.414 1.414l.707.707zm1.414 8.486l-.707.707a1 1 0 01-1.414-1.414l.707-.707a1 1 0 011.414 1.414zM4 11a1 1 0 100-2H3a1 1 0 000 2h1z"
                clipRule="evenodd"
              />
            </svg>
          ) : (
            <svg className="h-4 w-4" fill="currentColor" viewBox="0 0 20 20">
              <path d="M17.293 13.293A8 8 0 016.707 2.707a8.001 8.001 0 1010.586 10.586z" />
            </svg>
          )}
        </button>
        <a
          href="https://github.com/RBC/fogwall/blob/main/docs/CONFIGURATION.md"
          target="_blank"
          rel="noopener noreferrer"
          className="text-slate-400 hover:text-white transition-colors"
          title="Documentation"
        >
          Docs
        </a>
        {currentUser && (
          <>
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
          </>
        )}
      </div>
    </header>
  )
}
