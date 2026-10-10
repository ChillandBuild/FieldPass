import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, UNAUTHENTICATED_EVENT, type User } from '../api'
import { getToken, setToken } from '../api/config'

export type LoginOutcome = { ok: true } | { ok: false; reason: 'not_faculty' }

interface SessionValue {
  user: User | null
  /** True while a stored token is being checked on first load. */
  isRestoring: boolean
  login: (username: string, password: string) => Promise<LoginOutcome>
  logout: () => void
}

const SessionContext = createContext<SessionValue | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [isRestoring, setIsRestoring] = useState<boolean>(() => getToken() !== null)

  const logout = useCallback((): void => {
    setToken(null)
    setUser(null)
  }, [])

  useEffect(() => {
    if (!getToken()) return
    let cancelled = false
    api.me()
      .then((me) => {
        if (cancelled) return
        if (me.role === 'faculty') setUser(me)
        else logout()
      })
      .catch(() => { if (!cancelled) logout() })
      .finally(() => { if (!cancelled) setIsRestoring(false) })
    return () => { cancelled = true }
  }, [logout])

  // Any API call that comes back not_authenticated sends the user back to login.
  useEffect(() => {
    const onExpired = (): void => logout()
    window.addEventListener(UNAUTHENTICATED_EVENT, onExpired)
    return () => window.removeEventListener(UNAUTHENTICATED_EVENT, onExpired)
  }, [logout])

  const login = useCallback(async (username: string, password: string): Promise<LoginOutcome> => {
    const res = await api.login(username, password)
    if (res.user.role !== 'faculty') {
      logout()
      return { ok: false, reason: 'not_faculty' }
    }
    setToken(res.access_token)
    setUser(res.user)
    return { ok: true }
  }, [logout])

  const value = useMemo(() => ({ user, isRestoring, login, logout }), [user, isRestoring, login, logout])
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession(): SessionValue {
  const ctx = useContext(SessionContext)
  if (!ctx) throw new Error('useSession must be used inside SessionProvider')
  return ctx
}
