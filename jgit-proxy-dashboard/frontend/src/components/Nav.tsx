import { NavLink } from 'react-router-dom';
import type { CurrentUser } from '../types';

interface NavProps {
  currentUser: CurrentUser | null;
}

export function Nav({ currentUser }: NavProps) {
  return (
    <header className="bg-slate-800 text-white px-6 py-4 flex items-center gap-4 shadow">
      <NavLink
        to="/"
        className="text-xl font-semibold tracking-wide shrink-0 hover:text-slate-200"
      >
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
          <span>{currentUser.username}</span>
          <form method="post" action="/logout" className="m-0">
            <button
              type="submit"
              className="px-2 py-1 rounded text-xs bg-slate-700 hover:bg-slate-600 text-slate-300 hover:text-white transition-colors"
            >
              Sign out
            </button>
          </form>
        </div>
      )}
    </header>
  );
}
