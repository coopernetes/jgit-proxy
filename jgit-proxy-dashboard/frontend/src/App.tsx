import { useEffect, useState } from 'react'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { fetchMe } from './api'
import { Nav } from './components/Nav'
import { Providers } from './pages/Providers'
import { PushDetail } from './pages/PushDetail'
import { PushList } from './pages/PushList'
import { Repos } from './pages/Repos'
import type { CurrentUser } from './types'

export default function App() {
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null)

  useEffect(() => {
    fetchMe().then(setCurrentUser).catch(console.error)
  }, [])

  return (
    <BrowserRouter>
      <div className="bg-gray-100 min-h-screen flex flex-col">
        <Nav currentUser={currentUser} />
        <main className="flex-1">
          <Routes>
            <Route path="/" element={<PushList />} />
            <Route path="/push/:id" element={<PushDetail currentUser={currentUser} />} />
            <Route path="/providers" element={<Providers />} />
            <Route path="/repos" element={<Repos />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  )
}
