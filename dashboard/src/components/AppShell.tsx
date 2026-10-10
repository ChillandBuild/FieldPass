import { NavLink, Outlet } from 'react-router-dom'
import { FixtureBanner } from './FixtureBanner'
import { useSession } from '../hooks/useSession'

export function AppShell() {
  const { user, logout } = useSession()
  return (
    <>
      <FixtureBanner />
      <a className="skip-link" href="#main-content">Skip to content</a>
      <header className="app-header">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">f</span>
          <span>FieldPass<span className="brand-role">Faculty</span></span>
        </div>
        <nav className="app-nav" aria-label="Main">
          <NavLink to="/enrollments">Enrollments</NavLink>
          <NavLink to="/activities">Activities</NavLink>
          <NavLink to="/students">Students</NavLink>
          <NavLink to="/about">What this proves</NavLink>
        </nav>
        <div className="app-user">
          <span className="user-identity"><span className="user-avatar" aria-hidden="true">{user?.full_name.charAt(0)}</span>{user?.full_name}</span>
          <button type="button" className="btn btn-small" onClick={logout}>Sign out</button>
        </div>
      </header>
      <main className="page" id="main-content" tabIndex={-1}>
        <Outlet />
      </main>
    </>
  )
}
