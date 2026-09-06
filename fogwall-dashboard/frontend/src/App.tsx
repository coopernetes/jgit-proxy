import { useEffect, useState } from 'react'
import { BrowserRouter, NavLink, Route, Routes } from 'react-router'
import { fetchConfig, fetchMe } from './api'
import { Nav } from './components/Nav'
import { useDarkMode } from './hooks/useDarkMode'
import { Admin } from './pages/Admin'
import { ScmApiActionList } from './pages/ScmApiActionList'
import { Providers } from './pages/Providers'
import { PushDetail } from './pages/PushDetail'
import { PushDiff } from './pages/PushDiff'
import { PushList } from './pages/PushList'
import { Profile } from './pages/Profile'
import { Repos } from './pages/Repos'
import { Setup } from './pages/Setup'
import { Groups } from './pages/Groups'
import { Legal } from './pages/Legal'
import { Users } from './pages/Users'
import { UserDetail } from './pages/UserDetail'
import type { CurrentUser } from './types'

export default function App() {
  const { dark, toggle: toggleDark } = useDarkMode()
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null)
  const [authProvider, setAuthProvider] = useState<string>('local')
  const [bulkReview, setBulkReview] = useState<boolean>(false)

  useEffect(() => {
    fetchMe().then(setCurrentUser).catch(console.error)
    fetchConfig()
      .then((c) => {
        setAuthProvider(c.authProvider)
        setBulkReview(c.bulkReview ?? false)
      })
      .catch(console.error)
  }, [])

  return (
    <BrowserRouter basename="/dashboard">
      <div className="bg-gray-100 dark:bg-slate-900 min-h-screen flex flex-col" data-hmr-test="1">
        <Nav currentUser={currentUser} dark={dark} toggleDark={toggleDark} />
        <main className="flex-1">
          <Routes>
            <Route
              path="/"
              element={<PushList currentUser={currentUser} bulkReviewEnabled={bulkReview} />}
            />
            <Route
              path="/push/:id"
              element={<PushDetail currentUser={currentUser} dark={dark} />}
            />
            <Route path="/push/:id/diff" element={<PushDiff dark={dark} />} />
            <Route path="/providers" element={<Providers />} />
            <Route path="/setup" element={<Setup />} />
            <Route path="/repos" element={<Repos />} />
            <Route
              path="/scm-api-actions"
              element={<ScmApiActionList currentUser={currentUser} />}
            />
            <Route path="/profile" element={<Profile />} />
            <Route path="/users" element={<Users authProvider={authProvider} />} />
            <Route
              path="/users/:username"
              element={<UserDetail authProvider={authProvider} currentUser={currentUser} />}
            />
            <Route path="/groups" element={<Groups />} />
            <Route path="/admin" element={<Admin />} />
            <Route path="/legal" element={<Legal />} />
          </Routes>
        </main>
        <footer className="bg-slate-800 dark:bg-slate-950 text-slate-500 text-xs px-6 py-3 flex items-center justify-between">
          <span>
            &copy; 2024&ndash;{new Date().getFullYear()} fogwall contributors &middot;{' '}
            <NavLink to="/legal" className="hover:text-slate-300 transition-colors">
              Legal
            </NavLink>
          </span>
          <span className="flex items-center gap-3">
            <a
              href="https://github.com/RBC/fogwall/blob/main/LICENSE"
              target="_blank"
              rel="noopener noreferrer"
              className="hover:text-slate-300 transition-colors"
            >
              Apache-2.0
            </a>
            <a
              href="https://github.com/RBC/fogwall"
              target="_blank"
              rel="noopener noreferrer"
              className="hover:text-slate-300 transition-colors flex items-center gap-1"
              title="View on GitHub"
            >
              <svg
                className="h-3.5 w-3.5"
                fill="currentColor"
                viewBox="0 0 16 16"
                aria-hidden="true"
              >
                <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8z" />
              </svg>
              Source
            </a>
          </span>
        </footer>
      </div>
    </BrowserRouter>
  )
}
