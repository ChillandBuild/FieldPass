import { NavLink, Outlet } from 'react-router-dom'
import { FixtureBanner } from './FixtureBanner'
import { useSession } from '../hooks/useSession'

export function AppShell() {
  const { user, logout } = useSession()
  return (
    <>
      <FixtureBanner />
      <header className="app-header">
        <span className="brand">FieldPass Faculty</span>
        <nav className="app-nav" aria-label="Main">
          <NavLink to="/enrollments">Enrollments</NavLink>
          <NavLink to="/activities">Activities</NavLink>
          <NavLink to="/about">What this proves</NavLink>
        </nav>
        <div className="app-user">
          <span className="muted">{user?.full_name}</span>
          <button type="button" className="btn btn-small" onClick={logout}>Sign out</button>
        </div>
      </header>
      <main className="page">
        <Outlet />
      </main>
    </>
  )
}
