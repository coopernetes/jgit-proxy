import { useEffect, useState } from 'react'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { fetchConfig, fetchMe } from './api'
import { Nav } from './components/Nav'
import { Admin } from './pages/Admin'
import { Providers } from './pages/Providers'
import { PushDetail } from './pages/PushDetail'
import { PushList } from './pages/PushList'
import { Profile } from './pages/Profile'
import { Repos } from './pages/Repos'
import { Users } from './pages/Users'
import { UserDetail } from './pages/UserDetail'
import type { CurrentUser } from './types'

export default function App() {
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null)
  const [authProvider, setAuthProvider] = useState<string>('local')

  useEffect(() => {
    fetchMe().then(setCurrentUser).catch(console.error)
    fetchConfig()
      .then((c) => setAuthProvider(c.authProvider))
      .catch(console.error)
  }, [])

  return (
    <BrowserRouter basename="/dashboard">
      <div className="bg-gray-100 min-h-screen flex flex-col">
        <Nav currentUser={currentUser} />
        <main className="flex-1">
          <Routes>
            <Route path="/" element={<PushList currentUser={currentUser} />} />
            <Route path="/push/:id" element={<PushDetail currentUser={currentUser} />} />
            <Route path="/providers" element={<Providers />} />
            <Route path="/repos" element={<Repos />} />
            <Route path="/profile" element={<Profile />} />
            <Route path="/users" element={<Users authProvider={authProvider} />} />
            <Route
              path="/users/:username"
              element={<UserDetail authProvider={authProvider} currentUser={currentUser} />}
            />
            <Route path="/admin" element={<Admin />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  )
}
